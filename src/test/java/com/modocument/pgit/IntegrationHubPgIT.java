package com.modocument.pgit;

import com.modocument.application.port.TenantContext;
import com.modocument.application.port.integration.IntegrationEventLog;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.model.integration.FlowEvent;
import com.modocument.infrastructure.integration.engine.IntegrationScheduler;
import com.modocument.infrastructure.integration.engine.OutboundDispatcher;
import com.modocument.infrastructure.persistence.IntegrationFlowJpaRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ws25 integration hub on the REAL PostgreSQL schema with RLS enforced
 * (app booted on {@code modocument_app}):
 * <ul>
 *   <li>the cross-task run lock: two schedulers racing for one (flow, slot)
 *       create exactly one run, and a raw duplicate insert violates
 *       {@code uq_integrationrun_flowid_scheduledfor};</li>
 *   <li>the outbox claim uses {@code FOR UPDATE SKIP LOCKED}: two tasks
 *       claiming at once never get the same delivery;</li>
 *   <li>the outbox is transactional: an event published in a transaction
 *       that rolls back leaves neither the event nor its deliveries;</li>
 *   <li>every new table is tenant-isolated: bank A sees none of bank B's
 *       connections, flows, runs, run log, deliveries or events and cannot
 *       write rows for bank B.</li>
 * </ul>
 * The scheduled tick is off here: the test drives the engine itself.
 */
@TestPropertySource(properties = "modocument.integration.engine.scheduler-enabled=false")
class IntegrationHubPgIT extends PgSpringItSupport {

    private static final int BANK_A = 8250;
    private static final int BANK_B = 8350;
    private static final List<String> TABLES = List.of("IntegrationConnection", "IntegrationFlow",
            "IntegrationRun", "IntegrationRunEvent", "OutboundDelivery", "IntegrationEvent");

    private static long flowA;
    private static long eventFlowA;
    private static boolean seeded;

    @Autowired
    private IntegrationScheduler scheduler;
    @Autowired
    private OutboundDispatcher dispatcher;
    @Autowired
    private IntegrationEventLog eventLog;
    @Autowired
    private IntegrationFlowJpaRepository flows;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    static void seedAsOwner() throws Exception {
        if (seeded) {
            return;
        }
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("INSERT INTO Bank (BankId, Code, Name) VALUES (" + BANK_A
                    + ", 'IH_A', 'Integration A'), (" + BANK_B + ", 'IH_B', 'Integration B') "
                    + "ON CONFLICT DO NOTHING");
            for (int bank : new int[] {BANK_A, BANK_B}) {
                long connection = insertReturningId(owner, "INSERT INTO IntegrationConnection (BankId, Name, "
                        + "Type, Mode, ConfigJson, AuthType, CreatedBy) VALUES (" + bank + ", 'hook', 'WEBHOOK', "
                        + "'DIRECT', '{\"url\":\"https://bank.example.com/hook\"}', 'HMAC_SIGNATURE', 1) "
                        + "RETURNING IntegrationConnectionId");
                long flow = insertReturningId(owner, "INSERT INTO IntegrationFlow (BankId, Name, ConnectionId, "
                        + "Direction, TriggerType, CronExpression, PayloadKinds, Enabled, NextRunAt, CreatedBy) "
                        + "VALUES (" + bank + ", 'scheduled', " + connection + ", 'INBOUND', 'MANUAL', "
                        + "'0 0 * * * *', '', false, now() - interval '1 minute', 1) RETURNING IntegrationFlowId");
                long eventFlow = insertReturningId(owner, "INSERT INTO IntegrationFlow (BankId, Name, "
                        + "ConnectionId, Direction, TriggerType, Events, PayloadKinds, Enabled, CreatedBy) VALUES ("
                        + bank + ", 'events', " + connection + ", 'OUTBOUND', 'EVENT', 'INGESTION_FAILED', "
                        + "'JOB_STATUS', true, 1) RETURNING IntegrationFlowId");
                long run = insertReturningId(owner, "INSERT INTO IntegrationRun (BankId, FlowId, ConnectionId, "
                        + "Operation, TriggerType, ScheduledFor, Status) VALUES (" + bank + ", " + flow + ", "
                        + connection + ", 'READ', 'MANUAL', now() - interval '1 day', 'SUCCEEDED') "
                        + "RETURNING IntegrationRunId");
                statement.execute("INSERT INTO IntegrationRunEvent (BankId, RunId, At, Level, Message) VALUES ("
                        + bank + ", " + run + ", now(), 'INFO', 'seeded')");
                statement.execute("INSERT INTO IntegrationEvent (BankId, Type, EntityType, EntityId, OccurredAt) "
                        + "VALUES (" + bank + ", 'DOC_RELEASED', 'DocInstance', '1', now())");
                statement.execute("INSERT INTO OutboundDelivery (BankId, FlowId, RunId, PayloadKind, PayloadRef, "
                        + "Status) VALUES (" + bank + ", " + eventFlow + ", " + run + ", 'EVENTS', 'event:1', "
                        + "'DELIVERED')");
                if (bank == BANK_A) {
                    flowA = flow;
                    eventFlowA = eventFlow;
                }
            }
        }
        seeded = true;
    }

    private static long insertReturningId(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void twoSchedulersRacingForOneSlotCreateExactlyOneRun() throws Exception {
        Instant slot = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(30);
        IntegrationFlow flow = TenantContext.runAs(BANK_A, () -> {
            IntegrationFlow current = flows.findByIdAndBankId(flowA, BANK_A).orElseThrow();
            current.setNextRunAt(slot);
            return flows.save(current);
        });
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        Callable<Long> task = () -> {
            start.await();
            return TenantContext.runAs(BANK_A, () -> scheduler.scheduleSlot(flow, Instant.now()));
        };
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(task));
        }
        start.countDown();
        List<Long> won = new ArrayList<>();
        for (Future<Long> future : futures) {
            Long id = future.get();
            if (id != null) {
                won.add(id);
            }
        }
        pool.shutdown();
        assertThat(won).hasSize(1);
        try (Connection owner = ownerDataSource().getConnection();
             PreparedStatement statement = owner.prepareStatement(
                     "SELECT COUNT(*) FROM IntegrationRun WHERE FlowId = ? AND ScheduledFor = ?")) {
            statement.setLong(1, flowA);
            statement.setObject(2, java.time.OffsetDateTime.ofInstant(slot, java.time.ZoneOffset.UTC));
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void aDuplicateSlotInsertViolatesTheRunLock() throws Exception {
        try (Connection app = appDataSource().getConnection()) {
            setTenant(app, String.valueOf(BANK_A));
            String insert = "INSERT INTO IntegrationRun (BankId, FlowId, ConnectionId, Operation, TriggerType, "
                    + "ScheduledFor, Status) SELECT BankId, IntegrationFlowId, ConnectionId, 'READ', 'SCHEDULE', "
                    + "TIMESTAMPTZ '2030-01-01 00:00:00+00', 'QUEUED' FROM IntegrationFlow WHERE IntegrationFlowId = "
                    + flowA;
            try (Statement statement = app.createStatement()) {
                statement.execute(insert);
                assertThatThrownBy(() -> statement.execute(insert))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("uq_integrationrun_flowid_scheduledfor");
            }
        }
    }

    @Test
    void concurrentClaimsNeverShareADelivery() throws Exception {
        try (Connection owner = ownerDataSource().getConnection(); Statement statement = owner.createStatement()) {
            for (int i = 0; i < 20; i++) {
                statement.execute("INSERT INTO OutboundDelivery (BankId, FlowId, PayloadKind, PayloadRef, Status, "
                        + "NextAttemptAt) VALUES (" + BANK_A + ", " + eventFlowA + ", 'JOB_STATUS', 'job:" + (900 + i)
                        + "', 'PENDING', now() - interval '1 minute')");
            }
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<List<Long>> claim = () -> {
            start.await();
            return TenantContext.runAs(BANK_A, () -> dispatcher.claim(BANK_A));
        };
        Future<List<Long>> first = pool.submit(claim);
        Future<List<Long>> second = pool.submit(claim);
        start.countDown();
        List<Long> a = first.get();
        List<Long> b = second.get();
        pool.shutdown();
        Set<Long> overlap = new HashSet<>(a);
        overlap.retainAll(b);
        assertThat(overlap).isEmpty();
        assertThat(a.size() + b.size()).isGreaterThanOrEqualTo(20);
        try (Connection owner = ownerDataSource().getConnection(); Statement statement = owner.createStatement()) {
            statement.execute("DELETE FROM OutboundDelivery WHERE PayloadRef LIKE 'job:9%' AND BankId = " + BANK_A);
        }
    }

    @Test
    void aRolledBackStateChangeLeavesNoEventAndNoDelivery() throws Exception {
        int before = count("IntegrationEvent", BANK_A) + count("OutboundDelivery", BANK_A);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        TenantContext.runAs(BANK_A, () -> tx.executeWithoutResult(status -> {
            eventLog.publish(BANK_A, FlowEvent.INGESTION_FAILED, "IngestionJob", "77",
                    Map.of("jobId", 77));
            status.setRollbackOnly();
        }));
        assertThat(count("IntegrationEvent", BANK_A) + count("OutboundDelivery", BANK_A)).isEqualTo(before);

        TenantContext.runAs(BANK_A, () -> tx.executeWithoutResult(status ->
                eventLog.publish(BANK_A, FlowEvent.INGESTION_FAILED, "IngestionJob", "78", Map.of("jobId", 78))));
        // One event + one JOB_STATUS delivery for the subscribed enabled EVENT flow.
        assertThat(count("IntegrationEvent", BANK_A) + count("OutboundDelivery", BANK_A)).isEqualTo(before + 2);
    }

    @Test
    void everyIntegrationTableIsTenantIsolated() throws Exception {
        try (Connection app = appDataSource().getConnection()) {
            app.setAutoCommit(false);
            setTenant(app, String.valueOf(BANK_A));
            for (String table : TABLES) {
                try (Statement statement = app.createStatement();
                     ResultSet rs = statement.executeQuery("SELECT COUNT(*), COUNT(*) FILTER (WHERE BankId <> "
                             + BANK_A + ") FROM " + table)) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("%s visible to bank A", table).isPositive();
                    assertThat(rs.getInt(2)).as("%s rows of other banks", table).isZero();
                }
            }
            assertThatThrownBy(() -> {
                try (Statement statement = app.createStatement()) {
                    statement.execute("INSERT INTO IntegrationEvent (BankId, Type, EntityType, EntityId, "
                            + "OccurredAt) VALUES (" + BANK_B + ", 'DOC_RELEASED', 'DocInstance', '9', now())");
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
            app.rollback();
            setTenant(app, "");
            for (String table : TABLES) {
                try (Statement statement = app.createStatement();
                     ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("%s without a tenant", table).isZero();
                }
            }
            app.rollback();
        }
    }

    private static int count(String table, int bankId) throws SQLException {
        try (Connection owner = ownerDataSource().getConnection(); Statement statement = owner.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE BankId = " + bankId)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void setTenant(Connection connection, String tenant) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.tenant', ?, " + !connection.getAutoCommit() + ")")) {
            statement.setString(1, tenant);
            statement.executeQuery().close();
        }
    }
}
