package com.modocument.api.security;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC enforcement over real HTTP semantics (MockMvc with the real security
 * filter chain and method security): per permission family, a READ_ONLY
 * token is denied writes (403) but allowed reads (200/404), READ_WRITE can
 * author but not administer, APPROVER only reads, ADMIN passes everything —
 * and a token without a roles claim gets a clean 403, never a 500.
 *
 * <p>{@code @Transactional}: everything each test writes (users, templates,
 * lookup rows) rolls back, so the shared H2 seed counts stay intact for
 * ContextLoadsAndSeedTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PermissionEnforcementTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JwtEncoder jwtEncoder;

    private String readOnlyToken;
    private String readWriteToken;
    private String approverToken;
    private String adminToken;
    private AppUser readOnlyUser;

    @BeforeEach
    void createOneUserPerRole() {
        readOnlyUser = user(Role.READ_ONLY);
        readOnlyToken = jwtService.issueToken(readOnlyUser);
        readWriteToken = jwtService.issueToken(user(Role.READ_WRITE));
        approverToken = jwtService.issueToken(user(Role.APPROVER));
        adminToken = jwtService.issueToken(user(Role.ADMIN));
    }

    private AppUser user(Role role) {
        AppUser entity = new AppUser();
        entity.setEmail("perm-" + role + "-" + UUID.randomUUID() + "@example.com");
        entity.setPasswordHash("{noop}irrelevant");
        entity.setDisplayName("Perm " + role);
        entity.setBankId(1);
        entity.setRole(role);
        entity.setSettingsJson("{}");
        return users.save(entity);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    // ------------------------------------------------------------- READ_ONLY

    @Test
    void readOnlyCanReadEveryFamily() throws Exception {
        for (String route : new String[] {
                "/Templates/GetAll",            // TEMPLATE_READ
                "/ValidationRules/GetByTemplate/999999", // VALIDATION_READ (404 body below)
                "/ImportHistory/GetAll",        // IMPORT_READ
                "/api/ingestion/jobs",          // INGESTION_READ
                "/api/extraction/sources",      // EXTRACTION_READ
                "/api/doccreation/blueprints",  // DOC_READ
                "/ProcessStep/GetAll",          // LOOKUP_READ
                "/TemplateDataTypes/GetAll",    // LOOKUP_READ
                "/api/auth/me"}) {              // PROFILE_SELF
            int status = mvc.perform(get(route).header("Authorization", bearer(readOnlyToken)))
                    .andReturn().getResponse().getStatus();
            org.assertj.core.api.Assertions.assertThat(status)
                    .as("READ_ONLY GET %s must pass the permission layer (200/404, never 403)",
                            route)
                    .isIn(200, 404);
        }
    }

    @Test
    void readOnlyIsDeniedWritesUploadsAiExportsAndAdminSurfaces() throws Exception {
        String auth = bearer(readOnlyToken);

        // TEMPLATE_WRITE
        mvc.perform(post("/Templates/Create").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"t\",\"description\":\"d\"}"))
                .andExpect(status().isForbidden());
        // VALIDATION_WRITE
        mvc.perform(put("/ValidationRules/Upsert").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        // IMPORT_WRITE
        mvc.perform(post("/ImportHistory/Create").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        // INGESTION_WRITE
        mvc.perform(put("/api/ingestion/jobs/1/mapping").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mapping\":{\"a\":\"b\"}}"))
                .andExpect(status().isForbidden());
        // EXTRACTION_WRITE
        mvc.perform(post("/api/extraction/sources").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        // EXTRACTION_RUN
        mvc.perform(post("/api/extraction/jobs/some-id/start").header("Authorization", auth))
                .andExpect(status().isForbidden());
        // EXPORT_RUN (PRIV-005: read-only users never export)
        mvc.perform(get("/api/ingestion/jobs/1/export/file").header("Authorization", auth))
                .andExpect(status().isForbidden());
        // FILE_UPLOAD
        mvc.perform(multipart("/api/fileupload/upload")
                        .file(new MockMultipartFile("file", "a.csv", "text/csv", "a,b".getBytes()))
                        .header("Authorization", auth))
                .andExpect(status().isForbidden());
        // AI_USE
        mvc.perform(post("/api/aifiles/send").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"hi\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/transform/transform").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"x.csv\"}"))
                .andExpect(status().isForbidden());
        // DOC_WRITE / DOC_GENERATE
        mvc.perform(post("/api/doccreation/instances").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blueprintId\":1,\"title\":\"t\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/doccreation/instances/1/generate").header("Authorization", auth))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/doccreation/instances/1/download").header("Authorization", auth))
                .andExpect(status().isForbidden());
        // LOOKUP_ADMIN
        mvc.perform(post("/ProcessStep/Create").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"n\",\"description\":\"d\"}"))
                .andExpect(status().isForbidden());
        // METRICS_READ (admin-only)
        mvc.perform(get("/api/extraction/metrics").header("Authorization", auth))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------ READ_WRITE

    @Test
    void readWriteCanAuthorButNotAdminister() throws Exception {
        String auth = bearer(readWriteToken);

        mvc.perform(post("/Templates/Create").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rw template\",\"description\":\"d\"}"))
                .andExpect(status().isCreated());

        // Permission granted, resource absent: a 404, proving the 403s above
        // are the permission layer and not a blanket deny.
        mvc.perform(get("/api/ingestion/jobs/999999").header("Authorization", auth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/ingestion/jobs/999999/export/file").header("Authorization", auth))
                .andExpect(status().isNotFound());

        // Shared lookups and metrics stay admin-only.
        mvc.perform(post("/ProcessStep/Create").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"n\",\"description\":\"d\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/TemplateDataTypes/Create").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataType\":\"Geo\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/extraction/metrics").header("Authorization", auth))
                .andExpect(status().isForbidden());
    }

    // --------------------------------------------------------------- APPROVER

    @Test
    void approverReadsButNeverAuthorsUploadsOrExports() throws Exception {
        String auth = bearer(approverToken);

        mvc.perform(get("/Templates/GetAll").header("Authorization", auth))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", auth))
                .andExpect(status().isOk());

        mvc.perform(post("/Templates/Create").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"t\",\"description\":\"d\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/fileupload/upload")
                        .file(new MockMultipartFile("file", "a.csv", "text/csv", "a,b".getBytes()))
                        .header("Authorization", auth))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ingestion/jobs/1/export/file").header("Authorization", auth))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ ADMIN

    @Test
    void adminPassesEverythingIncludingAdminOnlySurfaces() throws Exception {
        String auth = bearer(adminToken);

        mvc.perform(post("/Templates/Create").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"admin template\",\"description\":\"d\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/ProcessStep/Create").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Archiving\",\"description\":\"admin-managed\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/TemplateDataTypes/Create").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataType\":\"Geo\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/extraction/metrics").header("Authorization", auth))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------- degenerate role claims

    @Test
    void tokenWithoutRolesClaimIsForbiddenNotAServerError() throws Exception {
        // Signed, unexpired, valid user and token_ver — only the roles claim
        // is missing (a stale pre-RBAC token). Must be a clean 403.
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(readOnlyUser.getAppUserId()))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(10, ChronoUnit.MINUTES))
                .claim(JwtService.CLAIM_BANK_ID, readOnlyUser.getBankId())
                .claim(JwtService.CLAIM_TOKEN_VERSION, readOnlyUser.getTokenVersion())
                .build();
        String roleless = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        mvc.perform(get("/Templates/GetAll").header("Authorization", bearer(roleless)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/Templates/Create").header("Authorization", bearer(roleless))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"t\",\"description\":\"d\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousStaysUnauthorized() throws Exception {
        mvc.perform(get("/Templates/GetAll")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/extraction/metrics")).andExpect(status().isUnauthorized());
    }
}
