package com.modocument.pgit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.integration.egress.BankEgressGuard;
import com.modocument.infrastructure.net.EgressTargets;
import com.modocument.infrastructure.net.OutboundHostPolicy;
import com.modocument.infrastructure.persistence.BankEgressRuleJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws25 egress rules on the REAL schema with row level security enforced
 * (the app runs as {@code modocument_app}). The platform admins' requests and
 * the approval transaction are bound to the PLATFORM bank, under whose RLS no
 * customer rule is visible — yet the platform console must list bank A's
 * request, the submit check must find it, and the applier must flip it to
 * APPROVED in bank A with the audit row stamped bank A. The guard then reads
 * bank A's approved rules under bank A's tenant, whatever context it is
 * called from, and bank B never sees them. The H2 suite has no RLS.
 */
class EgressRulePgIT extends PgSpringItSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PLATFORM_CODE = "MOI-PLATFORM";
    private static final int PLATFORM_A = 925_001;
    private static final int PLATFORM_B = 925_002;
    private static final int ADMIN_A = 925_011;
    private static final int ADMIN_B = 925_021;

    private static int platformBankId;
    private static int bankA;
    private static int bankB;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private BankEgressRuleJpaRepository rules;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    static void seedBanksAndAdmins() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("INSERT INTO Bank (Code, Name, Kind) VALUES ('" + PLATFORM_CODE
                    + "', 'MO Intelligence Platform', 'PLATFORM') ON CONFLICT DO NOTHING");
            platformBankId = queryInt(owner, "SELECT BankId FROM Bank WHERE Code = ? "
                    + "AND Kind = 'PLATFORM'", PLATFORM_CODE);
            bankA = insertBank(owner, "EGRA" + tag);
            bankB = insertBank(owner, "EGRB" + tag);
            statement.execute("INSERT INTO AppUsers (AppUserId, Email, PasswordHash, DisplayName, "
                    + "SettingsJson, CreatedDate, UpdatedDate, BankId, Role, TokenVersion, "
                    + "Disabled, MustChangePassword) VALUES "
                    + user(PLATFORM_A, "pgit-egress-pa", platformBankId, "PLATFORM_ADMIN") + ", "
                    + user(PLATFORM_B, "pgit-egress-pb", platformBankId, "PLATFORM_ADMIN") + ", "
                    + user(ADMIN_A, "pgit-egress-a-" + tag, bankA, "ADMIN") + ", "
                    + user(ADMIN_B, "pgit-egress-b-" + tag, bankB, "ADMIN")
                    + " ON CONFLICT (AppUserId) DO UPDATE SET BankId = EXCLUDED.BankId");
        }
    }

    @Test
    void approvalCrossesTheTenantBoundaryOnlyThroughTheRegisteredPaths() throws Exception {
        BankEgressGuard guard = new BankEgressGuard(OutboundHostPolicy.enforcing(), rules,
                transactionManager, Duration.ZERO, EgressRulePgIT::lookup, Clock.systemUTC());

        // ---- bank A requests ------------------------------------------------
        JsonNode created = send(post("/api/admin/integrations/egress-rules"), ADMIN_A, bankA,
                Role.ADMIN, "{\"cidr\":\"10.77.0.0/16\",\"port\":1521,"
                        + "\"reason\":\"Oracle over site-to-site VPN\"}", 200);
        int ruleId = created.get("id").asInt();
        assertThat(created.get("status").asText()).isEqualTo("PENDING");
        // Bank B's admin sees nothing of it and cannot revoke it.
        assertThat(send(get("/api/admin/integrations/egress-rules"), ADMIN_B, bankB, Role.ADMIN,
                null, 200)).isEmpty();
        send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/admin/integrations/egress-rules/" + ruleId), ADMIN_B, bankB,
                Role.ADMIN, null, 404);

        // ---- the platform lists it across banks (runAsSystem) -----------------
        assertThat(contains(send(get("/api/platform/egress-rules"), PLATFORM_A, platformBankId,
                Role.PLATFORM_ADMIN, null, 200), ruleId)).isTrue();

        // ---- maker/checker approval -------------------------------------------
        JsonNode change = send(post("/api/platform/egress-rules/" + ruleId + "/approve"),
                PLATFORM_A, platformBankId, Role.PLATFORM_ADMIN, null, 202);
        int pendingId = change.get("pendingChangeId").asInt();
        assertThatThrownBy(() -> guard.checkDestination(bankA, "ora.bank-a.vpn", 1521))
                .isInstanceOf(SecurityException.class);
        JsonNode applied = send(post("/api/admin/changes/" + pendingId + "/approve"),
                PLATFORM_B, platformBankId, Role.PLATFORM_ADMIN, null, 200);
        assertThat(applied.get("status").asText()).isEqualTo("APPLIED");

        try (Connection owner = ownerDataSource().getConnection()) {
            assertThat(queryString(owner, "SELECT Status FROM BankEgressRule "
                    + "WHERE BankEgressRuleId = ? AND BankId = ?", ruleId, bankA))
                    .isEqualTo("APPROVED");
            assertThat(queryInt(owner, "SELECT ApprovedByUserId FROM BankEgressRule "
                    + "WHERE BankEgressRuleId = ?", ruleId)).isEqualTo(PLATFORM_B);
            assertThat(auditCount(owner, "EGRESS_RULE_APPROVED", bankA, ruleId)).isOne();
            assertThat(auditCount(owner, "EGRESS_RULE_APPROVED", platformBankId, ruleId)).isOne();
            assertThat(auditCount(owner, "EGRESS_RULE_REQUESTED", bankA, ruleId)).isOne();
        }
        // Under RLS, bank B's tenant cannot read bank A's rule.
        try (Connection app = appDataSource().getConnection()) {
            app.setAutoCommit(false);
            setTenant(app, String.valueOf(bankB));
            assertThat(queryInt(app, "SELECT count(*) FROM BankEgressRule "
                    + "WHERE BankEgressRuleId = ?", ruleId)).isZero();
            app.rollback();
        }

        // ---- the guard reads bank A's rules under bank A's tenant ---------------
        assertThatCode(() -> guard.checkDestination(bankA, "ora.bank-a.vpn", 1521))
                .doesNotThrowAnyException();
        // ...even when the caller's thread is bound to another bank.
        TenantContext.runAs(bankB, () ->
                assertThatCode(() -> guard.checkDestination(bankA, "ora.bank-a.vpn", 1521))
                        .doesNotThrowAnyException());
        assertThatThrownBy(() -> guard.checkDestination(bankB, "ora.bank-a.vpn", 1521))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> guard.checkDestination(bankA, "ora.bank-a.vpn", 1522))
                .isInstanceOf(SecurityException.class);
    }

    // ------------------------------------------------------------- plumbing

    private static InetAddress[] lookup(String host) throws UnknownHostException {
        if (host.equals("ora.bank-a.vpn")) {
            return new InetAddress[]{EgressTargets.literal("10.77.3.4")};
        }
        throw new UnknownHostException(host);
    }

    private JsonNode send(MockHttpServletRequestBuilder request, int userId, int bankId, Role role,
                          String json, int expectedStatus) throws Exception {
        AppUser user = new AppUser();
        user.setAppUserId(userId);
        user.setBankId(bankId);
        user.setRole(role);
        user.setTokenVersion(0);
        request.header("Authorization", "Bearer " + jwtService.issueToken(user));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        String body = mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
    }

    private static boolean contains(JsonNode list, int ruleId) {
        for (JsonNode rule : list) {
            if (rule.get("id").asInt() == ruleId) {
                return true;
            }
        }
        return false;
    }

    private static String user(int id, String emailPrefix, int bankId, String role) {
        return "(" + id + ", '" + emailPrefix + "@moi.example', '{noop}x', '" + emailPrefix
                + "', '{}', now(), now(), " + bankId + ", '" + role + "', 0, false, false)";
    }

    private static int insertBank(Connection owner, String code) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(
                "INSERT INTO Bank (Code, Name, Kind) VALUES (?, ?, 'CUSTOMER') RETURNING BankId")) {
            statement.setString(1, code);
            statement.setString(2, "Egress " + code);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1);
            }
        }
    }

    private static int auditCount(Connection connection, String action, int auditBank,
                                  int ruleId) throws SQLException {
        return queryInt(connection, "SELECT count(*) FROM AuditEvent WHERE Action = ? "
                + "AND BankId = ? AND EntityType = 'BankEgressRule' AND EntityId = ?",
                action, auditBank, String.valueOf(ruleId));
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

    private static String queryString(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as(sql).isTrue();
                return rs.getString(1);
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
