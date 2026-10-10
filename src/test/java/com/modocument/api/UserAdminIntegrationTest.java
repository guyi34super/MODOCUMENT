package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.RefreshTokenJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import jakarta.servlet.http.Cookie;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws09 gates over real HTTP semantics (MockMvc + the real security chain),
 * now with the ws10 dual-control flow in front of every critical mutation:
 *
 * <ul>
 *   <li>B1 — users are admin-managed and individually identified: the
 *       listing carries every account of the Admin's bank with role, state
 *       and last login; creation issues a temporary password that must be
 *       changed before anything else works.</li>
 *   <li>B7/SEC-023 — creation, role changes and the disable switch answer
 *       202 with a PENDING change; nothing happens to the target until a
 *       SECOND admin approves (MakerCheckerIntegrationTest proves the
 *       self-approval block itself).</li>
 *   <li>B10 — the CSV export contains the caller's own bank only, with
 *       last-login dates, and neutralizes spreadsheet formulas.</li>
 *   <li>PRIV-017 — the leaver test: submit → approve by a second admin →
 *       the existing access token, the refresh cookie and a fresh login all
 *       fail on the very next request, the login with the same generic 401
 *       as any bad credential; the USER_DISABLED audit row is the
 *       evidence.</li>
 *   <li>Mover (B5 mechanism) — an APPROVED role change kills every
 *       outstanding token of the target immediately.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAdminIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASSWORD = "Sup3r-secret-ws09!";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private RefreshTokenJpaRepository refreshTokens;
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
        bankA = banks.save(new Bank("WSA" + tag.substring(0, 4).toUpperCase(), "Bank A"));
        bankB = banks.save(new Bank("WSB" + tag.substring(4).toUpperCase(), "Bank B"));
        admin = storeUser("admin-" + tag, bankA.getBankId(), Role.ADMIN);
        // ws10: the checker — a maker's change needs a DIFFERENT admin.
        secondAdmin = storeUser("admin2-" + tag, bankA.getBankId(), Role.ADMIN);
        member = storeUser("member-" + tag, bankA.getBankId(), Role.READ_WRITE);
        outsider = storeUser("outsider-" + tag, bankB.getBankId(), Role.READ_WRITE);
        // Distinct per run, so the shared per-IP login throttle never trips.
        clientIp = "10.9." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
    }

    @AfterEach
    void cleanUp() {
        // Pending changes reference users and banks by FK — scrub them first.
        jdbc.update("DELETE FROM PendingChange WHERE BankId = ? OR BankId = ?",
                bankA.getBankId(), bankB.getBankId());
        for (Integer id : createdUserIds) {
            refreshTokens.findByUserId(id).forEach(refreshTokens::delete);
            users.deleteById(id);
        }
        createdUserIds.clear();
        banks.delete(bankA);
        banks.delete(bankB);
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

    private MvcResult login(String email, String password, int expectedStatus) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\""
                                + password + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private String token(String email) throws Exception {
        MvcResult result = login(email, PASSWORD, 200);
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    /**
     * ADMIN logins owe a second factor (require-for-admin, ws06), which is
     * MfaFlowIntegrationTest's subject — here the admin's token is minted
     * directly, exactly like PermissionEnforcementTest does.
     */
    private String adminToken() {
        return jwtService.issueToken(admin);
    }

    /** The ws10 checker: a different admin of the same bank. */
    private String secondAdminToken() {
        return jwtService.issueToken(secondAdmin);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** Approves a pending change as the second admin and returns the decided row. */
    private JsonNode approveAsSecondAdmin(int pendingChangeId) throws Exception {
        MvcResult decided = mvc.perform(post("/api/admin/changes/" + pendingChangeId + "/approve")
                        .header("Authorization", bearer(secondAdminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"reviewed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andReturn();
        return MAPPER.readTree(decided.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------- B1: list

    @Test
    void listIsScopedToTheCallersBankAndIdentifiesEveryUser() throws Exception {
        // A real login so the listing has a live last-login date to show.
        login(member.getEmail(), PASSWORD, 200);
        String adminToken = adminToken();

        MvcResult result = mvc.perform(get("/api/admin/users")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode rows = MAPPER.readTree(result.getResponse().getContentAsString());
        List<String> emails = new ArrayList<>();
        rows.forEach(row -> emails.add(row.get("email").asText()));
        assertThat(emails).contains(admin.getEmail(), secondAdmin.getEmail(), member.getEmail())
                .doesNotContain(outsider.getEmail());

        JsonNode memberRow = null;
        for (JsonNode row : rows) {
            if (row.get("email").asText().equals(member.getEmail())) {
                memberRow = row;
            }
        }
        assertThat(memberRow).isNotNull();
        assertThat(memberRow.get("role").asText()).isEqualTo("READ_WRITE");
        assertThat(memberRow.get("disabled").asBoolean()).isFalse();
        assertThat(memberRow.get("mfaEnabled").asBoolean()).isFalse();
        // The member just logged in: B10's last-login data is live.
        assertThat(memberRow.get("lastLoginAt").isNull()).isFalse();
        assertThat(memberRow.get("authProvider").isNull()).isTrue();
    }

    @Test
    void nonAdminsGet403OnEveryUserAdminRoute() throws Exception {
        String memberToken = token(member.getEmail());

        mvc.perform(get("/api/admin/users").header("Authorization", bearer(memberToken)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users").header("Authorization", bearer(memberToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@example.com\",\"role\":\"READ_ONLY\","
                                + "\"temporaryPassword\":\"Temp-Rotor-91x!\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users/export").header("Authorization", bearer(memberToken)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users/access-review")
                        .header("Authorization", bearer(memberToken)))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------- joiner + forced password change

    @Test
    void joinerIsCreatedInTheAdminsBankAfterApprovalAndMustChangeThePasswordFirst()
            throws Exception {
        String adminToken = adminToken();
        String joinerEmail = "joiner-" + UUID.randomUUID() + "@example.com";

        // The temporary password rides the full password policy — refused at
        // SUBMIT time, so the maker learns immediately, not the checker.
        mvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + joinerEmail + "\",\"displayName\":\"Joiner\","
                                + "\"role\":\"READ_WRITE\",\"temporaryPassword\":\"weak\"}"))
                .andExpect(status().isBadRequest());

        // ws10: creation is maker/checker-gated — 202, nothing created yet,
        // and the pending payload carries NO password material (only a hash
        // is stored, and even that never leaves the backend).
        MvcResult submitted = mvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + joinerEmail + "\",\"displayName\":\"Joiner\","
                                + "\"role\":\"READ_WRITE\",\"temporaryPassword\":\""
                                + PASSWORD + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.changeType").value("USER_CREATE"))
                .andReturn();
        String submittedBody = submitted.getResponse().getContentAsString();
        assertThat(submittedBody).doesNotContain(PASSWORD).doesNotContain("passwordHash");
        int pendingId = MAPPER.readTree(submittedBody).get("pendingChangeId").asInt();
        assertThat(users.findAll()).noneMatch(u -> u.getEmail().equals(joinerEmail));

        // The second admin approves; only now does the account exist.
        JsonNode applied = approveAsSecondAdmin(pendingId);
        int joinerId = Integer.parseInt(applied.get("targetEntityId").asText());
        createdUserIds.add(joinerId);

        AppUser stored = users.findById(joinerId).orElseThrow();
        assertThat(stored.getBankId()).isEqualTo(bankA.getBankId());
        assertThat(stored.isMustChangePassword()).isTrue();

        // First login works, but the flag travels in the response...
        MvcResult firstLogin = login(joinerEmail, PASSWORD, 200);
        JsonNode body = MAPPER.readTree(firstLogin.getResponse().getContentAsString());
        assertThat(body.get("user").get("mustChangePassword").asBoolean()).isTrue();
        String tempToken = body.get("token").asText();

        // ...and every route outside self-service answers 403 with the code.
        mvc.perform(get("/Templates/GetAll").header("Authorization", bearer(tempToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        // Self-service stays open: the joiner can see who they are...
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(tempToken)))
                .andExpect(status().isOk());
        // ...and change the temporary password.
        mvc.perform(put("/api/auth/password")
                        .header("Authorization", bearer(tempToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD
                                + "\",\"newPassword\":\"Fresh-own-secret-42!\"}"))
                .andExpect(status().isNoContent());

        // The change cleared the flag and ended the session; the new
        // password opens an unrestricted one.
        MvcResult secondLogin = login(joinerEmail, "Fresh-own-secret-42!", 200);
        JsonNode second = MAPPER.readTree(secondLogin.getResponse().getContentAsString());
        assertThat(second.get("user").get("mustChangePassword").asBoolean()).isFalse();
        mvc.perform(get("/Templates/GetAll")
                        .header("Authorization", bearer(second.get("token").asText())))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------- mover (B5)

    @Test
    void approvedRoleChangeKillsTheTargetsOutstandingTokensImmediately() throws Exception {
        String memberToken = token(member.getEmail());
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(memberToken)))
                .andExpect(status().isOk());

        // Submit: 202, pending — the target's role and sessions are untouched.
        String adminToken = adminToken();
        MvcResult submitted = mvc.perform(put("/api/admin/users/" + member.getAppUserId() + "/role")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"READ_ONLY\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andReturn();
        int pendingId = MAPPER.readTree(submitted.getResponse().getContentAsString())
                .get("pendingChangeId").asInt();
        assertThat(users.findById(member.getAppUserId()).orElseThrow().getRole())
                .isEqualTo(Role.READ_WRITE);
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(memberToken)))
                .andExpect(status().isOk());

        // Approval by the SECOND admin applies the change.
        approveAsSecondAdmin(pendingId);
        assertThat(users.findById(member.getAppUserId()).orElseThrow().getRole())
                .isEqualTo(Role.READ_ONLY);

        // The token minted before the change is dead on its next request.
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(memberToken)))
                .andExpect(status().isUnauthorized());
        // A fresh login carries the new role.
        MvcResult relogin = login(member.getEmail(), PASSWORD, 200);
        assertThat(MAPPER.readTree(relogin.getResponse().getContentAsString())
                .get("user").get("role").asText()).isEqualTo("READ_ONLY");
    }

    @Test
    void roleAndDisableRefuseCrossBankTargetsLikeMissingIds() throws Exception {
        String adminToken = adminToken();

        // Refused at SUBMIT time: no pending row for a foreign-bank target.
        mvc.perform(put("/api/admin/users/" + outsider.getAppUserId() + "/role")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"READ_ONLY\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/admin/users/" + outsider.getAppUserId() + "/disabled")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isNotFound());
        assertThat(users.findById(outsider.getAppUserId()).orElseThrow().isDisabled()).isFalse();
        Integer pendingRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM PendingChange WHERE BankId = ?", Integer.class,
                bankA.getBankId());
        assertThat(pendingRows).isZero();
    }

    @Test
    void adminsCannotChangeTheirOwnRoleOrDisableThemselves() throws Exception {
        String adminToken = adminToken();

        mvc.perform(put("/api/admin/users/" + admin.getAppUserId() + "/role")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"READ_ONLY\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/admin/users/" + admin.getAppUserId() + "/disabled")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isConflict());
    }

    // ---------------------------------------------------- leaver (PRIV-017)

    @Test
    void disabledLeaverIsInertImmediatelyAfterApprovalAndTheEvidenceIsAudited()
            throws Exception {
        // The leaver has a live session: access token + refresh cookie.
        MvcResult session = login(member.getEmail(), PASSWORD, 200);
        String leaverToken = MAPPER.readTree(session.getResponse().getContentAsString())
                .get("token").asText();
        Cookie refreshCookie = session.getResponse().getCookie("modoc_refresh");
        assertThat(refreshCookie).isNotNull();
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(leaverToken)))
                .andExpect(status().isOk());

        // The leaver switch is maker/checker-gated: 202, still fully active.
        String adminToken = adminToken();
        MvcResult submitted = mvc.perform(
                        put("/api/admin/users/" + member.getAppUserId() + "/disabled")
                                .header("Authorization", bearer(adminToken))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"disabled\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andReturn();
        int pendingId = MAPPER.readTree(submitted.getResponse().getContentAsString())
                .get("pendingChangeId").asInt();
        assertThat(users.findById(member.getAppUserId()).orElseThrow().isDisabled()).isFalse();
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(leaverToken)))
                .andExpect(status().isOk());

        // A SECOND admin approves — the account is inert the same moment.
        approveAsSecondAdmin(pendingId);

        // 1. The existing access token fails on its very next request.
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(leaverToken)))
                .andExpect(status().isUnauthorized());
        // 2. The refresh cookie cannot resurrect the session.
        mvc.perform(post("/api/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isUnauthorized());
        // 3. A fresh login fails with the SAME generic 401 as bad credentials.
        MvcResult refused = login(member.getEmail(), PASSWORD, 401);
        assertThat(MAPPER.readTree(refused.getResponse().getContentAsString())
                .get("error").asText()).isEqualTo("Invalid email or password.");

        // The JML evidence: the audited leaver event.
        Integer audited = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = 'USER_DISABLED' "
                        + "AND EntityId = ?", Integer.class,
                String.valueOf(member.getAppUserId()));
        assertThat(audited).isNotNull().isPositive();

        // Re-enabling stays DIRECT (ws10: restoring access is the lower-risk
        // direction) and lets the person sign in afresh.
        mvc.perform(put("/api/admin/users/" + member.getAppUserId() + "/disabled")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabled").value(false));
        login(member.getEmail(), PASSWORD, 200);
    }

    // ------------------------------------------------------------ B10: export

    @Test
    void exportIsBankScopedCsvWithFormulaNeutralizationAndLastLogins() throws Exception {
        AppUser hostile = storeUser("hostile-" + UUID.randomUUID(), bankA.getBankId(),
                Role.READ_ONLY);
        hostile.setDisplayName("=HYPERLINK(\"http://evil.example\",\"x\")");
        // withNano(0): survives the H2 timestamp round-trip verbatim.
        hostile.setLastLoginAt(LocalDateTime.now().minusDays(3).withNano(0));
        users.save(hostile);

        String adminToken = adminToken();
        MvcResult result = mvc.perform(get("/api/admin/users/export")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("users-" + bankA.getCode() + "-")
                .contains(".csv");
        String csv = result.getResponse().getContentAsString();
        assertThat(csv).startsWith(
                "Email,DisplayName,Role,Disabled,LastLoginAt,MfaEnabled,AuthProvider,CreatedDate");
        assertThat(csv).contains(hostile.getEmail()).contains(member.getEmail())
                .doesNotContain(outsider.getEmail());
        // CSV injection neutralized exactly like the data exports.
        assertThat(csv).contains("'=HYPERLINK").doesNotContain(",\"=HYPERLINK");
        // B10: the export carries last-login dates.
        assertThat(csv).contains(hostile.getLastLoginAt().toString());

        Integer audited = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = 'USER_LIST_EXPORTED' "
                        + "AND BankId = ?", Integer.class, bankA.getBankId());
        assertThat(audited).isNotNull().isPositive();
    }

    // ----------------------------------------------- SEC-013: access review

    @Test
    void accessReviewFlagsNinetyDayInactiveAccounts() throws Exception {
        member.setLastLoginAt(LocalDateTime.now().minusDays(120));
        users.save(member);

        String adminToken = adminToken();
        MvcResult result = mvc.perform(get("/api/admin/users/access-review")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode rows = MAPPER.readTree(result.getResponse().getContentAsString());
        JsonNode memberRow = null;
        JsonNode adminRow = null;
        for (JsonNode row : rows) {
            if (row.get("email").asText().equals(member.getEmail())) {
                memberRow = row;
            }
            if (row.get("email").asText().equals(admin.getEmail())) {
                adminRow = row;
            }
        }
        assertThat(memberRow).isNotNull();
        assertThat(memberRow.get("inactive90d").asBoolean()).isTrue();
        assertThat(memberRow.get("daysSinceLastLogin").asLong()).isGreaterThanOrEqualTo(119);
        assertThat(memberRow.get("role").asText()).isEqualTo("READ_WRITE");
        // A freshly created account (no login yet) is not flagged.
        assertThat(adminRow).isNotNull();
        assertThat(adminRow.get("inactive90d").asBoolean()).isFalse();
        assertThat(adminRow.get("daysSinceLastLogin").isNull()).isTrue();
        // No cross-bank rows on the worksheet either.
        rows.forEach(row ->
                assertThat(row.get("email").asText()).isNotEqualTo(outsider.getEmail()));
    }
}
