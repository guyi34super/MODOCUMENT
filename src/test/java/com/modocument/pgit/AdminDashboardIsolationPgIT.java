package com.modocument.pgit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin dashboard (phase 2, ws22) on the REAL PostgreSQL schema with row
 * level security enforced (app booted on {@code modocument_app}): every
 * JPQL count runs against the Flyway schema, the RLS-scoped tables answer
 * for the caller's tenant only, and — the point of this test — AppUsers and
 * UserMfa, which are NOT under RLS, are still counted for the caller's bank
 * alone because the queries name the bank explicitly. Bank B is seeded with
 * more of everything than bank A, so a missing filter inflates A's numbers.
 */
class AdminDashboardIsolationPgIT extends PgSpringItSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BANK_A = 310;
    private static final int BANK_B = 410;
    private static final int ADMIN_A = 9310;
    private static final int ADMIN_B = 9410;

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
    void bankACountsOnlyItsOwnRowsEvenInTheTablesWithoutRls() throws Exception {
        String json = dashboard(ADMIN_A, BANK_A);
        JsonNode body = MAPPER.readTree(json);

        assertThat(body.get("bank").get("code").asText()).isEqualTo("DASH_A");
        JsonNode users = body.get("users");
        assertThat(users.get("total").asLong()).isEqualTo(3);
        assertThat(users.get("active").asLong()).isEqualTo(2);
        assertThat(users.get("disabled").asLong()).isEqualTo(1);
        assertThat(users.get("locked").asLong()).isEqualTo(1);
        assertThat(users.get("mustChangePassword").asLong()).isEqualTo(1);
        assertThat(users.get("byRole").get("ADMIN").asLong()).isEqualTo(1);
        assertThat(users.get("byRole").get("READ_WRITE").asLong()).isEqualTo(2);
        assertThat(users.get("mfaEnrolled").asLong()).isEqualTo(1);
        assertThat(users.get("adminsWithoutMfa").asLong()).isZero();
        assertThat(users.get("activeLast7d").asLong()).isEqualTo(1);

        assertThat(body.get("approvals").get("pendingChanges").asLong()).isEqualTo(1);
        assertThat(body.get("approvals").get("pendingReleases").asLong()).isZero();
        assertThat(body.get("ingestion").get("last7d").get("byStatus").get("FAILED").asLong())
                .isEqualTo(1);
        assertThat(body.get("ingestion").get("failures7d").asLong()).isEqualTo(1);
        assertThat(body.get("extraction").get("last7d").get("byStatus").get("COMPLETED")
                .asLong()).isEqualTo(1);
        assertThat(body.get("extraction").get("last7d").get("byStatus").get("FAILED")
                .asLong()).isZero();
        assertThat(body.get("alerts").get("last7dBySeverity").get("HIGH").asLong())
                .isEqualTo(1);
        assertThat(body.get("alerts").get("last7dBySeverity").get("CRITICAL").asLong())
                .isZero();
        assertThat(body.get("templates").get("private").asLong()).isEqualTo(1);
        assertThat(body.get("retention").get("lastPurged").asLong()).isEqualTo(4);
        assertThat(body.get("ingestion").get("parsedBy").get("AI").asLong()).isEqualTo(1);
        assertThat(body.get("ingestion").get("parsedBy").get("CODE").asLong()).isZero();

        JsonNode hub = body.get("integrations");
        assertThat(hub.get("runs24h").get("ok").asLong()).isEqualTo(1);
        assertThat(hub.get("runs24h").get("failed").asLong()).isEqualTo(1);
        assertThat(hub.get("deadLetters").asLong()).isEqualTo(1);
        assertThat(hub.get("agentsOffline").asLong()).isEqualTo(1);
        assertThat(hub.get("apiCalls24h").asLong()).isEqualTo(2);

        assertThat(json).doesNotContain("@example.com").doesNotContain("DASH_B")
                .doesNotContain("Dash Template");
    }

    @Test
    void bankBSeesItsOwnLargerNumbers() throws Exception {
        JsonNode body = MAPPER.readTree(dashboard(ADMIN_B, BANK_B));

        assertThat(body.get("bank").get("code").asText()).isEqualTo("DASH_B");
        assertThat(body.get("users").get("total").asLong()).isEqualTo(4);
        assertThat(body.get("users").get("mfaEnrolled").asLong()).isEqualTo(2);
        assertThat(body.get("users").get("adminsWithoutMfa").asLong()).isEqualTo(1);
        assertThat(body.get("users").get("locked").asLong()).isEqualTo(2);
        assertThat(body.get("approvals").get("pendingChanges").asLong()).isEqualTo(2);
        assertThat(body.get("ingestion").get("failures7d").asLong()).isEqualTo(2);
        assertThat(body.get("alerts").get("last7dBySeverity").get("CRITICAL").asLong())
                .isEqualTo(2);
        assertThat(body.get("templates").get("private").asLong()).isEqualTo(2);
        assertThat(body.get("retention").get("lastPurged").asLong()).isEqualTo(70);
        assertThat(body.get("ingestion").get("parsedBy").get("CODE").asLong()).isEqualTo(2);
        assertThat(body.get("ingestion").get("parsedBy").get("AI").asLong()).isZero();

        JsonNode hub = body.get("integrations");
        assertThat(hub.get("runs24h").get("ok").asLong()).isEqualTo(3);
        assertThat(hub.get("runs24h").get("failed").asLong()).isEqualTo(3);
        assertThat(hub.get("deadLetters").asLong()).isEqualTo(3);
        assertThat(hub.get("agentsOffline").asLong()).isEqualTo(3);
        assertThat(hub.get("apiCalls24h").asLong()).isEqualTo(4);
    }

    private String dashboard(int userId, int bankId) throws Exception {
        AppUser user = new AppUser();
        user.setAppUserId(userId);
        user.setBankId(bankId);
        user.setRole(Role.ADMIN);
        user.setTokenVersion(0);
        return mvc.perform(get("/api/admin/dashboard")
                        .header("Authorization", "Bearer " + jwtService.issueToken(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static final String USER_COLUMNS = "INSERT INTO AppUsers (AppUserId, Email, "
            + "PasswordHash, DisplayName, SettingsJson, CreatedDate, UpdatedDate, BankId, Role, "
            + "TokenVersion, Disabled, MustChangePassword, LockedUntil, LastLoginAt) VALUES ";

    private static final String[] SEED = {
            "INSERT INTO Bank (BankId, Code, Name) VALUES "
                    + "(310, 'DASH_A', 'Dash Bank A'), (410, 'DASH_B', 'Dash Bank B') "
                    + "ON CONFLICT DO NOTHING",
            // Bank A: an admin with MFA, a locked writer with a temporary
            // password who signed in yesterday, a disabled writer.
            USER_COLUMNS
                    + "(9310, 'dash-a-admin@example.com', '{noop}x', 'Dash A Admin', '{}', now(), now(), 310, 'ADMIN', 0, false, false, null, null), "
                    + "(9311, 'dash-a-writer@example.com', '{noop}x', 'Dash A Writer', '{}', now(), now(), 310, 'READ_WRITE', 0, false, true, now() + interval '1 day', now() - interval '1 day'), "
                    + "(9312, 'dash-a-leaver@example.com', '{noop}x', 'Dash A Leaver', '{}', now(), now(), 310, 'READ_WRITE', 0, true, false, null, now() - interval '30 days') "
                    + "ON CONFLICT DO NOTHING",
            // Bank B: more of everything.
            USER_COLUMNS
                    + "(9410, 'dash-b-admin@example.com', '{noop}x', 'Dash B Admin', '{}', now(), now(), 410, 'ADMIN', 0, false, false, null, now()), "
                    + "(9411, 'dash-b-admin2@example.com', '{noop}x', 'Dash B Admin2', '{}', now(), now(), 410, 'ADMIN', 0, false, true, now() + interval '1 day', now()), "
                    + "(9412, 'dash-b-admin3@example.com', '{noop}x', 'Dash B Admin3', '{}', now(), now(), 410, 'ADMIN', 0, false, true, now() + interval '1 day', now()), "
                    + "(9413, 'dash-b-writer@example.com', '{noop}x', 'Dash B Writer', '{}', now(), now(), 410, 'READ_WRITE', 0, false, true, null, now()) "
                    + "ON CONFLICT DO NOTHING",
            "INSERT INTO UserMfa (UserId, SecretCiphertext, EnabledAt, CreatedAt) VALUES "
                    + "(9310, 'unused', now(), now()), (9410, 'unused', now(), now()), "
                    + "(9411, 'unused', now(), now()), "
                    // a half-finished enrolment is not "enrolled"
                    + "(9412, 'unused', null, now()) "
                    + "ON CONFLICT DO NOTHING",
            "INSERT INTO Template (TemplateId, Name, Description, OwnerUserId, BankId) VALUES "
                    + "(7310, 'Dash Template A', 'seed', 9311, 310), "
                    + "(7410, 'Dash Template B1', 'seed', 9413, 410), "
                    + "(7411, 'Dash Template B2', 'seed', 9413, 410) "
                    + "ON CONFLICT DO NOTHING",
            "INSERT INTO IngestionJob (IngestionJobId, TemplateId, FileName, CurrentStep, Status, "
                    + "CreatedDate, UpdatedDate, OwnerUserId, BankId, ParsedBy) VALUES "
                    + "(7320, 7310, 'dash-a.csv', 'UPLOADED', 'FAILED', now(), now(), 9311, 310, 'AI'), "
                    + "(7420, 7410, 'dash-b.csv', 'UPLOADED', 'FAILED', now(), now(), 9413, 410, 'CODE'), "
                    + "(7421, 7410, 'dash-b.csv', 'UPLOADED', 'FAILED', now(), now(), 9413, 410, 'CODE') "
                    + "ON CONFLICT DO NOTHING",
            // Integration hub (ws25/ws26): one connection + outbound flow per bank.
            "INSERT INTO IntegrationConnection (BankId, Name, Type, Mode, CreatedBy) VALUES "
                    + "(310, 'dash-hub', 'REST_API', 'DIRECT', 9310), "
                    + "(410, 'dash-hub', 'REST_API', 'DIRECT', 9410) "
                    + "ON CONFLICT DO NOTHING",
            "INSERT INTO IntegrationFlow (BankId, Name, ConnectionId, Direction, TriggerType, "
                    + "CreatedBy) SELECT BankId, Name, IntegrationConnectionId, 'OUTBOUND', "
                    + "'MANUAL', CreatedBy FROM IntegrationConnection WHERE Name = 'dash-hub' "
                    + "ON CONFLICT DO NOTHING",
            // Bank A: 1 ok + 1 failed in the last 24 h, one ok run older than that.
            // Bank B: 3 ok + 3 failed.
            "INSERT INTO IntegrationRun (BankId, FlowId, ConnectionId, Operation, TriggerType, "
                    + "ScheduledFor, Status, FinishedAt) "
                    + "SELECT f.BankId, f.IntegrationFlowId, f.ConnectionId, 'WRITE', 'MANUAL', "
                    + "now() - (r.n * interval '1 minute'), r.status, now() - r.age "
                    + "FROM IntegrationFlow f JOIN (VALUES "
                    + "(310, 1, 'SUCCEEDED', interval '1 hour'), "
                    + "(310, 2, 'FAILED', interval '2 hours'), "
                    + "(310, 3, 'SUCCEEDED', interval '30 hours'), "
                    + "(410, 1, 'SUCCEEDED', interval '0'), (410, 2, 'SUCCEEDED', interval '0'), "
                    + "(410, 3, 'SUCCEEDED', interval '0'), (410, 4, 'FAILED', interval '0'), "
                    + "(410, 5, 'FAILED', interval '0'), (410, 6, 'FAILED', interval '0')"
                    + ") AS r(bank, n, status, age) ON r.bank = f.BankId "
                    + "WHERE f.Name = 'dash-hub' ON CONFLICT DO NOTHING",
            "INSERT INTO OutboundDelivery (BankId, FlowId, PayloadKind, PayloadRef, Status) "
                    + "SELECT f.BankId, f.IntegrationFlowId, 'JOB_STATUS', 'job:1', d.status "
                    + "FROM IntegrationFlow f JOIN (VALUES (310, 'DEAD'), (310, 'DELIVERED'), "
                    + "(410, 'DEAD'), (410, 'DEAD'), (410, 'DEAD')) AS d(bank, status) "
                    + "ON d.bank = f.BankId WHERE f.Name = 'dash-hub'",
            // Bank A: one silent agent (offline) and one fresh (online);
            // bank B: three that never sent a heartbeat (offline).
            "INSERT INTO IntegrationAgent (BankId, Name, Status, LastHeartbeatAt, "
                    + "CreatedByUserId, CreatedAt) VALUES "
                    + "(310, 'dash-a-silent', 'ONLINE', now() - interval '2 days', 9310, now()), "
                    + "(310, 'dash-a-fresh', 'ONLINE', now(), 9310, now()), "
                    + "(410, 'dash-b-1', 'ONLINE', null, 9410, now()), "
                    + "(410, 'dash-b-2', 'ONLINE', null, 9410, now()), "
                    + "(410, 'dash-b-3', 'ONLINE', null, 9410, now())",
            // Integration API calls (MachineApiInterceptor's audit rows).
            "INSERT INTO AuditEvent (AuditEventId, OccurredAt, BankId, Action, EntityType, "
                    + "EntityId, Outcome) VALUES "
                    + "(gen_random_uuid(), now(), 310, 'INTEGRATION_API_CALL', 'ApiClient', '1', 'SUCCESS'), "
                    + "(gen_random_uuid(), now(), 310, 'INTEGRATION_API_CALL', 'ApiClient', '1', 'SUCCESS'), "
                    + "(gen_random_uuid(), now() - interval '2 days', 310, 'INTEGRATION_API_CALL', 'ApiClient', '1', 'SUCCESS'), "
                    + "(gen_random_uuid(), now(), 410, 'INTEGRATION_API_CALL', 'ApiClient', '2', 'SUCCESS'), "
                    + "(gen_random_uuid(), now(), 410, 'INTEGRATION_API_CALL', 'ApiClient', '2', 'SUCCESS'), "
                    + "(gen_random_uuid(), now(), 410, 'INTEGRATION_API_CALL', 'ApiClient', '2', 'SUCCESS'), "
                    + "(gen_random_uuid(), now(), 410, 'INTEGRATION_API_CALL', 'ApiClient', '2', 'SUCCESS')",
            "INSERT INTO ExtractionSource (ExtractionSourceId, OwnerUserId, Name, Type, "
                    + "TermsAcknowledged, BankId) VALUES "
                    + "(7330, 9311, 'Dash A Source', 'WEBSITE', true, 310), "
                    + "(7430, 9413, 'Dash B Source', 'WEBSITE', true, 410) "
                    + "ON CONFLICT DO NOTHING",
            // Terminal statuses only: SqlExtractionStartupRecovery settles
            // RUNNING jobs when the (shared) context boots after this seed.
            "INSERT INTO ExtractionJob (ExtractionJobId, OwnerUserId, SourceId, Status, Incremental, "
                    + "BankId, CreatedDate, UpdatedDate) VALUES "
                    + "(7340, 9311, '7330', 'COMPLETED', false, 310, now(), now()), "
                    + "(7440, 9413, '7430', 'FAILED', false, 410, now(), now()), "
                    + "(7441, 9413, '7430', 'FAILED', false, 410, now(), now()) "
                    + "ON CONFLICT DO NOTHING",
            "INSERT INTO AlertLog (BankId, RuleName, Severity, DedupeKey, EventCount, Summary, "
                    + "CreatedAt, Status) VALUES "
                    + "(310, 'dash', 'HIGH', 'dash-a-1', 1, 'seed', now(), 'SENT'), "
                    + "(410, 'dash', 'CRITICAL', 'dash-b-1', 1, 'seed', now(), 'SENT'), "
                    + "(410, 'dash', 'CRITICAL', 'dash-b-2', 1, 'seed', now(), 'SENT')",
            "INSERT INTO PendingChange (BankId, ChangeType, TargetEntityType, PayloadJson, Status, "
                    + "MakerUserId) VALUES "
                    + "(310, 'USER_ROLE_CHANGE', 'AppUser', '{}', 'REQUESTED', 9310), "
                    + "(410, 'USER_ROLE_CHANGE', 'AppUser', '{}', 'REQUESTED', 9410), "
                    + "(410, 'SETTING_UPDATE', 'Setting', '{}', 'REQUESTED', 9410)",
            "INSERT INTO AuditEvent (AuditEventId, OccurredAt, BankId, Action, EntityType, "
                    + "EntityId, NewValues, Outcome) VALUES "
                    + "(gen_random_uuid(), now(), 310, 'RETENTION_PURGE_COMPLETED', 'Bank', '310', "
                    + "'{\"retentionDays\":30,\"cutoff\":\"x\",\"uploadedFiles\":3,\"ingestionJobs\":1}', 'SUCCESS'), "
                    + "(gen_random_uuid(), now(), 410, 'RETENTION_PURGE_COMPLETED', 'Bank', '410', "
                    + "'{\"retentionDays\":30,\"cutoff\":\"x\",\"uploadedFiles\":60,\"ingestionJobs\":10}', 'SUCCESS')",
    };
}
