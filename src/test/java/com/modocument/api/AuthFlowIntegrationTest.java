package com.modocument.api;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.RefreshTokenJpaRepository;
import com.modocument.infrastructure.persistence.RevokedTokenJpaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole token lifecycle over real HTTP semantics (MockMvc with the real
 * security filter chain): login issues an access token plus an httpOnly
 * rotating refresh cookie; refresh rotates within the family's FIXED 7-day
 * expiry (absolute bound) and refuses a token idle past the configured
 * timeout; reusing a rotated cookie kills the family; logout denylists the
 * access token's jti; a password change invalidates every outstanding access
 * token (via the shared TokenVersion-bump seam, gate B5); and the old public
 * /register route is gone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASSWORD = "Sup3r-secret!";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private RefreshTokenJpaRepository refreshTokens;
    @Autowired
    private RevokedTokenJpaRepository revokedTokens;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private com.modocument.application.usecase.UserSecurityService userSecurity;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private AppUser user;

    @BeforeEach
    void createUser() {
        AppUser entity = new AppUser();
        entity.setEmail("flow-" + UUID.randomUUID() + "@example.com");
        entity.setPasswordHash(passwordEncoder.encode(PASSWORD));
        entity.setDisplayName("Flow Test");
        entity.setBankId(1);
        entity.setRole(Role.READ_WRITE);
        entity.setSettingsJson("{}");
        user = users.save(entity);
    }

    @AfterEach
    void cleanUp() {
        refreshTokens.findByUserId(user.getAppUserId()).forEach(refreshTokens::delete);
        revokedTokens.deleteAll();
        // Audit rows this suite's assertions read (session-expiry, bump) are
        // per-user; scrub them so no other suite sees leftovers.
        jdbc.update("DELETE FROM AuditEvent WHERE ActorUserId = ?", user.getAppUserId());
        users.deleteById(user.getAppUserId());
    }

    /** The newest (live) token of the user's only family. */
    private com.modocument.domain.entity.RefreshToken newestToken() {
        return refreshTokens.findByUserId(user.getAppUserId()).stream()
                .max(java.util.Comparator.comparing(
                        com.modocument.domain.entity.RefreshToken::getIssuedAt))
                .orElseThrow();
    }

    private int auditCount(String action) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = ? AND Outcome = ? "
                        + "AND ActorUserId = ?",
                Integer.class, action, "FAILED", user.getAppUserId());
        return count == null ? 0 : count;
    }

    private MvcResult login() throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\""
                                + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String token(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private static Cookie refreshCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("modoc_refresh");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    @Test
    void publicRegistrationIsGone() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"intruder@example.com\",\"password\":\"Password1!\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginReturnsUserInfoInBodyAndRefreshCookie() throws Exception {
        MvcResult result = login();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("user").get("email").asText()).isEqualTo(user.getEmail());
        assertThat(body.get("user").get("role").asText()).isEqualTo("READ_WRITE");
        assertThat(body.get("user").get("bankId").asInt()).isEqualTo(1);

        Cookie cookie = refreshCookie(result);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/auth/refresh");

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token(result)))
                .andExpect(status().isOk());
    }

    @Test
    void refreshRotatesAndReuseRevokesTheWholeFamily() throws Exception {
        Cookie first = refreshCookie(login());

        // Rotation: the same cookie name comes back with a new value.
        MvcResult rotated = mvc.perform(post("/api/auth/refresh").cookie(first))
                .andExpect(status().isOk())
                .andReturn();
        Cookie second = refreshCookie(rotated);
        assertThat(second.getValue()).isNotEqualTo(first.getValue());
        assertThat(token(rotated)).isNotBlank();

        // Reuse detection: presenting the rotated-away cookie is treated as
        // theft and 401s...
        mvc.perform(post("/api/auth/refresh").cookie(first))
                .andExpect(status().isUnauthorized());

        // ...and the WHOLE family is dead, including the fresh token.
        mvc.perform(post("/api/auth/refresh").cookie(second))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheAccessTokenAndRefreshTokens() throws Exception {
        MvcResult session = login();
        String accessToken = token(session);
        Cookie refresh = refreshCookie(session);

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        // The jti is denylisted, so the otherwise-valid JWT dies immediately...
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        // ...and the refresh token cannot resurrect the session.
        mvc.perform(post("/api/auth/refresh").cookie(refresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordChangeInvalidatesEveryOutstandingAccessToken() throws Exception {
        MvcResult session = login();
        String accessToken = token(session);

        mvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD
                                + "\",\"newPassword\":\"Even-m0re-secret!\"}"))
                .andExpect(status().isNoContent());

        // TokenVersion bumped: the very token that made the change is dead.
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshWithoutACookieIsUnauthorized() throws Exception {
        mvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------- session lifetimes (SUP-S02)

    /**
     * Absolute bound: the family's ExpiresAt is fixed at login; rotation
     * inherits it unchanged, so refreshing can never extend a session past
     * 7 days from the original sign-in.
     */
    @Test
    void rotationKeepsTheFamilyExpiryFixedAtLogin() throws Exception {
        Cookie first = refreshCookie(login());
        java.time.Instant familyExpiry = newestToken().getExpiresAt();

        mvc.perform(post("/api/auth/refresh").cookie(first))
                .andExpect(status().isOk());

        assertThat(newestToken().getExpiresAt()).isEqualTo(familyExpiry);
    }

    /** Absolute bound enforced: a family past its ExpiresAt is refused + audited. */
    @Test
    void anExpiredFamilyIsRefusedAndAudited() throws Exception {
        Cookie cookie = refreshCookie(login());
        var newest = newestToken();
        newest.setExpiresAt(java.time.Instant.now().minusSeconds(1)); // the 7 days are over
        refreshTokens.saveAndFlush(newest);

        mvc.perform(post("/api/auth/refresh").cookie(cookie))
                .andExpect(status().isUnauthorized());

        assertThat(auditCount("SESSION_ABSOLUTE_EXPIRED")).isEqualTo(1);
    }

    /**
     * Idle bound: a refresh token unused past
     * modocument.security.session.idle-timeout (12h default; last use = the
     * newest rotation's IssuedAt) is refused even though the family is young.
     */
    @Test
    void anIdleSessionIsRefusedAndAuditedEvenWhileTheFamilyIsYoung() throws Exception {
        Cookie cookie = refreshCookie(login());
        var newest = newestToken();
        newest.setIssuedAt(java.time.Instant.now().minus(13, java.time.temporal.ChronoUnit.HOURS));
        refreshTokens.saveAndFlush(newest);

        mvc.perform(post("/api/auth/refresh").cookie(cookie))
                .andExpect(status().isUnauthorized());

        assertThat(auditCount("SESSION_IDLE_EXPIRED")).isEqualTo(1);
        // Refused, not reuse-revoked: retrying is the same quiet refusal, not
        // the family-killing theft alarm.
        mvc.perform(post("/api/auth/refresh").cookie(cookie))
                .andExpect(status().isUnauthorized());
        assertThat(auditCount("SESSION_IDLE_EXPIRED")).isEqualTo(2);
    }

    // ------------------------------------------------------ B5 bump seam

    /**
     * Gate B5 through the shared seam: {@code UserSecurityService
     * .bumpTokenVersion} — what the ws09 role-change path will call — kills
     * every outstanding access token on its next request.
     */
    @Test
    void aTokenVersionBumpThroughTheSharedSeamKillsOutstandingAccessTokens() throws Exception {
        String accessToken = token(login());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        userSecurity.bumpTokenVersion(user.getAppUserId(), "role-change");

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        // The bump audited itself with the reason it was asked for.
        Integer bumps = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = 'TOKEN_VERSION_BUMPED' "
                        + "AND Outcome = 'SUCCESS' AND ActorUserId = ?",
                Integer.class, user.getAppUserId());
        assertThat(bumps).isEqualTo(1);
    }
}
