package com.modocument.api;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.RecoveryCodeJpaRepository;
import com.modocument.infrastructure.persistence.RefreshTokenJpaRepository;
import com.modocument.infrastructure.persistence.UserMfaJpaRepository;
import com.modocument.infrastructure.security.totp.Base32;
import com.modocument.infrastructure.security.totp.Totp;
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
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate B3 (MFA enforced) over real HTTP semantics: enrolment, activation and
 * the two-phase login; a password alone never buys a token once MFA is in
 * play; the challenge token is NOT an access token; wrong codes 401 and are
 * rate-limited; recovery codes work once; an ADMIN without MFA is forced
 * into enrolment and cannot obtain an access token on ANY path without a
 * verified second factor.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MfaFlowIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASSWORD = "Sup3r-secret-mfa!";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private RefreshTokenJpaRepository refreshTokens;
    @Autowired
    private UserMfaJpaRepository userMfa;
    @Autowired
    private RecoveryCodeJpaRepository recoveryCodes;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private AppUser user;
    /** Distinct per run: the login and MFA throttles are shared singletons. */
    private String clientIp;

    @BeforeEach
    void createUser() {
        user = saveUser(Role.READ_WRITE);
        clientIp = "10.7." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
    }

    @AfterEach
    void cleanUp() {
        for (AppUser created : users.findAll()) {
            if (created.getEmail().startsWith("mfa-")) {
                refreshTokens.findByUserId(created.getAppUserId()).forEach(refreshTokens::delete);
                recoveryCodes.deleteByUserId(created.getAppUserId());
                userMfa.deleteById(created.getAppUserId());
                users.deleteById(created.getAppUserId());
            }
        }
    }

    private AppUser saveUser(Role role) {
        AppUser entity = new AppUser();
        entity.setEmail("mfa-" + UUID.randomUUID() + "@example.com");
        entity.setPasswordHash(passwordEncoder.encode(PASSWORD));
        entity.setDisplayName("Mfa Test");
        entity.setBankId(1);
        entity.setRole(role);
        entity.setSettingsJson("{}");
        return users.save(entity);
    }

    private ResultActions login(AppUser account) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + account.getEmail() + "\",\"password\":\""
                        + PASSWORD + "\"}"));
    }

    private ResultActions verify(String challenge, String code) throws Exception {
        return mvc.perform(post("/api/auth/mfa/verify")
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(
                        new com.modocument.application.dto.MfaVerifyRequest(challenge, code))));
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static String currentCode(String base32Secret) {
        return Totp.codeAt(Base32.decode(base32Secret),
                Totp.counterAt(Instant.now().getEpochSecond()));
    }

    /** Full password-only login (no MFA yet) → access token. */
    private String passwordLoginToken(AppUser account) throws Exception {
        MvcResult result = login(account).andExpect(status().isOk()).andReturn();
        return body(result).get("token").asText();
    }

    /** Enrolls and activates via the given bearer token; returns the enrolment. */
    private JsonNode enrollAndActivate(String bearer) throws Exception {
        MvcResult enrolled = mvc.perform(post("/api/auth/mfa/enroll")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andExpect(jsonPath("$.otpauthUri").value(
                        org.hamcrest.Matchers.startsWith("otpauth://totp/")))
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10))
                .andReturn();
        JsonNode enrollment = body(enrolled);
        mvc.perform(post("/api/auth/mfa/activate")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + currentCode(enrollment.get("secret").asText())
                                + "\"}"))
                .andExpect(status().isNoContent());
        return enrollment;
    }

    // ----------------------------------------------------------- gate B3

    @Test
    void loginWithMfaEnabledYieldsNoTokensUntilTheCodeVerifies() throws Exception {
        String bearer = passwordLoginToken(user);
        JsonNode enrollment = enrollAndActivate(bearer);

        // Phase 1: correct password → challenge only. No token, no user
        // payload, no refresh cookie — nothing a session could be built from.
        MvcResult challenged = login(user)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.enrollmentRequired").value(false))
                .andExpect(jsonPath("$.challenge").isNotEmpty())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.user").doesNotExist())
                .andReturn();
        assertThat(challenged.getResponse().getCookie("modoc_refresh")).isNull();
        String challenge = body(challenged).get("challenge").asText();

        // B3: the challenge token is NOT an access token.
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + challenge))
                .andExpect(status().isUnauthorized());

        // Phase 2: challenge + TOTP code → the normal session shape.
        MvcResult session = verify(challenge, currentCode(enrollment.get("secret").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(user.getEmail()))
                .andReturn();
        Cookie refresh = session.getResponse().getCookie("modoc_refresh");
        assertThat(refresh).isNotNull();
        assertThat(refresh.isHttpOnly()).isTrue();
        String accessToken = body(session).get("token").asText();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void wrongCodeIs401AndTheSameCodeNeverVerifiesTwice() throws Exception {
        String bearer = passwordLoginToken(user);
        JsonNode enrollment = enrollAndActivate(bearer);
        String challenge = body(login(user).andExpect(status().isOk()).andReturn())
                .get("challenge").asText();

        verify(challenge, "000000")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid verification code."));

        // A valid code works once...
        String code = currentCode(enrollment.get("secret").asText());
        verify(challenge, code).andExpect(status().isOk());
        // ...and is burned afterwards (SEC-022 replay prevention).
        verify(challenge, code).andExpect(status().isUnauthorized());
    }

    @Test
    void recoveryCodeCompletesTheLoginExactlyOnce() throws Exception {
        String bearer = passwordLoginToken(user);
        JsonNode enrollment = enrollAndActivate(bearer);
        String recovery = enrollment.get("recoveryCodes").get(0).asText();

        String challenge = body(login(user).andExpect(status().isOk()).andReturn())
                .get("challenge").asText();
        verify(challenge, recovery)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        // Burned: the same recovery code never works again.
        String secondChallenge = body(login(user).andExpect(status().isOk()).andReturn())
                .get("challenge").asText();
        verify(secondChallenge, recovery).andExpect(status().isUnauthorized());
    }

    /** B3: an ADMIN without MFA gets enrolment forced — and no tokens anywhere. */
    @Test
    void adminWithoutMfaMustEnrollThroughTheChallengeBeforeAnyToken() throws Exception {
        AppUser admin = saveUser(Role.ADMIN);

        // Path 1 (no code → no tokens): the login answer carries only the
        // challenge; the challenge is not an access token.
        MvcResult challenged = login(admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.enrollmentRequired").value(true))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn();
        String challenge = body(challenged).get("challenge").asText();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + challenge))
                .andExpect(status().isUnauthorized());
        // The challenge cannot touch other PROFILE_SELF surfaces either.
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + challenge))
                .andExpect(status().isUnauthorized());

        // The challenge MAY drive enrolment (the whole point of forcing it).
        JsonNode enrollment = body(mvc.perform(post("/api/auth/mfa/enroll")
                        .header("Authorization", "Bearer " + challenge))
                .andExpect(status().isOk())
                .andReturn());
        mvc.perform(post("/api/auth/mfa/activate")
                        .header("Authorization", "Bearer " + challenge)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\""
                                + currentCode(enrollment.get("secret").asText()) + "\"}"))
                .andExpect(status().isNoContent());

        // Only now does the verify flow issue the session.
        MvcResult session = verify(challenge, currentCode(enrollment.get("secret").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
        String accessToken = body(session).get("token").asText();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // Once MFA is active the challenge may no longer reach enrolment.
        String newChallenge = body(login(admin).andExpect(status().isOk()).andReturn())
                .get("challenge").asText();
        mvc.perform(post("/api/auth/mfa/enroll")
                        .header("Authorization", "Bearer " + newChallenge))
                .andExpect(status().isUnauthorized());
    }

    /** B3: wrong code → 401, and guessing is rate-limited to 5/min. */
    @Test
    void verifyIsRateLimitedAfterFiveAttempts() throws Exception {
        String bearer = passwordLoginToken(user);
        enrollAndActivate(bearer);
        String challenge = body(login(user).andExpect(status().isOk()).andReturn())
                .get("challenge").asText();

        for (int attempt = 1; attempt <= 5; attempt++) {
            verify(challenge, "000000").andExpect(status().isUnauthorized());
        }
        verify(challenge, "000000").andExpect(status().isTooManyRequests());
    }

    @Test
    void statusAndDisableRoundTrip() throws Exception {
        String bearer = passwordLoginToken(user);
        mvc.perform(get("/api/auth/mfa").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(false))
                .andExpect(jsonPath("$.enabled").value(false));

        JsonNode enrollment = enrollAndActivate(bearer);
        mvc.perform(get("/api/auth/mfa").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        // Disable demands a fresh proof; a recovery code counts.
        mvc.perform(delete("/api/auth/mfa")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/auth/mfa")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\""
                                + enrollment.get("recoveryCodes").get(1).asText() + "\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/auth/mfa").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(false));
        // And the next login is single-phase again.
        login(user).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }
}
