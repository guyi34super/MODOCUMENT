package com.modocument.pgit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.JwtService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ws21 bank lifecycle on the REAL schema with row level security
 * enforced (the app runs as {@code modocument_app}): the platform admins'
 * requests and approvals are bound to the PLATFORM bank, under whose RLS no
 * other bank's row is even visible — yet onboarding must create a bank, its
 * users and audit rows stamped with the NEW bank, suspension must update
 * another bank's Bank row and revoke its users' sessions, and the
 * break-glass admin must land in the target bank. The H2 suite cannot see
 * any of that (no RLS there).
 *
 * <p>Checked as the schema owner (past RLS): every row carries the right
 * BankId; and as the app role bound to the platform tenant: the new bank's
 * audit rows stay invisible to the platform owner.
 */
class BankLifecyclePgIT extends PgSpringItSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PLATFORM_CODE = "MOI-PLATFORM";
    private static final int ADMIN_A = 921_001;
    private static final int ADMIN_B = 921_002;
    private static final String TEMP_ADMIN = "PgIt-Onboard-Kestrel-58!";
    private static final String TEMP_APPROVER = "PgIt-Onboard-Plover-73!";

    private static int platformBankId;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;

    @BeforeAll
    static void seedPlatformOwner() throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement()) {
            // Any committed PLATFORM bank wins the unique partial index; the
            // directory resolves the platform bank by the default code.
            statement.execute("INSERT INTO Bank (Code, Name, Kind) VALUES ('" + PLATFORM_CODE
                    + "', 'MO Intelligence Platform', 'PLATFORM') ON CONFLICT DO NOTHING");
            try (ResultSet rs = statement.executeQuery("SELECT BankId FROM Bank WHERE Code = '"
                    + PLATFORM_CODE + "' AND Kind = 'PLATFORM'")) {
                assertThat(rs.next()).as("platform bank %s", PLATFORM_CODE).isTrue();
                platformBankId = rs.getInt(1);
            }
            statement.execute("INSERT INTO AppUsers (AppUserId, Email, PasswordHash, DisplayName, "
                    + "SettingsJson, CreatedDate, UpdatedDate, BankId, Role, TokenVersion, "
                    + "Disabled, MustChangePassword) VALUES "
                    + "(" + ADMIN_A + ", 'pgit-plat-a@moi.example', '{noop}x', 'Ops A', '{}', "
                    + "now(), now(), " + platformBankId + ", 'PLATFORM_ADMIN', 0, false, false), "
                    + "(" + ADMIN_B + ", 'pgit-plat-b@moi.example', '{noop}x', 'Ops B', '{}', "
                    + "now(), now(), " + platformBankId + ", 'PLATFORM_ADMIN', 0, false, false) "
                    + "ON CONFLICT DO NOTHING");
        }
    }

    @Test
    void onboardSuspendReactivateAndBreakGlassLandInTheRightBankUnderRls() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        String code = "PG" + tag;
        String adminEmail = "pg-admin-" + tag.toLowerCase(Locale.ROOT) + "@newbank.example";
        String approverEmail = "pg-approver-" + tag.toLowerCase(Locale.ROOT) + "@newbank.example";
        String clientIp = "10.210." + (int) (Math.random() * 250) + "."
                + (int) (Math.random() * 250);

        // ---- onboarding: platform admin A submits, B approves -----------------
        JsonNode change = postAs(ADMIN_A, "/api/platform/banks", "{\"code\":\"" + code + "\","
                + "\"name\":\"PgIt Bank " + tag + "\",\"users\":["
                + "{\"email\":\"" + adminEmail + "\",\"role\":\"ADMIN\","
                + "\"temporaryPassword\":\"" + TEMP_ADMIN + "\"},"
                + "{\"email\":\"" + approverEmail + "\",\"role\":\"APPROVER\","
                + "\"temporaryPassword\":\"" + TEMP_APPROVER + "\"}]}", 202);
        int pendingId = change.get("pendingChangeId").asInt();
        postAs(ADMIN_A, "/api/admin/changes/" + pendingId + "/approve", "", 409);
        JsonNode applied = postAs(ADMIN_B, "/api/admin/changes/" + pendingId + "/approve", "", 200);
        assertThat(applied.get("status").asText()).isEqualTo("APPLIED");

        int bankId;
        try (Connection owner = ownerDataSource().getConnection()) {
            bankId = queryInt(owner, "SELECT BankId FROM Bank WHERE Code = ? AND Kind = 'CUSTOMER' "
                    + "AND Status = 'ACTIVE'", code);
            assertThat(applied.get("targetEntityId").asText()).isEqualTo(String.valueOf(bankId));
            assertThat(queryInt(owner, "SELECT count(*) FROM AppUsers WHERE BankId = ? "
                    + "AND MustChangePassword AND Role IN ('ADMIN', 'APPROVER')", bankId))
                    .isEqualTo(2);
            // The audit rows of the onboarding sit in the bank they are about...
            assertThat(auditCount(owner, "BANK_ONBOARDED", bankId, bankId)).isEqualTo(1);
            assertThat(queryInt(owner, "SELECT count(*) FROM AuditEvent WHERE Action = "
                    + "'USER_CREATED' AND BankId = ?", bankId)).isEqualTo(2);
            // ... and in the platform bank, next to the maker/checker trail.
            assertThat(auditCount(owner, "BANK_ONBOARDED", platformBankId, bankId)).isEqualTo(1);
            assertThat(queryInt(owner, "SELECT count(*) FROM AuditEvent WHERE BankId = ? "
                    + "AND Action IN ('CHANGE_REQUESTED', 'CHANGE_APPROVED') AND EntityId = ?",
                    platformBankId, String.valueOf(pendingId))).isEqualTo(2);
            assertThat(queryInt(owner, "SELECT count(*) FROM AuditEvent WHERE BankId = ? "
                    + "AND Action = 'USER_CREATED'", platformBankId)).isZero();
        }
        // Under RLS the platform tenant cannot read the new bank's audit trail.
        try (Connection app = appDataSource().getConnection()) {
            app.setAutoCommit(false);
            setTenant(app, String.valueOf(platformBankId));
            assertThat(queryInt(app, "SELECT count(*) FROM AuditEvent WHERE BankId = ?", bankId))
                    .isZero();
            assertThat(queryInt(app, "SELECT count(*) FROM Bank WHERE BankId = ?", bankId))
                    .isZero();
            app.rollback();
        }

        // The overview counts the new bank (counts only).
        JsonNode overview = getAs(ADMIN_A, "/api/platform/overview");
        JsonNode summary = null;
        for (JsonNode bank : overview.get("banks")) {
            if (bank.get("bankId").asInt() == bankId) {
                summary = bank;
            }
        }
        assertThat(summary).isNotNull();
        assertThat(summary.get("users").asLong()).isEqualTo(2);
        assertThat(overview.toString()).doesNotContain("@");

        // ---- suspension ---------------------------------------------------------
        MvcResult session = login(approverEmail, TEMP_APPROVER, clientIp, 200);
        String token = MAPPER.readTree(session.getResponse().getContentAsString())
                .get("token").asText();
        Cookie refresh = session.getResponse().getCookie("modoc_refresh");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        JsonNode suspend = postAs(ADMIN_A, "/api/platform/banks/" + bankId + "/suspend",
                "{\"reason\":\"PgIt suspension\"}", 202);
        postAs(ADMIN_B, "/api/admin/changes/" + suspend.get("pendingChangeId").asInt()
                + "/approve", "", 200);

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(refresh))
                .andExpect(status().isUnauthorized());
        login(approverEmail, TEMP_APPROVER, clientIp, 401);
        try (Connection owner = ownerDataSource().getConnection()) {
            assertThat(queryInt(owner, "SELECT count(*) FROM Bank WHERE BankId = ? "
                    + "AND Status = 'SUSPENDED' AND SuspendedAt IS NOT NULL", bankId)).isEqualTo(1);
            assertThat(queryInt(owner, "SELECT count(*) FROM AppUsers WHERE BankId = ? "
                    + "AND TokenVersion = 1", bankId)).isEqualTo(2);
            assertThat(queryInt(owner, "SELECT count(*) FROM RefreshToken r JOIN AppUsers u "
                    + "ON u.AppUserId = r.UserId WHERE u.BankId = ? AND r.RevokedAt IS NULL",
                    bankId)).isZero();
            assertThat(auditCount(owner, "BANK_SUSPENDED", bankId, bankId)).isEqualTo(1);
            assertThat(auditCount(owner, "BANK_SUSPENDED", platformBankId, bankId)).isEqualTo(1);
            assertThat(queryInt(owner, "SELECT count(*) FROM AuditEvent WHERE BankId = ? "
                    + "AND Action = 'LOGIN_FAILED' AND NewValues->>'reason' = 'bank_suspended'",
                    bankId)).isEqualTo(1);
            assertThat(queryInt(owner, "SELECT count(*) FROM AuditEvent WHERE BankId = ? "
                    + "AND Action = 'TOKEN_REFRESH_REUSE_DETECTED'", bankId)).isZero();
        }

        // ---- reactivation (direct) ------------------------------------------------
        JsonNode reactivated = postAs(ADMIN_A, "/api/platform/banks/" + bankId + "/reactivate",
                "", 200);
        assertThat(reactivated.get("status").asText()).isEqualTo("ACTIVE");
        login(approverEmail, TEMP_APPROVER, clientIp, 200);

        // ---- break-glass admin ----------------------------------------------------
        String rescueEmail = "pg-rescue-" + tag.toLowerCase(Locale.ROOT) + "@newbank.example";
        JsonNode add = postAs(ADMIN_A, "/api/platform/banks/" + bankId + "/admins",
                "{\"email\":\"" + rescueEmail + "\",\"temporaryPassword\":\"" + TEMP_ADMIN
                        + "\",\"reason\":\"PgIt break-glass\"}", 202);
        postAs(ADMIN_B, "/api/admin/changes/" + add.get("pendingChangeId").asInt() + "/approve",
                "", 200);
        try (Connection owner = ownerDataSource().getConnection()) {
            assertThat(queryInt(owner, "SELECT count(*) FROM AppUsers WHERE Email = ? "
                    + "AND BankId = ? AND Role = 'ADMIN' AND MustChangePassword", rescueEmail,
                    bankId)).isEqualTo(1);
            assertThat(auditCount(owner, "BANK_ADMIN_ADDED", bankId, bankId)).isEqualTo(1);
            assertThat(auditCount(owner, "BANK_ADMIN_ADDED", platformBankId, bankId))
                    .isEqualTo(1);
            assertThat(auditCount(owner, "BANK_REACTIVATED", bankId, bankId)).isEqualTo(1);
            assertThat(auditCount(owner, "BANK_REACTIVATED", platformBankId, bankId))
                    .isEqualTo(1);
        }
    }

    // ------------------------------------------------------------ helpers

    private String bearer(int userId) {
        AppUser user = new AppUser();
        user.setAppUserId(userId);
        user.setBankId(platformBankId);
        user.setRole(Role.PLATFORM_ADMIN);
        user.setTokenVersion(0);
        return "Bearer " + jwtService.issueToken(user);
    }

    private JsonNode postAs(int userId, String path, String json, int expectedStatus)
            throws Exception {
        var request = post(path).header("Authorization", bearer(userId));
        if (!json.isEmpty()) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        String body = mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
    }

    private JsonNode getAs(int userId, String path) throws Exception {
        return MAPPER.readTree(mvc.perform(get(path).header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private MvcResult login(String email, String password, String clientIp, int expectedStatus)
            throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private static int auditCount(Connection connection, String action, int auditBank,
                                  int aboutBank) throws SQLException {
        return queryInt(connection, "SELECT count(*) FROM AuditEvent WHERE Action = ? "
                + "AND BankId = ? AND EntityType = 'Bank' AND EntityId = ?", action, auditBank,
                String.valueOf(aboutBank));
    }

    private static int queryInt(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as(sql).isTrue();
                return rs.getInt(1);
            }
        }
    }

    private static void setTenant(Connection connection, String tenant) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.tenant', ?, true)")) {
            statement.setString(1, tenant);
            statement.executeQuery().close();
        }
    }
}
