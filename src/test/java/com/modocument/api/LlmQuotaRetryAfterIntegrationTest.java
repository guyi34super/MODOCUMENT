package com.modocument.api;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The LLM-spending endpoints answer their 429 with a Retry-After header
 * (LlmQuotaInterceptor → LlmRateLimiter → ApiExceptionHandler), so clients
 * know how long the per-user window has left instead of hammering. Quota of
 * ONE for this context: the first call spends it — and 400s on the empty
 * parameters BEFORE any provider work, so nothing leaves the process — and
 * the second is throttled.
 */
class LlmQuotaRetryAfterIntegrationTest extends OnePermitRateLimitTestSupport {

    /** Default LLM window: 1 hour (LlmRateLimiter). */
    private static final long WINDOW_SECONDS = 3600;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private JwtService jwtService;

    private AppUser user;

    @AfterEach
    void cleanUp() {
        if (user != null) {
            users.deleteById(user.getAppUserId());
        }
    }

    @Test
    void llmEndpointAnswers429WithASaneRetryAfterOnceTheQuotaIsSpent() throws Exception {
        user = savedUser();
        String bearer = jwtService.issueToken(user);

        // Spends the single permit; the controller rejects the empty
        // parameters (400) before any LLM call could happen.
        mvc.perform(post("/api/automapper/generate")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isBadRequest());

        MvcResult throttled = mvc.perform(post("/api/automapper/generate")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andReturn();

        long retryAfter = Long.parseLong(throttled.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, WINDOW_SECONDS);
    }

    private AppUser savedUser() {
        AppUser entity = new AppUser();
        entity.setEmail("llm-quota-" + UUID.randomUUID() + "@example.com");
        entity.setPasswordHash("{noop}irrelevant");
        entity.setDisplayName("Llm Quota Test");
        entity.setBankId(1);
        entity.setRole(Role.READ_WRITE); // READ_WRITE carries AI_USE
        entity.setSettingsJson("{}");
        return users.save(entity);
    }
}
