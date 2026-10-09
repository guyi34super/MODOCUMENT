package com.modocument.api;

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
import org.springframework.test.web.servlet.ResultMatcher;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the API's security-header contract (E1) exactly as PRODUCTION emits
 * it — dev tooling off ({@code permit-dev-paths=false}), so the strict CSP,
 * not the Swagger/H2-relaxed one, is what is asserted. The SPA's headers are
 * nginx's job (frontend/security-headers.conf, guarded by
 * scripts/check-nginx-headers.sh); nginx deliberately adds nothing on top of
 * these for proxied API responses, so this test IS the API half of the
 * header inventory in docs/security/web-hardening.md.
 *
 * <p>Every header must appear exactly once ({@code stringValues}), on a
 * public endpoint, an authenticated endpoint and a rejected (401) request.
 * HSTS behind TLS termination is covered in depth by
 * TrustedProxyIntegrationTest; asserted here only as part of the contract.
 */
@SpringBootTest(properties = {
        "modocument.security.permit-dev-paths=false",
        // Production mode refuses the publicly known dev secret and needs a
        // dedicated MFA key + a first admin; test-only values.
        "modocument.security.jwt.secret=header-contract-test-signing-key-0123456789abcdef",
        "modocument.security.mfa.key=header-contract-test-mfa-encryption-key-0123456789",
        "MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL=header-contract-admin@example.com",
        "MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD=Header-Contract-Passphrase-2026!",
        // Own in-memory database: this context's ddl-auto=create must not
        // wipe the schema the shared test contexts are using.
        "spring.datasource.url=jdbc:h2:mem:modocument-header-contract;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HeaderContractTest {

    /** A JSON API serves no markup: nothing may load, nothing may frame it. */
    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private JwtService jwtService;

    private static ResultMatcher apiSecurityHeaders() {
        return ResultMatcher.matchAll(
                header().stringValues("Content-Security-Policy", API_CSP),
                header().stringValues("X-Frame-Options", "DENY"),
                header().stringValues("X-Content-Type-Options", "nosniff"),
                header().stringValues("Referrer-Policy", "no-referrer"));
    }

    @Test
    void publicEndpointsCarryTheApiHeaderSet() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(apiSecurityHeaders());
        mvc.perform(get("/api/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(apiSecurityHeaders());
    }

    @Test
    void authenticatedEndpointCarriesTheApiHeaderSetAndIsNeverCached() throws Exception {
        String token = jwtService.issueToken(readOnlyUser());

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(apiSecurityHeaders())
                // Per-user data must not land in shared/browser caches.
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void rejectedRequestsCarryTheSetToo() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(apiSecurityHeaders());
    }

    @Test
    void theDevToolingCspNeverReachesProduction() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(header().string("Content-Security-Policy", not(containsString("unsafe-inline"))))
                .andExpect(header().string("X-Frame-Options", not(containsString("SAMEORIGIN"))));
    }

    @Test
    void hstsIsPartOfTheContractBehindTlsOnly() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Forwarded-Proto", "https"))
                .andExpect(header().stringValues("Strict-Transport-Security",
                        "max-age=31536000 ; includeSubDomains"));
        mvc.perform(get("/actuator/health"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    private AppUser readOnlyUser() {
        int bankId = users.findByEmailIgnoreCase("header-contract-admin@example.com")
                .orElseThrow()
                .getBankId();
        AppUser entity = new AppUser();
        entity.setEmail("headers-" + UUID.randomUUID() + "@example.com");
        entity.setPasswordHash("{noop}irrelevant");
        entity.setDisplayName("Header Contract");
        entity.setBankId(bankId);
        entity.setRole(Role.READ_ONLY);
        entity.setSettingsJson("{}");
        return users.save(entity);
    }
}
