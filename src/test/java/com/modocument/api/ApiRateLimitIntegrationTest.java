package com.modocument.api;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.ratelimit.ApiRateLimitInterceptor;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate E2 / SEC-007: every expensive endpoint answers 429 + Retry-After once
 * the caller's per-user bucket is spent ({@link ApiRateLimitInterceptor},
 * {@code modocument.ratelimit.*}). Every bucket is sized to ONE permit in this
 * context, so the first call spends it and the second is throttled. The
 * first calls are deliberately cheap failures (missing body, unknown id):
 * the interceptor meters ATTEMPTS — it runs before argument binding and the
 * handler — so a flood of invalid requests is contained too, and no test
 * here does real pipeline work.
 *
 * <p>All callers are ADMINs: the limits apply to admins exactly as to anyone
 * else (an admin token is as scriptable and as stealable). Each test uses
 * fresh users, because the buckets live for the context's lifetime.
 */
@SpringBootTest(properties = {
        "modocument.ratelimit.uploads=1",
        "modocument.ratelimit.ingestion=1",
        "modocument.ratelimit.transform=1",
        "modocument.ratelimit.exports=1",
        "modocument.ratelimit.doc-generate=1",
        "modocument.ratelimit.extraction=1",
        "modocument.ratelimit.dsr=1",
        "modocument.ratelimit.password-change=1",
        "modocument.ratelimit.mfa-code=1",
        "modocument.ratelimit.admin-jobs=1",
        "modocument.ratelimit.admin-security=1",
        "modocument.security.rate-limit.llm.per-user-max=1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiRateLimitIntegrationTest {

    /** Default window (modocument.ratelimit.window-millis): 1 hour. */
    private static final long WINDOW_SECONDS = 3600;
    private static final int MISSING_ID = 987_654_321;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private ApiRateLimitInterceptor apiRateLimit;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private final List<AppUser> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        created.forEach(user -> users.deleteById(user.getAppUserId()));
        created.clear();
    }

    /** One representative route per bucket family; a fresh request each call. */
    static Stream<Arguments> bucketFamilies() {
        return Stream.of(
                family("uploads", () -> multipart("/api/fileupload/upload")),
                family("ingestion", () -> post("/api/ingestion/jobs")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")),
                family("transform", () -> post("/api/transform/transform")),
                family("exports", () -> get("/api/ingestion/jobs/{id}/export/file", MISSING_ID)),
                family("doc-generate", () ->
                        post("/api/doccreation/instances/{id}/generate", MISSING_ID)),
                family("extraction", () -> post("/api/extraction/jobs/{id}/start", "no-such-job")),
                family("dsr", () -> post("/api/admin/dsr/search")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")),
                family("password-change", () -> put("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")),
                family("mfa-code", () -> post("/api/auth/mfa/activate")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")),
                family("admin-security", () ->
                        post("/api/admin/users/{id}/password-reset", MISSING_ID)
                                .contentType(MediaType.APPLICATION_JSON).content("{}")));
    }

    private static Arguments family(String bucket, Call request) {
        return Arguments.of(bucket, request);
    }

    /** A fresh request per call (builders are single-use). */
    @FunctionalInterface
    interface Call {
        AbstractMockHttpServletRequestBuilder<?> get();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bucketFamilies")
    void secondCallInTheWindowIs429WithRetryAfter(String bucket, Call request)
            throws Exception {
        String admin = bearer(Role.ADMIN);

        int first = statusOf(request.get(), admin);
        assertThat(first).as("first %s call spends the single permit", bucket).isNotEqualTo(429);

        MvcResult throttled = mvc.perform(request.get().header("Authorization", admin))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andReturn();
        long retryAfter = Long.parseLong(throttled.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, WINDOW_SECONDS);
        assertThat(throttled.getResponse().getContentAsString()).contains("limit");
    }

    @Test
    void bucketsArePerUser() throws Exception {
        String userA = bearer(Role.ADMIN);
        String userB = bearer(Role.ADMIN);

        assertThat(statusOf(multipart("/api/fileupload/upload"), userA)).isNotEqualTo(429);
        assertThat(statusOf(multipart("/api/fileupload/upload"), userA)).isEqualTo(429);

        // A's exhausted bucket does not touch B.
        assertThat(statusOf(multipart("/api/fileupload/upload"), userB)).isNotEqualTo(429);
    }

    @Test
    void bucketsAreIndependentOfEachOther() throws Exception {
        String user = bearer(Role.ADMIN);
        assertThat(statusOf(multipart("/api/fileupload/upload"), user)).isNotEqualTo(429);
        assertThat(statusOf(multipart("/api/fileupload/upload"), user)).isEqualTo(429);

        assertThat(statusOf(post("/api/ingestion/jobs")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), user)).isNotEqualTo(429);
    }

    @Test
    void routesInOneBucketShareItsPermits() throws Exception {
        String user = bearer(Role.ADMIN);
        // Both DSR routes and both MFA-code routes draw on one bucket each.
        assertThat(statusOf(post("/api/admin/dsr/search")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), user)).isNotEqualTo(429);
        assertThat(statusOf(post("/api/admin/dsr/erase")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), user)).isEqualTo(429);

        assertThat(statusOf(post("/api/auth/mfa/activate")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), user)).isNotEqualTo(429);
        assertThat(statusOf(delete("/api/auth/mfa")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), user)).isEqualTo(429);

        // The ws18 POST export twin (over-cap reason in the body) draws on
        // the GET download's exports bucket — not a second, unmetered path.
        assertThat(statusOf(get("/api/ingestion/jobs/{id}/export/file", MISSING_ID), user))
                .isNotEqualTo(429);
        assertThat(statusOf(post("/api/ingestion/jobs/{id}/export/file", MISSING_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"format\":\"csv\",\"reason\":\"rate-limit test\"}"), user))
                .isEqualTo(429);
    }

    @Test
    void neitherCasingNorHeadDodgesABucket() throws Exception {
        // The handler mapping is case-insensitive; the bucket is chosen from
        // the matched route pattern, so an upper-cased path spends the same
        // permits.
        String user = bearer(Role.ADMIN);
        assertThat(statusOf(multipart("/API/FILEUPLOAD/UPLOAD"), user)).isNotEqualTo(429);
        assertThat(statusOf(multipart("/api/fileupload/upload"), user)).isEqualTo(429);

        // HEAD runs the GET export handler: it spends the GET bucket.
        String other = bearer(Role.ADMIN);
        assertThat(statusOf(head("/api/ingestion/jobs/{id}/export/file", MISSING_ID), other))
                .isNotEqualTo(429);
        assertThat(statusOf(get("/api/ingestion/jobs/{id}/export/file", MISSING_ID), other))
                .isEqualTo(429);
    }

    @Test
    void uncoveredMethodsOnACoveredPathStayUnmetered() throws Exception {
        String user = bearer(Role.ADMIN);
        for (int i = 0; i < 3; i++) {
            assertThat(statusOf(get("/api/ingestion/jobs"), user)).isEqualTo(200);
        }
    }

    @Test
    void llmQuotaCannotBeDodgedByCasingAndCoversTheExtractionAiHelpers() throws Exception {
        String user = bearer(Role.ADMIN);
        // Spends the single LLM permit (400 on the empty parameters, before
        // any provider call), then the lower-case path is throttled.
        assertThat(statusOf(post("/API/AUTOMAPPER/GENERATE"), user)).isNotEqualTo(429);
        assertThat(statusOf(post("/api/automapper/generate"), user)).isEqualTo(429);

        // infer-schema shares the same per-user LLM quota (unknown job: 404
        // before any LLM call, but the attempt is metered).
        String other = bearer(Role.ADMIN);
        assertThat(statusOf(post("/api/extraction/jobs/{id}/infer-schema", "no-such-job"), other))
                .isNotEqualTo(429);
        assertThat(statusOf(get("/api/extraction/jobs/{id}/summary", "no-such-job"), other))
                .isEqualTo(429);
    }

    @Test
    void everyConfiguredRouteIsARealHandlerRoute() {
        // A typo in the route table would silently leave an endpoint
        // unlimited; every entry must name an existing METHOD + pattern.
        Set<String> handlerRoutes = new HashSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            info.getMethodsCondition().getMethods().forEach(method ->
                    info.getPatternValues().forEach(pattern ->
                            handlerRoutes.add(method.name() + " " + pattern)));
        }
        assertThat(handlerRoutes).containsAll(apiRateLimit.routes().keySet());
        assertThat(apiRateLimit.routes().values()).containsOnly(
                "uploads", "ingestion", "transform", "exports", "doc-generate", "extraction",
                "dsr", "password-change", "mfa-code", "admin-jobs", "admin-security");
    }

    // ------------------------------------------------------------------ helpers

    private int statusOf(AbstractMockHttpServletRequestBuilder<?> request, String bearer)
            throws Exception {
        return mvc.perform(request.header("Authorization", bearer))
                .andReturn().getResponse().getStatus();
    }

    private String bearer(Role role) {
        AppUser entity = new AppUser();
        entity.setEmail("ws17-rate-" + UUID.randomUUID() + "@example.com");
        entity.setPasswordHash("{noop}irrelevant");
        entity.setDisplayName("Rate Limit Test");
        entity.setBankId(1);
        entity.setRole(role);
        entity.setSettingsJson("{}");
        AppUser saved = users.save(entity);
        created.add(saved);
        return "Bearer " + jwtService.issueToken(saved);
    }
}
