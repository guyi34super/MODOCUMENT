package com.modocument.api;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.RefreshToken;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.RefreshTokenJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The trusted-proxy model end to end (SEC-022): with
 * {@code server.forward-headers-strategy: framework} the ForwardedHeaderFilter
 * rewrites the request from X-Forwarded-* BEFORE anything reads it, and the
 * fronting nginx OVERWRITES those headers with its own single-hop values
 * ($remote_addr/$scheme). These tests exercise what the app sees AFTER that
 * rewrite: the effective client IP is exactly the first X-Forwarded-For hop
 * (any client-smuggled hops beyond it are ignored), per-IP rate limiting keys
 * on it, 429s carry a usable Retry-After, and HSTS fires once the proxy
 * reports TLS (X-Forwarded-Proto: https) — which it never does for plain HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TrustedProxyIntegrationTest {

    /** Default per-IP window: 30 permits / 15 min (AuthRateLimiter). */
    private static final int PER_IP_MAX = 30;
    private static final long WINDOW_SECONDS = 900;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private RefreshTokenJpaRepository refreshTokens;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private AppUser user;

    /** Distinct per use: the auth throttle windows are shared singletons. */
    private static final java.util.concurrent.atomic.AtomicInteger IP_COUNTER =
            new java.util.concurrent.atomic.AtomicInteger();

    private static String uniqueIp() {
        int n = IP_COUNTER.incrementAndGet();
        return "203.0." + (100 + n / 250) + "." + (n % 250);
    }

    @AfterEach
    void cleanUp() {
        if (user != null) {
            refreshTokens.findByUserId(user.getAppUserId()).forEach(refreshTokens::delete);
            users.deleteById(user.getAppUserId());
            user = null;
        }
    }

    // ------------------------------------------------------------------ HSTS

    @Test
    void hstsIsEmittedBehindTlsTerminationAndNeverOnPlainHttp() throws Exception {
        // The proxy says the client connection was TLS → the rewritten request
        // is "secure" and the configured HSTS header actually goes out.
        mvc.perform(get("/actuator/health").header("X-Forwarded-Proto", "https"))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security",
                        containsString("max-age=31536000")))
                .andExpect(header().string("Strict-Transport-Security",
                        containsString("includeSubDomains")));

        // Plain HTTP (local dev, health probes): no HSTS — pinning HTTPS on a
        // host that serves none would brick the origin for a year.
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    // ------------------------------------------- effective client IP (rewrite)

    /**
     * What the app records as "the client" is the FIRST X-Forwarded-For hop —
     * the one slot the trusted proxy overwrites with $remote_addr. Hops a
     * client might have smuggled beyond it change nothing.
     */
    @Test
    void theEffectiveClientIpIsTheRewrittenFirstHopNotSmuggledExtras() throws Exception {
        user = savedUser();
        String proxyHop = uniqueIp();

        mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", proxyHop + ", 6.6.6.6, evil")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail()
                                + "\",\"password\":\"Sup3r-secret!\"}"))
                .andExpect(status().isOk());

        List<RefreshToken> stored = refreshTokens.findByUserId(user.getAppUserId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getIp()).isEqualTo(proxyHop);
    }

    /**
     * The per-IP limiter keys on that same rewritten hop: varying the junk
     * beyond hop one does NOT buy a fresh window (the old append-based nginx
     * config plus hand-parsing made exactly that bypass possible), while a
     * different first hop — a genuinely different client after the rewrite —
     * is unthrottled.
     */
    @Test
    void perIpThrottleKeysOnTheFirstHopAndCannotBeDodgedWithExtraHops() throws Exception {
        String attacker = uniqueIp();
        String bystander = uniqueIp();

        // /api/auth/refresh without a cookie is a cheap 401 that still spends
        // a per-IP permit (same AuthRateLimiter as login, no email key).
        for (int i = 0; i < PER_IP_MAX; i++) {
            mvc.perform(post("/api/auth/refresh")
                            .header("X-Forwarded-For", attacker + ", 6.6.6." + i))
                    .andExpect(status().isUnauthorized());
        }

        // Window spent: fresh junk after hop one changes nothing — still 429.
        mvc.perform(post("/api/auth/refresh")
                        .header("X-Forwarded-For", attacker + ", 7.7.7.7, 8.8.8.8"))
                .andExpect(status().isTooManyRequests());

        // A different FIRST hop is a different client: not throttled.
        mvc.perform(post("/api/auth/refresh").header("X-Forwarded-For", bystander))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------ Retry-After on 429

    @Test
    void throttledLoginAnswers429WithASaneRetryAfter() throws Exception {
        user = savedUser();
        String ip = uniqueIp();

        // Spend the shared per-IP window cheaply (cookie-less refreshes)...
        for (int i = 0; i < PER_IP_MAX; i++) {
            mvc.perform(post("/api/auth/refresh").header("X-Forwarded-For", ip))
                    .andExpect(status().isUnauthorized());
        }

        // ...then a login from the same client is throttled and told for how long.
        MvcResult throttled = mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail()
                                + "\",\"password\":\"Sup3r-secret!\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andReturn();

        long retryAfter = Long.parseLong(throttled.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, WINDOW_SECONDS);
    }

    private AppUser savedUser() {
        AppUser entity = new AppUser();
        entity.setEmail("proxy-" + UUID.randomUUID() + "@example.com");
        entity.setPasswordHash(passwordEncoder.encode("Sup3r-secret!"));
        entity.setDisplayName("Proxy Test");
        entity.setBankId(1);
        entity.setRole(Role.READ_WRITE);
        entity.setSettingsJson("{}");
        return users.save(entity);
    }
}
