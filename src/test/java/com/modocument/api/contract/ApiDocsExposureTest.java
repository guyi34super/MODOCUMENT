package com.modocument.api.contract;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The /v3/api-docs exposure decision (ws17, SEC-006), proven in a
 * production-shaped context: permit-dev-paths OFF (so the dev permitAll for
 * the API docs is gone and the startup guards demand a real JWT secret, an
 * MFA key and a bootstrap admin), its own in-memory database.
 *
 * <ul>
 *   <li>Anonymous → 401: no unauthenticated reconnaissance of the route
 *       catalogue.</li>
 *   <li>Any valid access token → 200, even the least-privileged role: the
 *       spec describes routes, not data, and every route still enforces its
 *       own permission (see docs/api/versioning-policy.md).</li>
 *   <li>The Swagger UI is not served outside dev.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "modocument.security.permit-dev-paths=false",
        "modocument.security.jwt.secret=ws17-prod-shaped-test-secret-6b1f0c9e4a2d7e85f3c1",
        "modocument.security.mfa.key=ws17-prod-shaped-test-mfa-key-91d0a7c3e5b2f4a8c6e0",
        "MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL=ws17-bootstrap@example.com",
        "MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD=Prod-Shaped-Context-9fK2#qL7!",
        "spring.datasource.url=jdbc:h2:mem:ws17-apidocs-prod;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiDocsExposureTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private JwtService jwtService;

    @Test
    void anonymousCallerIsRefused() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isUnauthorized());
    }

    @Test
    void anyAuthenticatedRoleMayReadTheGeneratedSpec() throws Exception {
        String bearer = "Bearer " + jwtService.issueToken(savedUser(Role.READ_ONLY));
        mvc.perform(get("/v3/api-docs").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme")
                        .value("bearer"));
    }

    @Test
    void swaggerUiIsNotServedOutsideDev() throws Exception {
        String bearer = "Bearer " + jwtService.issueToken(savedUser(Role.ADMIN));
        mvc.perform(get("/swagger-ui.html").header("Authorization", bearer))
                .andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui/index.html").header("Authorization", bearer))
                .andExpect(status().isNotFound());
    }

    private AppUser savedUser(Role role) {
        AppUser entity = new AppUser();
        entity.setEmail("ws17-docs-" + UUID.randomUUID() + "@example.com");
        entity.setPasswordHash("{noop}irrelevant");
        entity.setDisplayName("Api Docs Exposure");
        entity.setBankId(1);
        entity.setRole(role);
        entity.setSettingsJson("{}");
        return users.save(entity);
    }
}
