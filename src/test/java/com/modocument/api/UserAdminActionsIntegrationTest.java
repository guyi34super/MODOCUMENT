package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.RecoveryCode;
import com.modocument.domain.entity.UserMfa;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.RecoveryCodeJpaRepository;
import com.modocument.infrastructure.persistence.RefreshTokenJpaRepository;
import com.modocument.infrastructure.persistence.UserMfaJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 (ws22) user actions over real HTTP semantics — MockMvc with the
 * production security chain, maker/checker and rate limiting:
 *
 * <ul>
 *   <li>unlock (direct) lifts a login lockout so the user signs in again;</li>
 *   <li>password reset is dual-controlled: 202 with the policy checked and
 *       only a hash stored; the maker cannot approve it; a second admin's
 *       approval kills every outstanding token, retires the old password
 *       and forces a password change on the next sign-in;</li>
 *   <li>MFA reset is dual-controlled and sends the user back to enrolment;</li>
 *   <li>display-name edit is direct and never touches the email;</li>
 *   <li>every action refuses the caller's own account (409), answers 404
 *       for another bank's user and 403 for a non-admin;</li>
 *   <li>the resets share the {@code admin-security} rate-limit bucket.</li>
 * </ul>
 */
@SpringBootTest(properties = "modocument.ratelimit.admin-security=5")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAdminActionsIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASSWORD = "Sup3r-secret-ws22!";
    private static final String TEMPORARY = "Temp-Rotor-ws22-Kx9";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private RefreshTokenJpaRepository refreshTokens;
    @Autowired
    private UserMfaJpaRepository userMfa;
    @Autowired
    private RecoveryCodeJpaRepository recoveryCodes;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtService jwtService;

    private Bank bankA;
    private Bank bankB;
    private AppUser admin;
    private AppUser secondAdmin;
    private AppUser member;
    private AppUser outsider;
    private String clientIp;
    private final List<Integer> createdUserIds = new ArrayList<>();

    @BeforeEach
    void seedTwoBanks() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        bankA = banks.save(new Bank("UAA" + tag.substring(0, 4).toUpperCase(), "Actions A"));
        bankB = banks.save(new Bank("UAB" + tag.substring(4).toUpperCase(), "Actions B"));
        admin = storeUser("ua-admin-" + tag, bankA.getBankId(), Role.ADMIN);
        secondAdmin = storeUser("ua-admin2-" + tag, bankA.getBankId(), Role.ADMIN);
        member = storeUser("ua-member-" + tag, bankA.getBankId(), Role.READ_WRITE);
        outsider = storeUser("ua-outsider-" + tag, bankB.getBankId(), Role.READ_WRITE);
        clientIp = "10.22." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM PendingChange WHERE BankId = ? OR BankId = ?",
                bankA.getBankId(), bankB.getBankId());
        for (Integer id : createdUserIds) {
            refreshTokens.findByUserId(id).forEach(refreshTokens::delete);
            recoveryCodes.deleteByUserId(id);
            userMfa.findById(id).ifPresent(userMfa::delete);
            users.deleteById(id);
        }
        createdUserIds.clear();
        banks.delete(bankA);
        banks.delete(bankB);
    }

    // ------------------------------------------------------------- unlock

    @Test
    void unlockLiftsALockoutSoTheUserSignsInAgain() throws Exception {
        AppUser locked = users.findById(member.getAppUserId()).orElseThrow();
        locked.setLockedUntil(LocalDateTime.now().plusMinutes(30));
        locked.setFailedLoginCount(3);
        locked.setLockoutCount(2);
        users.save(locked);
        // The lock answers the generic 401 even with the right password.
        loginStatus(member.getEmail(), PASSWORD, 401);

        // The listing tells the admin who is locked.
        mvc.perform(get("/api/admin/users").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + member.getAppUserId() + ")].locked")
                        .value(true));

        mvc.perform(post("/api/admin/users/" + member.getAppUserId() + "/unlock")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.lockedUntil").doesNotExist());

        AppUser after = users.findById(member.getAppUserId()).orElseThrow();
        assertThat(after.getLockedUntil()).isNull();
        assertThat(after.getFailedLoginCount()).isZero();
        assertThat(after.getLockoutCount()).isZero();
        loginStatus(member.getEmail(), PASSWORD, 200);
        assertThat(auditCount("USER_UNLOCKED", member)).isOne();
    }

    // ----------------------------------------------------- password reset

    @Test
    void passwordResetIsDualControlledAndKillsEverySessionOnApproval() throws Exception {
        String liveToken = sessionToken(member, PASSWORD);

        // A weak temporary password is refused at submit — the maker learns now.
        mvc.perform(post("/api/admin/users/" + member.getAppUserId() + "/password-reset")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"temporaryPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());

        MvcResult submitted = mvc.perform(
                        post("/api/admin/users/" + member.getAppUserId() + "/password-reset")
                                .header("Authorization", bearer(admin))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"temporaryPassword\":\"" + TEMPORARY + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.changeType").value("USER_PASSWORD_RESET"))
                .andExpect(jsonPath("$.payload.userId").value(member.getAppUserId()))
                .andExpect(jsonPath("$.payload.targetEmail").value(member.getEmail()))
                .andExpect(jsonPath("$.payload.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.payload.temporaryPassword").doesNotExist())
                .andReturn();
        int pendingId = MAPPER.readTree(submitted.getResponse().getContentAsString())
                .get("pendingChangeId").asInt();
        // The row holds the hash, never the plaintext.
        String storedPayload = jdbc.queryForObject(
                "SELECT PayloadJson FROM PendingChange WHERE PendingChangeId = ?",
                String.class, pendingId);
        assertThat(storedPayload).contains("passwordHash").doesNotContain(TEMPORARY);

        // Nothing happened yet: the session lives, the old password works.
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + liveToken))
                .andExpect(status().isOk());

        // The maker cannot approve their own reset (B7).
        mvc.perform(post("/api/admin/changes/" + pendingId + "/approve")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/admin/changes/" + pendingId + "/approve")
                        .header("Authorization", bearer(secondAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        // Every outstanding token died with the TokenVersion bump...
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + liveToken))
                .andExpect(status().isUnauthorized());
        assertThat(refreshTokens.findByUserId(member.getAppUserId()))
                .allSatisfy(token -> assertThat(token.getRevokedAt()).isNotNull());
        // ...the old password is retired...
        loginStatus(member.getEmail(), PASSWORD, 401);
        // ...and the temporary one works, but only for a password change.
        MvcResult temp = loginStatus(member.getEmail(), TEMPORARY, 200);
        JsonNode body = MAPPER.readTree(temp.getResponse().getContentAsString());
        assertThat(body.get("user").get("mustChangePassword").asBoolean()).isTrue();
        mvc.perform(get("/Templates/GetAll")
                        .header("Authorization", "Bearer " + body.get("token").asText()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        assertThat(auditCount("USER_PASSWORD_RESET", member)).isOne();
        String auditValues = jdbc.queryForObject("SELECT NewValues FROM AuditEvent "
                        + "WHERE Action = 'USER_PASSWORD_RESET' AND EntityId = ?",
                String.class, String.valueOf(member.getAppUserId()));
        assertThat(auditValues).doesNotContain(TEMPORARY).doesNotContain("argon2");
    }

    @Test
    void passwordResetOfAnIdpAccountIsRefused() throws Exception {
        AppUser sso = users.findById(member.getAppUserId()).orElseThrow();
        sso.setAuthProvider("entra");
        sso.setProviderSubject("sub-" + UUID.randomUUID());
        users.save(sso);

        mvc.perform(post("/api/admin/users/" + member.getAppUserId() + "/password-reset")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"temporaryPassword\":\"" + TEMPORARY + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("identity provider")));
    }

    // ---------------------------------------------------------- MFA reset

    @Test
    void mfaResetIsDualControlledAndSendsTheUserBackToEnrolment() throws Exception {
        AppUser enrolledAdmin = storeUser("ua-mfa-" + UUID.randomUUID().toString()
                .substring(0, 8), bankA.getBankId(), Role.ADMIN);
        enrolMfa(enrolledAdmin);
        String oldToken = bearer(enrolledAdmin);
        // Enrolled: the password alone buys a challenge, not an enrolment.
        loginStatus(enrolledAdmin.getEmail(), PASSWORD, 200,
                "$.enrollmentRequired", false);

        // A user without MFA has nothing to reset — the maker hears it now.
        mvc.perform(post("/api/admin/users/" + member.getAppUserId() + "/mfa-reset")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"lost phone\"}"))
                .andExpect(status().isConflict());
        // A reason is mandatory.
        mvc.perform(post("/api/admin/users/" + enrolledAdmin.getAppUserId() + "/mfa-reset")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());

        MvcResult submitted = mvc.perform(
                        post("/api/admin/users/" + enrolledAdmin.getAppUserId() + "/mfa-reset")
                                .header("Authorization", bearer(admin))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Lost phone, ticket HD-1234\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.changeType").value("USER_MFA_RESET"))
                .andExpect(jsonPath("$.payload.reason").value("Lost phone, ticket HD-1234"))
                .andReturn();
        int pendingId = MAPPER.readTree(submitted.getResponse().getContentAsString())
                .get("pendingChangeId").asInt();
        assertThat(userMfa.findById(enrolledAdmin.getAppUserId())).isPresent();

        mvc.perform(post("/api/admin/changes/" + pendingId + "/approve")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/changes/" + pendingId + "/approve")
                        .header("Authorization", bearer(secondAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        assertThat(userMfa.findById(enrolledAdmin.getAppUserId())).isEmpty();
        assertThat(recoveryCodes.findByUserIdAndUsedAtIsNull(enrolledAdmin.getAppUserId()))
                .isEmpty();
        mvc.perform(get("/api/auth/me").header("Authorization", oldToken))
                .andExpect(status().isUnauthorized());
        // The admin must re-enrol before any session is issued (require-for-admin).
        loginStatus(enrolledAdmin.getEmail(), PASSWORD, 200,
                "$.enrollmentRequired", true);
        assertThat(auditCount("USER_MFA_RESET", enrolledAdmin)).isOne();
    }

    // ----------------------------------------------------- display name

    @Test
    void displayNameEditIsDirectAndNeverTouchesTheEmail() throws Exception {
        mvc.perform(patch("/api/admin/users/" + member.getAppUserId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"  Renamed Member \","
                                + "\"email\":\"hijack@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Renamed Member"))
                .andExpect(jsonPath("$.email").value(member.getEmail()));

        AppUser after = users.findById(member.getAppUserId()).orElseThrow();
        assertThat(after.getDisplayName()).isEqualTo("Renamed Member");
        assertThat(after.getEmail()).isEqualTo(member.getEmail());
        assertThat(auditCount("USER_UPDATED", member)).isOne();

        mvc.perform(patch("/api/admin/users/" + member.getAppUserId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------- refusals (409/404/403)

    @Test
    void everyActionRefusesTheCallersOwnAccount() throws Exception {
        int self = admin.getAppUserId();
        for (MockHttpServletRequestBuilder request : actionsOn(self)) {
            mvc.perform(request.header("Authorization", bearer(admin)))
                    .andExpect(status().isConflict());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM PendingChange WHERE BankId = ?",
                Integer.class, bankA.getBankId())).isZero();
    }

    @Test
    void anotherBanksUserLooksLikeAMissingId() throws Exception {
        for (MockHttpServletRequestBuilder request : actionsOn(outsider.getAppUserId())) {
            mvc.perform(request.header("Authorization", bearer(admin)))
                    .andExpect(status().isNotFound());
        }
        AppUser untouched = users.findById(outsider.getAppUserId()).orElseThrow();
        assertThat(untouched.getDisplayName()).isEqualTo(outsider.getDisplayName());
    }

    @Test
    void nonAdminsAreForbidden() throws Exception {
        AppUser writer = storeUser("ua-writer-" + UUID.randomUUID().toString().substring(0, 8),
                bankA.getBankId(), Role.READ_WRITE);
        for (MockHttpServletRequestBuilder request : actionsOn(member.getAppUserId())) {
            mvc.perform(request.header("Authorization", bearer(writer)))
                    .andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------- rate limit

    @Test
    void credentialResetsShareTheAdminSecurityBucket() throws Exception {
        // Five permits in this context (the default is 10 per window); the
        // attempts are metered before the handler runs, so cheap 404s count.
        String caller = bearer(admin);
        int foreign = outsider.getAppUserId();
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/admin/users/" + foreign + "/password-reset")
                            .header("Authorization", caller)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"temporaryPassword\":\"" + TEMPORARY + "\"}"))
                    .andExpect(status().isNotFound());
        }
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/admin/users/" + foreign + "/mfa-reset")
                            .header("Authorization", caller)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"x\"}"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/admin/users/" + foreign + "/mfa-reset")
                        .header("Authorization", caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        // Unlock and edit are direct, cheap and not in the bucket.
        mvc.perform(post("/api/admin/users/" + member.getAppUserId() + "/unlock")
                        .header("Authorization", caller))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------ helpers

    private List<MockHttpServletRequestBuilder> actionsOn(int userId) {
        return List.of(
                post("/api/admin/users/" + userId + "/unlock"),
                post("/api/admin/users/" + userId + "/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"temporaryPassword\":\"" + TEMPORARY + "\"}"),
                post("/api/admin/users/" + userId + "/mfa-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"lost phone\"}"),
                patch("/api/admin/users/" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Someone Else\"}"));
    }

    private AppUser storeUser(String emailPrefix, int bankId, Role role) {
        AppUser entity = new AppUser();
        entity.setEmail(emailPrefix + "@example.com");
        entity.setPasswordHash(passwordEncoder.encode(PASSWORD));
        entity.setDisplayName(emailPrefix);
        entity.setBankId(bankId);
        entity.setRole(role);
        entity.setSettingsJson("{}");
        entity.setCreatedDate(LocalDateTime.now());
        AppUser saved = users.save(entity);
        createdUserIds.add(saved.getAppUserId());
        return saved;
    }

    /** An activated enrolment plus two recovery codes (the secret is never used here). */
    private void enrolMfa(AppUser user) {
        UserMfa row = new UserMfa();
        row.setUserId(user.getAppUserId());
        row.setSecretCiphertext("test-ciphertext-not-used");
        row.setEnabledAt(Instant.now());
        row.setCreatedAt(Instant.now());
        userMfa.save(row);
        recoveryCodes.save(new RecoveryCode(user.getAppUserId(), "{noop}AAAAABBBBB"));
        recoveryCodes.save(new RecoveryCode(user.getAppUserId(), "{noop}CCCCCDDDDD"));
    }

    private String bearer(AppUser user) {
        AppUser fresh = users.findById(user.getAppUserId()).orElseThrow();
        return "Bearer " + jwtService.issueToken(fresh);
    }

    private String sessionToken(AppUser user, String password) throws Exception {
        MvcResult result = loginStatus(user.getEmail(), password, 200);
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private MvcResult loginStatus(String email, String password, int expected) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password
                                + "\"}"))
                .andExpect(status().is(expected))
                .andReturn();
    }

    private void loginStatus(String email, String password, int expected, String jsonPath,
                             Object value) throws Exception {
        mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password
                                + "\"}"))
                .andExpect(status().is(expected))
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath(jsonPath).value(value));
    }

    private Integer auditCount(String action, AppUser target) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = ? AND EntityId = ?",
                Integer.class, action, String.valueOf(target.getAppUserId()));
    }
}
