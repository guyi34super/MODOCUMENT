package com.modocument.pgit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * QA suite (phase 2, Wave 2): the ws22 user-admin actions on the REAL
 * PostgreSQL schema with the app on {@code modocument_app} (RLS enforced).
 *
 * <p>Why a PgIT: AppUsers is not under RLS, but every action writes rows
 * that are — the {@code USER_*} audit events and, for the dual-controlled
 * resets, the PendingChange and its decision. BUG-01 (phase 2 test plan §8)
 * was exactly this class of defect: an audit row flushed at commit outside
 * the tenant binding passes on H2 and answers 502 under RLS. The H2 suite
 * ({@code UserAdminActionsIntegrationTest}) cannot see it.
 *
 * <p>Each test owns its target user, so the order does not matter. Row
 * checks read through the owner connection (RLS bypassed) and assert the
 * rows landed in the right bank.
 */
class UserAdminActionsRlsPgIT extends PgSpringItSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BANK_A = 560;
    private static final int BANK_B = 660;
    private static final int ADMIN_A = 9560;
    private static final int ADMIN2_A = 9561;
    private static final int LOCKED_A = 9562;
    private static final int RESET_A = 9563;
    private static final int MFA_A = 9564;
    private static final int RENAME_A = 9565;
    private static final int ADMIN_B = 9660;
    private static final String TEMPORARY = "Qa-Rls-Temporary-Passphrase-2026!";

    private static boolean seeded;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;

    @BeforeAll
    static void seedAsOwner() throws Exception {
        if (seeded) {
            return;
        }
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement()) {
            for (String sql : SEED) {
                statement.execute(sql);
            }
        }
        seeded = true;
    }

    @Test
    void unlockWorksUnderRlsAndAuditsInTheTargetsBank() throws Exception {
        int status = perform(post("/api/admin/users/" + LOCKED_A + "/unlock"), ADMIN_A, BANK_A,
                Role.ADMIN).getResponse().getStatus();

        assertThat(status).isEqualTo(200);
        assertThat(ownerLong("SELECT count(*) FROM AppUsers WHERE AppUserId = " + LOCKED_A
                + " AND LockedUntil IS NULL AND FailedLoginCount = 0")).isOne();
        assertThat(audits("USER_UNLOCKED", LOCKED_A, BANK_A)).isOne();
    }

    @Test
    void aDualControlledPasswordResetIsSubmittedAndAppliedUnderRls() throws Exception {
        MvcResult submitted = perform(json(post("/api/admin/users/" + RESET_A
                        + "/password-reset"), "{\"temporaryPassword\":\"" + TEMPORARY + "\"}"),
                ADMIN_A, BANK_A, Role.ADMIN);
        assertThat(submitted.getResponse().getStatus())
                .as(submitted.getResponse().getContentAsString()).isEqualTo(202);
        int pendingId = MAPPER.readTree(submitted.getResponse().getContentAsString())
                .get("pendingChangeId").asInt();
        assertThat(ownerLong("SELECT count(*) FROM PendingChange WHERE PendingChangeId = "
                + pendingId + " AND BankId = " + BANK_A)).isOne();

        // Bank B's admin cannot even see it under RLS.
        assertThat(perform(post("/api/admin/changes/" + pendingId + "/approve"), ADMIN_B, BANK_B,
                Role.ADMIN).getResponse().getStatus()).isEqualTo(404);

        MvcResult approved = perform(post("/api/admin/changes/" + pendingId + "/approve"),
                ADMIN2_A, BANK_A, Role.ADMIN);
        assertThat(approved.getResponse().getStatus())
                .as(approved.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(MAPPER.readTree(approved.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("APPLIED");

        assertThat(ownerLong("SELECT count(*) FROM AppUsers WHERE AppUserId = " + RESET_A
                + " AND MustChangePassword AND TokenVersion = 1")).isOne();
        assertThat(ownerLong("SELECT count(*) FROM RefreshToken WHERE UserId = " + RESET_A
                + " AND RevokedAt IS NULL")).isZero();
        assertThat(audits("USER_PASSWORD_RESET", RESET_A, BANK_A)).isOne();
        assertThat(ownerLong("SELECT count(*) FROM AuditEvent WHERE BankId = " + BANK_A
                + " AND CAST(NewValues AS text) LIKE '%" + TEMPORARY + "%'"))
                .as("the temporary password never reaches the audit trail").isZero();
    }

    @Test
    void aDualControlledMfaResetIsAppliedUnderRls() throws Exception {
        MvcResult submitted = perform(json(post("/api/admin/users/" + MFA_A + "/mfa-reset"),
                "{\"reason\":\"QA: lost phone\"}"), ADMIN_A, BANK_A, Role.ADMIN);
        assertThat(submitted.getResponse().getStatus())
                .as(submitted.getResponse().getContentAsString()).isEqualTo(202);
        int pendingId = MAPPER.readTree(submitted.getResponse().getContentAsString())
                .get("pendingChangeId").asInt();

        MvcResult approved = perform(post("/api/admin/changes/" + pendingId + "/approve"),
                ADMIN2_A, BANK_A, Role.ADMIN);
        assertThat(approved.getResponse().getStatus())
                .as(approved.getResponse().getContentAsString()).isEqualTo(200);

        assertThat(ownerLong("SELECT count(*) FROM UserMfa WHERE UserId = " + MFA_A)).isZero();
        assertThat(audits("USER_MFA_RESET", MFA_A, BANK_A)).isOne();
    }

    @Test
    void aDisplayNameEditIsAuditedUnderRls() throws Exception {
        MvcResult result = perform(json(patch("/api/admin/users/" + RENAME_A),
                "{\"displayName\":\"QA Renamed\"}"), ADMIN_A, BANK_A, Role.ADMIN);

        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(ownerLong("SELECT count(*) FROM AppUsers WHERE AppUserId = " + RENAME_A
                + " AND DisplayName = 'QA Renamed'")).isOne();
        assertThat(audits("USER_UPDATED", RENAME_A, BANK_A)).isOne();
    }

    @Test
    void anotherBanksAdminGets404OnEveryActionAndChangesNothing() throws Exception {
        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {
                post("/api/admin/users/" + LOCKED_A + "/unlock"),
                json(post("/api/admin/users/" + RESET_A + "/password-reset"),
                        "{\"temporaryPassword\":\"" + TEMPORARY + "\"}"),
                json(post("/api/admin/users/" + MFA_A + "/mfa-reset"), "{\"reason\":\"x\"}"),
                json(patch("/api/admin/users/" + RENAME_A), "{\"displayName\":\"Hijack\"}")}) {
            assertThat(perform(request, ADMIN_B, BANK_B, Role.ADMIN).getResponse().getStatus())
                    .isEqualTo(404);
        }
        assertThat(ownerLong("SELECT count(*) FROM AppUsers WHERE DisplayName = 'Hijack'"))
                .isZero();
        assertThat(ownerLong("SELECT count(*) FROM PendingChange WHERE BankId = " + BANK_B))
                .isZero();
    }

    // ----------------------------------------------------------- helpers

    private MvcResult perform(MockHttpServletRequestBuilder request, int userId, int bankId,
                              Role role) throws Exception {
        AppUser user = new AppUser();
        user.setAppUserId(userId);
        user.setBankId(bankId);
        user.setRole(role);
        user.setTokenVersion(0);
        return mvc.perform(request.header("Authorization", "Bearer "
                + jwtService.issueToken(user))).andReturn();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request,
                                                      String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long audits(String action, int targetUserId, int bankId) throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             PreparedStatement statement = owner.prepareStatement("SELECT count(*) FROM "
                     + "AuditEvent WHERE Action = ? AND EntityId = ? AND BankId = ?")) {
            statement.setString(1, action);
            statement.setString(2, String.valueOf(targetUserId));
            statement.setInt(3, bankId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long ownerLong(String sql) throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static final String USER_COLUMNS = "INSERT INTO AppUsers (AppUserId, Email, "
            + "PasswordHash, DisplayName, SettingsJson, CreatedDate, UpdatedDate, BankId, Role, "
            + "TokenVersion, Disabled, MustChangePassword) VALUES ";

    private static final String[] SEED = {
            "INSERT INTO Bank (BankId, Code, Name) VALUES "
                    + "(560, 'QAUA_A', 'QA user actions A'), (660, 'QAUA_B', 'QA user actions B') "
                    + "ON CONFLICT DO NOTHING",
            USER_COLUMNS
                    + "(9560, 'qaua-a-admin@example.com', '{noop}x', 'QA A Admin', '{}', now(), now(), 560, 'ADMIN', 0, false, false), "
                    + "(9561, 'qaua-a-admin2@example.com', '{noop}x', 'QA A Admin 2', '{}', now(), now(), 560, 'ADMIN', 0, false, false), "
                    + "(9562, 'qaua-a-locked@example.com', '{noop}x', 'QA A Locked', '{}', now(), now(), 560, 'READ_WRITE', 0, false, false), "
                    + "(9563, 'qaua-a-reset@example.com', '{noop}x', 'QA A Reset', '{}', now(), now(), 560, 'READ_WRITE', 0, false, false), "
                    + "(9564, 'qaua-a-mfa@example.com', '{noop}x', 'QA A Mfa', '{}', now(), now(), 560, 'ADMIN', 0, false, false), "
                    + "(9565, 'qaua-a-rename@example.com', '{noop}x', 'QA A Rename', '{}', now(), now(), 560, 'READ_ONLY', 0, false, false), "
                    + "(9660, 'qaua-b-admin@example.com', '{noop}x', 'QA B Admin', '{}', now(), now(), 660, 'ADMIN', 0, false, false) "
                    + "ON CONFLICT DO NOTHING",
            "UPDATE AppUsers SET LockedUntil = now() + interval '30 minutes', FailedLoginCount = 5,"
                    + " LockoutCount = 1 WHERE AppUserId = 9562",
            "INSERT INTO UserMfa (UserId, SecretCiphertext, EnabledAt, CreatedAt) VALUES "
                    + "(9564, 'qa-not-a-real-ciphertext', now(), now()) ON CONFLICT DO NOTHING",
            "INSERT INTO RefreshToken (Jti, UserId, FamilyId, TokenHash, IssuedAt, ExpiresAt) VALUES "
                    + "('6a1f3c2e-0000-4000-8000-000000009563', 9563, "
                    + "'6a1f3c2e-0000-4000-8000-00000000f563', "
                    + "'0000000000000000000000000000000000000000000000000000000000009563', "
                    + "now(), now() + interval '1 day') ON CONFLICT DO NOTHING",
    };
}
