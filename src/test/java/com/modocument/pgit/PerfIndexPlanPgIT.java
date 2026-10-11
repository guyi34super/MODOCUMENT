package com.modocument.pgit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ws27 (V270): the planner really uses the new indexes for the hot queries,
 * on the migrated schema, as the RLS-subject runtime role. Each table is
 * seeded with enough history (tens of thousands of rows, mostly old or
 * finished — the production shape) and ANALYZEd, then the query is
 * EXPLAINed with the same predicates the application sends:
 * <ul>
 *   <li>the minute alert sweep's window read of the wildcard
 *       {@code admin-action-burst} rule (AuditWindowReader, as SYSTEM) →
 *       {@code ix_auditevent_occurredat};</li>
 *   <li>a bank-scoped audit read by action and date →
 *       {@code ix_auditevent_bankid_action_occurredat};</li>
 *   <li>the admin dashboard's "runs ended in the last 24 h" count →
 *       {@code ix_integrationrun_bankid_finishedat};</li>
 *   <li>the integration tick's cross-bank scans (as SYSTEM): banks with a
 *       PENDING delivery → {@code ix_outbounddelivery_pending_next}, banks
 *       with an unfinished run → {@code ix_integrationrun_active_status}.</li>
 * </ul>
 * The plans are printed, so the CI log carries the evidence.
 */
class PerfIndexPlanPgIT extends PgItSupport {

    private static final int BANK = 870;
    private static final int OTHER_BANK = 871;

    @BeforeAll
    static void seedHistoryAndAnalyze() throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement sql = owner.createStatement()) {
            sql.execute("INSERT INTO Bank (BankId, Code, Name) VALUES "
                    + "(870, 'PERF_A', 'Perf Bank A'), (871, 'PERF_B', 'Perf Bank B') "
                    + "ON CONFLICT DO NOTHING");
            // 120 000 audit rows over 60 days, two banks, six frequent actions and
            // a rare one (as USER_DISABLED is next to LOGIN_SUCCEEDED). ActorRole
            // READ_ONLY: no shipped alert rule matches them (a shared database).
            sql.execute("INSERT INTO AuditEvent (AuditEventId, OccurredAt, BankId, ActorRole, "
                    + "Action, Outcome) SELECT gen_random_uuid(), "
                    + "now() - (g * interval '43 seconds'), CASE WHEN g % 2 = 0 THEN 870 ELSE 871 END, "
                    + "'READ_ONLY', CASE WHEN g % 1000 = 0 THEN 'PERF_RARE' ELSE 'PERF_ACTION_' || (g % 6) END, "
                    + "'SUCCESS' "
                    + "FROM generate_series(1, 120000) g");
            sql.execute("INSERT INTO IntegrationConnection (BankId, Name, Type, Mode, CreatedBy) "
                    + "VALUES (870, 'perf-hub', 'REST_API', 'DIRECT', 1), "
                    + "(871, 'perf-hub', 'REST_API', 'DIRECT', 1) ON CONFLICT DO NOTHING");
            sql.execute("INSERT INTO IntegrationFlow (BankId, Name, ConnectionId, Direction, "
                    + "TriggerType, CreatedBy) SELECT BankId, Name, IntegrationConnectionId, "
                    + "'OUTBOUND', 'MANUAL', CreatedBy FROM IntegrationConnection "
                    + "WHERE Name = 'perf-hub' ON CONFLICT DO NOTHING");
            // 40 000 runs per bank over 200 days, all finished but 3 per bank.
            sql.execute("INSERT INTO IntegrationRun (BankId, FlowId, ConnectionId, Operation, "
                    + "TriggerType, ScheduledFor, Status, CreatedAt, FinishedAt) "
                    + "SELECT f.BankId, f.IntegrationFlowId, f.ConnectionId, 'WRITE', 'MANUAL', "
                    + "now() - (g * interval '7 minutes'), "
                    + "CASE WHEN g <= 3 THEN 'RUNNING' WHEN g % 10 = 0 THEN 'FAILED' "
                    + "ELSE 'SUCCEEDED' END, now() - (g * interval '7 minutes'), "
                    + "CASE WHEN g <= 3 THEN NULL ELSE now() - (g * interval '7 minutes') END "
                    + "FROM IntegrationFlow f CROSS JOIN generate_series(1, 40000) g "
                    + "WHERE f.Name = 'perf-hub'");
            // 40 000 outbox rows per bank, all delivered or dead but 5 per bank.
            sql.execute("INSERT INTO OutboundDelivery (BankId, FlowId, PayloadKind, PayloadRef, "
                    + "Status, NextAttemptAt, CreatedAt) "
                    + "SELECT f.BankId, f.IntegrationFlowId, 'JOB_STATUS', 'job:' || g, "
                    + "CASE WHEN g <= 5 THEN 'PENDING' WHEN g % 20 = 0 THEN 'DEAD' "
                    + "ELSE 'DELIVERED' END, "
                    + "CASE WHEN g <= 5 THEN now() + interval '1 minute' END, "
                    + "now() - (g * interval '7 minutes') "
                    + "FROM IntegrationFlow f CROSS JOIN generate_series(1, 40000) g "
                    + "WHERE f.Name = 'perf-hub'");
            sql.execute("ANALYZE AuditEvent");
            sql.execute("ANALYZE IntegrationRun");
            sql.execute("ANALYZE OutboundDelivery");
        }
    }

    @Test
    void theAlertSweepWindowReadUsesTheOccurredAtIndex() throws Exception {
        // AuditWindowReader for admin-action-burst: any action but ALERT_*,
        // role ADMIN, SUCCESS, the 10-minute window, newest first, capped.
        String plan = explain("*", "SELECT e.AuditEventId, e.OccurredAt, e.BankId, e.Action, "
                + "e.Outcome, e.ActorUserId, e.ActorEmail, e.ActorRole, e.Ip, e.EntityType, "
                + "e.EntityId FROM AuditEvent e "
                + "WHERE e.OccurredAt >= now() - interval '10 minutes' AND e.OccurredAt <= now() "
                + "AND e.Action NOT LIKE 'ALERT!_%' ESCAPE '!' AND e.Outcome IN ('SUCCESS') "
                + "AND e.ActorRole IN ('ADMIN') ORDER BY e.OccurredAt DESC "
                + "FETCH FIRST 10000 ROWS ONLY");

        assertUsesIndex(plan, "ix_auditevent_occurredat");
    }

    @Test
    void aBankScopedAuditReadByActionUsesTheBankActionIndex() throws Exception {
        String plan = explain(String.valueOf(BANK), "SELECT e.AuditEventId, e.OccurredAt "
                + "FROM AuditEvent e WHERE e.BankId = 870 AND e.Action = 'PERF_RARE' "
                + "AND e.OccurredAt >= now() - interval '30 days' "
                + "ORDER BY e.OccurredAt DESC FETCH FIRST 50 ROWS ONLY");

        assertUsesIndex(plan, "ix_auditevent_bankid_action_occurredat");
    }

    @Test
    void theDashboardRunCountUsesTheFinishedAtIndex() throws Exception {
        // IntegrationRunJpaRepository#countByBankIdAndStatusAndFinishedAtGreaterThanEqual
        String plan = explain(String.valueOf(BANK), "SELECT count(r.IntegrationRunId) "
                + "FROM IntegrationRun r WHERE r.BankId = 870 AND r.Status = 'SUCCEEDED' "
                + "AND r.FinishedAt >= now() - interval '24 hours'");

        assertUsesIndex(plan, "ix_integrationrun_bankid_finishedat");
    }

    @Test
    void theTickScansUseThePartialIndexes() throws Exception {
        // OutboundDeliveryJpaRepository#findBankIdsWithPending (as SYSTEM)
        String pending = explain("*", "SELECT DISTINCT d.BankId FROM OutboundDelivery d "
                + "WHERE d.Status = 'PENDING' AND d.NextAttemptAt IS NOT NULL");
        assertUsesIndex(pending, "ix_outbounddelivery_pending_next");

        // IntegrationRunJpaRepository#findBankIdsWithRunsIn(ACTIVE) (as SYSTEM)
        String active = explain("*", "SELECT DISTINCT r.BankId FROM IntegrationRun r "
                + "WHERE r.Status IN ('QUEUED', 'RUNNING', 'AWAITING_AGENT', 'RUNNING_AGENT')");
        assertUsesIndex(active, "ix_integrationrun_active_status");

        // The per-bank run-timeout sweep (IntegrationRunJpaRepository#findStale).
        String stale = explain(String.valueOf(OTHER_BANK), "SELECT r.IntegrationRunId "
                + "FROM IntegrationRun r WHERE r.BankId = 871 AND r.Status IN ('QUEUED', "
                + "'RUNNING', 'AWAITING_AGENT', 'RUNNING_AGENT') "
                + "AND COALESCE(r.StartedAt, r.CreatedAt) < now() - interval '15 minutes'");
        assertUsesIndex(stale, "ix_integrationrun_active_status");
    }

    /** EXPLAIN as the runtime role (row level security on) under {@code app.tenant}. */
    private static String explain(String tenant, String query) throws Exception {
        List<String> lines = new ArrayList<>();
        try (Connection app = appDataSource().getConnection();
             Statement sql = app.createStatement()) {
            sql.execute("SET app.tenant = '" + tenant + "'");
            try (ResultSet plan = sql.executeQuery("EXPLAIN " + query)) {
                while (plan.next()) {
                    lines.add(plan.getString(1));
                }
            }
        }
        String text = String.join("\n", lines);
        System.out.println("[PerfIndexPlanPgIT] tenant=" + tenant + "\n" + query + "\n" + text);
        return text;
    }

    private static void assertUsesIndex(String plan, String index) {
        assertThat(plan).as("plan uses %s", index)
                .containsIgnoringCase(index)
                .containsAnyOf("Index Scan", "Index Only Scan", "Bitmap Index Scan");
        assertThat(plan).as("no sequential scan").doesNotContain("Seq Scan");
    }
}
