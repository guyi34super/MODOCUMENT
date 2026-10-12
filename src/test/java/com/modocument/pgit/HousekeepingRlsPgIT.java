package com.modocument.pgit;

import com.modocument.application.port.TenantContext;
import com.modocument.infrastructure.housekeeping.BankHousekeepingSummary;
import com.modocument.infrastructure.housekeeping.HousekeepingJob;
import com.modocument.infrastructure.housekeeping.HousekeepingPurger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ws27 housekeeping on the REAL schema with row level security ENFORCED (the
 * app runs as {@code modocument_app}): under {@code runAs(bank)} each rule
 * deletes that bank's old rows — through the RLS policy, with the runtime
 * role's grants, honouring the foreign keys (run log → run, outbox → run) —
 * and nothing of another bank, even when asked to by id; the non-tenant
 * RevokedToken sweep runs as SYSTEM; the bank gets its
 * HOUSEKEEPING_COMPLETED audit row in the append-only trail.
 */
class HousekeepingRlsPgIT extends PgSpringItSupport {

    private static final int BANK_A = 880;
    private static final int BANK_B = 881;

    @Autowired
    private HousekeepingJob job;
    @Autowired
    private HousekeepingPurger purger;

    @BeforeAll
    static void seedAsOwner() throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement sql = owner.createStatement()) {
            sql.execute("INSERT INTO Bank (BankId, Code, Name) VALUES "
                    + "(880, 'HK_A', 'Housekeeping A'), (881, 'HK_B', 'Housekeeping B') "
                    + "ON CONFLICT DO NOTHING");
            sql.execute("INSERT INTO AppUsers (AppUserId, Email, PasswordHash, DisplayName, "
                    + "SettingsJson, CreatedDate, UpdatedDate, BankId, Role, TokenVersion, Disabled, "
                    + "MustChangePassword) VALUES "
                    + "(9880, 'hk-a@example.com', '{noop}x', 'HK A', '{}', now(), now(), 880, "
                    + "'READ_WRITE', 0, false, false), "
                    + "(9881, 'hk-b@example.com', '{noop}x', 'HK B', '{}', now(), now(), 881, "
                    + "'READ_WRITE', 0, false, false) ON CONFLICT DO NOTHING");
            // Refresh tokens: per bank one expired 40 days ago, one live.
            sql.execute("INSERT INTO RefreshToken (Jti, UserId, FamilyId, TokenHash, IssuedAt, "
                    + "ExpiresAt) SELECT gen_random_uuid(), u, gen_random_uuid(), md5(random()::text), "
                    + "now() - interval '47 days', now() - e "
                    + "FROM (VALUES (9880, interval '40 days'), (9880, interval '-7 days'), "
                    + "(9881, interval '40 days')) AS t(u, e)");
            sql.execute("INSERT INTO RevokedToken (Jti, ExpiresAt) VALUES "
                    + "('00000000-0000-0000-0000-000000000881', now() - interval '1 hour'), "
                    + "('00000000-0000-0000-0000-000000000882', now() + interval '1 hour') "
                    + "ON CONFLICT DO NOTHING");
            sql.execute("INSERT INTO IntegrationConnection (BankId, Name, Type, Mode, CreatedBy) "
                    + "VALUES (880, 'hk-hub', 'REST_API', 'DIRECT', 9880), "
                    + "(881, 'hk-hub', 'REST_API', 'DIRECT', 9881) ON CONFLICT DO NOTHING");
            sql.execute("INSERT INTO IntegrationFlow (BankId, Name, ConnectionId, Direction, "
                    + "TriggerType, CreatedBy) SELECT BankId, Name, IntegrationConnectionId, "
                    + "'OUTBOUND', 'MANUAL', CreatedBy FROM IntegrationConnection "
                    + "WHERE Name = 'hk-hub' ON CONFLICT DO NOTHING");
            // Per bank: two runs finished 120 days ago (one with a run log and a
            // DELIVERED outbox row), one finished yesterday.
            sql.execute("INSERT INTO IntegrationRun (BankId, FlowId, ConnectionId, Operation, "
                    + "TriggerType, ScheduledFor, Status, CreatedAt, FinishedAt) "
                    + "SELECT f.BankId, f.IntegrationFlowId, f.ConnectionId, 'WRITE', 'MANUAL', "
                    + "now() - a, 'SUCCEEDED', now() - a, now() - a FROM IntegrationFlow f "
                    + "CROSS JOIN (VALUES (interval '120 days'), (interval '121 days'), "
                    + "(interval '1 day')) AS t(a) WHERE f.Name = 'hk-hub'");
            sql.execute("INSERT INTO IntegrationRunEvent (BankId, RunId, At, Level, Message) "
                    + "SELECT r.BankId, r.IntegrationRunId, r.FinishedAt, 'INFO', 'done' "
                    + "FROM IntegrationRun r JOIN IntegrationFlow f ON f.IntegrationFlowId = r.FlowId "
                    + "WHERE f.Name = 'hk-hub'");
            sql.execute("INSERT INTO OutboundDelivery (BankId, FlowId, RunId, PayloadKind, "
                    + "PayloadRef, Status, CreatedAt) SELECT r.BankId, r.FlowId, r.IntegrationRunId, "
                    + "'JOB_STATUS', 'job:1', 'DELIVERED', r.FinishedAt FROM IntegrationRun r "
                    + "JOIN IntegrationFlow f ON f.IntegrationFlowId = r.FlowId "
                    + "WHERE f.Name = 'hk-hub' AND r.FinishedAt < now() - interval '120 days 12 hours'");
            // Events: an old one, an old one a PENDING delivery still needs, a fresh one.
            sql.execute("INSERT INTO IntegrationEvent (BankId, Type, EntityType, EntityId, "
                    + "OccurredAt) SELECT b, 'INGESTION_VALIDATED', 'IngestionJob', k, now() - a "
                    + "FROM (VALUES (880), (881)) AS banks(b) CROSS JOIN (VALUES "
                    + "('old', interval '100 days'), ('needed', interval '100 days'), "
                    + "('fresh', interval '1 day')) AS t(k, a)");
            sql.execute("INSERT INTO OutboundDelivery (BankId, FlowId, EventId, PayloadKind, "
                    + "PayloadRef, Status, NextAttemptAt, CreatedAt) "
                    + "SELECT e.BankId, f.IntegrationFlowId, e.IntegrationEventId, 'EVENTS', "
                    + "'event:' || e.IntegrationEventId, 'PENDING', now() + interval '1 hour', now() "
                    + "FROM IntegrationEvent e JOIN IntegrationFlow f ON f.BankId = e.BankId "
                    + "AND f.Name = 'hk-hub' WHERE e.EntityId = 'needed'");
            // Idempotency keys: per bank one expired, one live.
            sql.execute("INSERT INTO ApiClient (BankId, Name, Kind, AuthMethod, ClientId, Scopes, "
                    + "CreatedAt) VALUES (880, 'hk', 'SYSTEM', 'OAUTH2_CLIENT_CREDENTIALS', "
                    + "'hk-client-880', 'jobs:write', now()), (881, 'hk', 'SYSTEM', "
                    + "'OAUTH2_CLIENT_CREDENTIALS', 'hk-client-881', 'jobs:write', now()) "
                    + "ON CONFLICT DO NOTHING");
            sql.execute("INSERT INTO IdempotencyKey (BankId, ApiClientId, IdemKey, RequestHash, "
                    + "CreatedAt, ExpiresAt) SELECT c.BankId, c.ApiClientId, k, repeat('0', 64), "
                    + "now() - interval '1 day', now() + a FROM ApiClient c CROSS JOIN (VALUES "
                    + "('expired', interval '-1 hour'), ('live', interval '23 hours')) AS t(k, a) "
                    + "WHERE c.ClientId LIKE 'hk-client-%' ON CONFLICT DO NOTHING");
        }
    }

    @Test
    void underRlsEachBanksOldRowsGoAndTheOtherBanksStay() throws Exception {
        // RLS on its own: as bank A, bank B's rows are invisible even by its id.
        Instant cutoff = Instant.now().minus(Duration.ofDays(90));
        assertThat(TenantContext.runAs(BANK_A, () -> purger.terminalDeliveries(BANK_B, cutoff)))
                .isZero();

        BankHousekeepingSummary summary = TenantContext.runAs(BANK_A, () ->
                job.housekeepBank(BANK_A, Instant.now(), 0));

        assertThat(summary.refreshTokens()).isEqualTo(1);
        assertThat(summary.outboundDeliveries()).isEqualTo(1);
        assertThat(summary.integrationRuns()).isEqualTo(2);
        assertThat(summary.integrationRunEvents()).isEqualTo(2);
        assertThat(summary.integrationEvents()).isEqualTo(1);
        assertThat(summary.idempotencyKeys()).isEqualTo(1);

        // Bank A keeps exactly the young and the still-needed rows.
        assertThat(count("SELECT count(*) FROM RefreshToken WHERE UserId = 9880")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM IntegrationRun WHERE BankId = 880")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM IntegrationRunEvent WHERE BankId = 880")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM IntegrationEvent WHERE BankId = 880")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM OutboundDelivery WHERE BankId = 880")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM IdempotencyKey WHERE BankId = 880")).isEqualTo(1);
        // Bank B untouched.
        assertThat(count("SELECT count(*) FROM RefreshToken WHERE UserId = 9881")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM IntegrationRun WHERE BankId = 881")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM IntegrationEvent WHERE BankId = 881")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM OutboundDelivery WHERE BankId = 881")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM IdempotencyKey WHERE BankId = 881")).isEqualTo(2);
        // The evidence row, in bank A's tenant.
        assertThat(count("SELECT count(*) FROM AuditEvent WHERE BankId = 880 "
                + "AND Action = 'HOUSEKEEPING_COMPLETED' AND Outcome = 'SUCCESS' "
                + "AND (NewValues ->> 'integrationRuns')::int = 2")).isEqualTo(1);
    }

    @Test
    void theRevokedTokenSweepRunsAsSystem() throws Exception {
        int deleted = TenantContext.runAsSystem(() -> purger.revokedTokens(Instant.now()));

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT count(*) FROM RevokedToken "
                + "WHERE Jti = '00000000-0000-0000-0000-000000000881'")).isZero();
        assertThat(count("SELECT count(*) FROM RevokedToken "
                + "WHERE Jti = '00000000-0000-0000-0000-000000000882'")).isEqualTo(1);
    }

    /** Counted as the owner (bypasses RLS): the truth on disk. */
    private static long count(String query) throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement sql = owner.createStatement();
             ResultSet rows = sql.executeQuery(query)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
