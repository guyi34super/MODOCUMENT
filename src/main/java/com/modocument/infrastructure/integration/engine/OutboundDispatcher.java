package com.modocument.infrastructure.integration.engine;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.IntegrationConnector;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.WriteResult;
import com.modocument.domain.entity.IntegrationConnection;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.entity.IntegrationRun;
import com.modocument.domain.entity.OutboundDelivery;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Hashing;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.DeliveryStatus;
import com.modocument.domain.model.integration.FlowTrigger;
import com.modocument.domain.model.integration.RunStatus;
import com.modocument.infrastructure.integration.connector.ConnectorErrors;
import com.modocument.infrastructure.persistence.IntegrationConnectionJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationFlowJpaRepository;
import com.modocument.infrastructure.persistence.OutboundDeliveryJpaRepository;
import com.modocument.infrastructure.settings.IntegrationSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sends the outbox (ws25). Per bank (called by the scheduler tick inside
 * {@code runAs(bank)}, or right after an outbound run queued its
 * deliveries):
 * <ol>
 *   <li><b>claim</b> due PENDING deliveries — {@code FOR UPDATE SKIP LOCKED}
 *       on PostgreSQL, so several tasks never send the same row (a plain
 *       {@code FOR UPDATE} on H2) — and hide them for a lease;</li>
 *   <li>an EVENT delivery gets its run on its first dispatch; an AGENT
 *       connection parks it for the agent (run AWAITING_AGENT, no
 *       NextAttemptAt);</li>
 *   <li><b>build</b> the payload ({@link PayloadBuilder}: masked, mapped,
 *       capped) and fix the idempotency key at the first attempt:
 *       {@code sha256(runId + ":" + batch hash)} — every retry reuses it;</li>
 *   <li><b>write</b> through the connector in chunks of the target's
 *       {@code batchSize} (each chunk its own transaction/request, key
 *       {@code <key>-<n>});</li>
 *   <li>success → DELIVERED (+ EXPORT_RUN); failure → retry after
 *       {@code base × 2^(attempts-1)} (capped), and DEAD once the bank's
 *       {@code integration.webhookRetryAttempts} retries are spent
 *       ({@code INTEGRATION_DELIVERY_DEAD}); a payload that can never be
 *       built (caps, deleted job) is DEAD at once;</li>
 *   <li>the run settles when it has no PENDING delivery left: SUCCEEDED, or
 *       FAILED when any delivery is DEAD.</li>
 * </ol>
 */
@Component
public class OutboundDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboundDispatcher.class);

    private final OutboundDeliveryJpaRepository deliveries;
    private final IntegrationFlowJpaRepository flows;
    private final IntegrationConnectionJpaRepository connections;
    private final IntegrationConnectors connectors;
    private final PayloadBuilder payloads;
    private final IntegrationRuns runs;
    private final IntegrationSettings settings;
    private final AuditRecorder audit;
    private final IntegrationEngineProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private volatile Boolean postgres;

    public OutboundDispatcher(OutboundDeliveryJpaRepository deliveries, IntegrationFlowJpaRepository flows,
                              IntegrationConnectionJpaRepository connections,
                              IntegrationConnectors connectors, PayloadBuilder payloads,
                              IntegrationRuns runs, IntegrationSettings settings, AuditRecorder audit,
                              IntegrationEngineProperties properties, JdbcTemplate jdbc,
                              PlatformTransactionManager transactionManager) {
        this.deliveries = deliveries;
        this.flows = flows;
        this.connections = connections;
        this.connectors = connectors;
        this.payloads = payloads;
        this.runs = runs;
        this.settings = settings;
        this.audit = audit;
        this.properties = properties;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = Clock.systemUTC();
    }

    /** Claims and sends the bank's due deliveries; returns how many were attempted. */
    public int dispatchDue(int bankId) {
        List<Long> claimed = claim(bankId);
        // ws28: one flow/connection cache for the whole tick — a batch of
        // deliveries of the same flow loads it once, not once per delivery.
        TickCache cache = new TickCache(bankId);
        for (Long id : claimed) {
            dispatch(bankId, id, cache);
        }
        return claimed.size();
    }

    /** Sends the PENDING deliveries of one run now (an outbound run that just queued them). */
    public void dispatchRun(int bankId, long runId) {
        TickCache cache = new TickCache(bankId);
        for (OutboundDelivery delivery : deliveries.findByBankIdAndRunIdAndStatusOrderByIdAsc(
                bankId, runId, DeliveryStatus.PENDING)) {
            dispatch(bankId, delivery.getId(), cache);
        }
        settleRun(bankId, runId);
    }

    /**
     * Claims up to {@code dispatchBatchSize} due deliveries for a lease and
     * returns their ids in due order. ws28: ONE statement on PostgreSQL —
     * {@code UPDATE … FROM (SELECT … FOR UPDATE SKIP LOCKED LIMIT n) RETURNING}
     * — so concurrent tasks never claim the same row and the claim costs one
     * round trip; on H2 (tests) a {@code SELECT … FOR UPDATE} and one batched
     * {@code UPDATE … WHERE id IN (…)} in the same transaction.
     */
    public List<Long> claim(int bankId) {
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(properties.getDispatchLease());
        int limit = Math.max(1, properties.getDispatchBatchSize());
        java.time.OffsetDateTime dueBy = java.time.OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC);
        java.time.OffsetDateTime lease = java.time.OffsetDateTime.ofInstant(leaseUntil, java.time.ZoneOffset.UTC);
        List<Long> ids = tx.execute(status -> isPostgres()
                ? claimPostgres(bankId, dueBy, lease, limit)
                : claimPortable(bankId, dueBy, lease, limit));
        return ids == null ? List.of() : ids;
    }

    /** PostgreSQL: lock, lease and return the due rows in one statement. */
    private List<Long> claimPostgres(int bankId, java.time.OffsetDateTime dueBy,
                                     java.time.OffsetDateTime lease, int limit) {
        List<Object[]> rows = jdbc.query("WITH due AS (SELECT OutboundDeliveryId, NextAttemptAt "
                        + "FROM OutboundDelivery WHERE BankId = ? AND Status = 'PENDING' "
                        + "AND NextAttemptAt IS NOT NULL AND NextAttemptAt <= ? "
                        + "ORDER BY NextAttemptAt, OutboundDeliveryId LIMIT " + limit
                        + " FOR UPDATE SKIP LOCKED) "
                        + "UPDATE OutboundDelivery o SET NextAttemptAt = ? FROM due "
                        + "WHERE o.OutboundDeliveryId = due.OutboundDeliveryId "
                        + "RETURNING o.OutboundDeliveryId, due.NextAttemptAt",
                (rs, rowNum) -> new Object[]{rs.getLong(1), rs.getTimestamp(2)},
                bankId, dueBy, lease);
        // RETURNING has no order: put the claim back into due order.
        return rows.stream()
                .sorted(java.util.Comparator.<Object[], java.sql.Timestamp>comparing(
                                row -> (java.sql.Timestamp) row[1])
                        .thenComparingLong(row -> (Long) row[0]))
                .map(row -> (Long) row[0])
                .toList();
    }

    /** H2 and other databases: select for update, then one batched lease update. */
    private List<Long> claimPortable(int bankId, java.time.OffsetDateTime dueBy,
                                     java.time.OffsetDateTime lease, int limit) {
        List<Long> found = jdbc.queryForList("SELECT OutboundDeliveryId FROM OutboundDelivery "
                + "WHERE BankId = ? AND Status = 'PENDING' AND NextAttemptAt IS NOT NULL "
                + "AND NextAttemptAt <= ? ORDER BY NextAttemptAt, OutboundDeliveryId "
                + "LIMIT " + limit + " FOR UPDATE", Long.class, bankId, dueBy);
        if (!found.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(found.size(), "?"));
            List<Object> args = new ArrayList<>(found.size() + 1);
            args.add(lease);
            args.addAll(found);
            jdbc.update("UPDATE OutboundDelivery SET NextAttemptAt = ? WHERE OutboundDeliveryId IN ("
                    + placeholders + ")", args.toArray());
        }
        return found;
    }

    /**
     * ws28: the flows and connections one tick touches, loaded at most once
     * each (a tick takes seconds; a flow edited meanwhile is picked up by the
     * next tick, as an in-flight delivery always did).
     */
    private final class TickCache {

        private final int bankId;
        private final Map<Long, Optional<IntegrationFlow>> flowsById = new java.util.HashMap<>();
        private final Map<Long, Optional<IntegrationConnection>> connectionsById = new java.util.HashMap<>();

        TickCache(int bankId) {
            this.bankId = bankId;
        }

        IntegrationFlow flow(long flowId) {
            return flowsById.computeIfAbsent(flowId, id -> flows.findByIdAndBankId(id, bankId)).orElse(null);
        }

        IntegrationConnection connection(long connectionId) {
            return connectionsById.computeIfAbsent(connectionId,
                    id -> connections.findByIdAndBankId(id, bankId)).orElse(null);
        }
    }

    private boolean isPostgres() {
        Boolean known = postgres;
        if (known == null) {
            known = Boolean.TRUE.equals(jdbc.execute((java.sql.Connection c) ->
                    "PostgreSQL".equalsIgnoreCase(c.getMetaData().getDatabaseProductName())));
            postgres = known;
        }
        return known;
    }

    /** One delivery attempt; never throws (failures are recorded on the row). */
    public void dispatch(int bankId, long deliveryId) {
        dispatch(bankId, deliveryId, new TickCache(bankId));
    }

    private void dispatch(int bankId, long deliveryId, TickCache cache) {
        Optional<OutboundDelivery> found = deliveries.findByIdAndBankId(deliveryId, bankId);
        if (found.isEmpty() || found.get().getStatus() != DeliveryStatus.PENDING) {
            return;
        }
        OutboundDelivery delivery = found.get();
        IntegrationFlow flow = cache.flow(delivery.getFlowId());
        IntegrationConnection connection = flow == null ? null : cache.connection(flow.getConnectionId());
        if (flow == null || connection == null) {
            markDead(delivery, flow, "The flow or its connection no longer exists.");
            return;
        }
        if (delivery.getRunId() == null) {
            IntegrationRun run = runs.tryCreate(flow, IntegrationRun.OP_WRITE, FlowTrigger.EVENT,
                    runs.uniqueNow(), null);
            if (run == null) {
                return; // slot collision: next tick
            }
            runs.start(bankId, run.getId(), connection.getMode() == ConnectionMode.AGENT
                    ? RunStatus.AWAITING_AGENT : RunStatus.RUNNING);
            delivery.setRunId(run.getId());
            OutboundDelivery current = delivery;
            // ws28: the save returns the stored row — no second read for it.
            delivery = tx.execute(status -> deliveries.save(current));
        }
        long runId = delivery.getRunId();
        if (connection.getMode() == ConnectionMode.AGENT) {
            parkForAgent(delivery);
            runs.update(bankId, runId, run -> {
                if (run.getStatus() == RunStatus.RUNNING || run.getStatus() == RunStatus.QUEUED) {
                    run.setStatus(RunStatus.AWAITING_AGENT);
                }
            });
            return;
        }

        OutboundBatch batch;
        try {
            batch = payloads.build(flow, delivery, connection.getType() == ConnectionType.SFTP);
        } catch (PayloadBuilder.PayloadRefusedException refused) {
            markDead(delivery, flow, refused.getMessage());
            settleRun(bankId, runId);
            return;
        } catch (RuntimeException e) {
            recordFailure(delivery, flow, ConnectorErrors.safeMessage(e));
            settleRun(bankId, runId);
            return;
        }
        String key = idempotencyKey(delivery, batch);
        try {
            ConnectionContext context = connectors.context(connection, true);
            IntegrationConnector connector = connectors.forType(connection.getType());
            Map<String, Object> target = IntegrationJson.spec(flow.getTargetSpecJson());
            int written = 0;
            String reference = null;
            List<OutboundBatch> chunks = chunks(batch, key, batchSize(connection.getType(), target));
            for (OutboundBatch chunk : chunks) {
                WriteResult result = connector.write(context, target, chunk);
                written += result.recordsWritten();
                reference = result.remoteReference();
            }
            markDelivered(delivery, flow, batch, written, reference);
        } catch (Exception e) {
            recordFailure(delivery, flow, ConnectorErrors.safeMessage(e));
        }
        settleRun(bankId, runId);
    }

    /** The batch hash fixes the key at the first attempt; retries reuse the stored key. */
    String idempotencyKey(OutboundDelivery delivery, OutboundBatch batch) {
        if (delivery.getIdempotencyKey() != null) {
            return delivery.getIdempotencyKey();
        }
        String batchHash = batch.fileContent() != null
                ? Hashing.sha256(new String(java.util.Base64.getEncoder().encode(batch.fileContent()),
                StandardCharsets.US_ASCII))
                : Hashing.sha256(IntegrationJson.write(batch.records()));
        String key = Hashing.sha256(delivery.getRunId() + ":" + batchHash).substring(0, 40);
        delivery.setIdempotencyKey(key);
        tx.executeWithoutResult(status -> deliveries.findById(delivery.getId()).ifPresent(row -> {
            row.setIdempotencyKey(key);
            deliveries.save(row);
        }));
        return key;
    }

    /** Records per write call: the agent chunks with the same rule (connector-core). */
    static int batchSize(ConnectionType type, Map<String, Object> target) {
        return com.modocument.connector.core.spi.OutboundBatch.batchSize(
                com.modocument.connector.core.spi.ConnectionType.valueOf(type.name()), target);
    }

    static List<OutboundBatch> chunks(OutboundBatch batch, String key, int size) {
        if (batch.fileContent() != null || batch.records().size() <= size) {
            return List.of(withKey(batch, key, batch.records()));
        }
        List<OutboundBatch> chunks = new ArrayList<>();
        List<Map<String, Object>> records = batch.records();
        for (int from = 0, n = 0; from < records.size(); from += size, n++) {
            chunks.add(withKey(batch, key + "-" + n,
                    records.subList(from, Math.min(records.size(), from + size))));
        }
        return chunks;
    }

    private static OutboundBatch withKey(OutboundBatch batch, String key,
                                         List<Map<String, Object>> records) {
        return new OutboundBatch(key, batch.kind(), batch.format(), records, batch.fileName(),
                batch.contentType(), batch.fileContent());
    }

    // ---- state -----------------------------------------------------------------------

    private void parkForAgent(OutboundDelivery delivery) {
        tx.executeWithoutResult(status -> deliveries.findById(delivery.getId()).ifPresent(row -> {
            row.setNextAttemptAt(null);
            deliveries.save(row);
        }));
    }

    void markDelivered(OutboundDelivery delivery, IntegrationFlow flow, OutboundBatch batch,
                       int written, String reference) {
        markDelivered(delivery, flow, batch == null ? null : PayloadBuilder.ExportFacts.of(batch),
                written, reference);
    }

    /** DELIVERED, plus the EXPORT_RUN row when {@code export} is known (DIRECT and agent). */
    void markDelivered(OutboundDelivery delivery, IntegrationFlow flow,
                       PayloadBuilder.ExportFacts export, int written, String reference) {
        tx.executeWithoutResult(status -> deliveries.findById(delivery.getId()).ifPresent(row -> {
            row.setStatus(DeliveryStatus.DELIVERED);
            row.setAttempts(row.getAttempts() + 1);
            row.setDeliveredAt(clock.instant());
            row.setNextAttemptAt(null);
            row.setLastError(null);
            row.setRemoteReference(reference == null ? null
                    : reference.length() <= 300 ? reference : reference.substring(0, 300));
            deliveries.save(row);
            if (flow != null && export != null) {
                payloads.recordExport(flow, row, export);
            }
        }));
        if (delivery.getRunId() != null) {
            runs.update(delivery.getBankId(), delivery.getRunId(),
                    run -> run.setRecordsOut(run.getRecordsOut() + written));
            runs.log(delivery.getBankId(), delivery.getRunId(), "INFO", "Delivery " + delivery.getId()
                    + " (" + delivery.getPayloadKind() + ") delivered: " + written + " record(s).");
        }
    }

    /** A failed attempt: retry with backoff, or DEAD once the retries are spent. */
    void recordFailure(OutboundDelivery delivery, IntegrationFlow flow, String error) {
        int retries = Math.max(0, settings.webhookRetryAttempts(delivery.getBankId()));
        boolean dead = tx.execute(status -> deliveries.findById(delivery.getId()).map(row -> {
            int attempts = row.getAttempts() + 1;
            row.setAttempts(attempts);
            row.setLastError(clip(error));
            if (attempts > retries) {
                row.setStatus(DeliveryStatus.DEAD);
                row.setNextAttemptAt(null);
                deliveries.save(row);
                return true;
            }
            row.setNextAttemptAt(clock.instant().plus(backoff(attempts)));
            deliveries.save(row);
            return false;
        }).orElse(false));
        if (delivery.getRunId() != null) {
            runs.log(delivery.getBankId(), delivery.getRunId(), dead ? "ERROR" : "WARN",
                    "Delivery " + delivery.getId() + " attempt failed: " + clip(error)
                            + (dead ? " — dead-lettered." : " — will retry."));
        }
        if (dead) {
            auditDead(delivery, flow, error);
        }
    }

    void markDead(OutboundDelivery delivery, IntegrationFlow flow, String error) {
        tx.executeWithoutResult(status -> deliveries.findById(delivery.getId()).ifPresent(row -> {
            row.setStatus(DeliveryStatus.DEAD);
            row.setAttempts(row.getAttempts() + 1);
            row.setNextAttemptAt(null);
            row.setLastError(clip(error));
            deliveries.save(row);
        }));
        if (delivery.getRunId() != null) {
            runs.log(delivery.getBankId(), delivery.getRunId(), "ERROR", "Delivery " + delivery.getId()
                    + " dead-lettered: " + clip(error));
        }
        auditDead(delivery, flow, error);
    }

    private void auditDead(OutboundDelivery delivery, IntegrationFlow flow, String error) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("deliveryId", delivery.getId());
        values.put("flowId", delivery.getFlowId());
        values.put("runId", delivery.getRunId());
        values.put("payloadKind", delivery.getPayloadKind().name());
        values.put("error", clip(error));
        audit.record(AuditEventSpec.builder("INTEGRATION_DELIVERY_DEAD", AuditOutcome.FAILED)
                .entity("OutboundDelivery", String.valueOf(delivery.getId()))
                .newValues(values)
                .build());
        log.warn("Outbound delivery {} of flow {} dead-lettered", delivery.getId(),
                flow == null ? delivery.getFlowId() : flow.getId());
    }

    Duration backoff(int attempts) {
        Duration base = properties.getBaseBackoff();
        Duration max = properties.getMaxBackoff();
        long factor = 1L << Math.min(20, Math.max(0, attempts - 1));
        Duration delay = base.multipliedBy(factor);
        return delay.compareTo(max) > 0 ? max : delay;
    }

    /** SUCCEEDED / FAILED once no PENDING delivery of the run is left. */
    public void settleRun(int bankId, long runId) {
        if (deliveries.countByBankIdAndRunIdAndStatus(bankId, runId, DeliveryStatus.PENDING) > 0) {
            return;
        }
        Optional<IntegrationRun> run = runs.find(bankId, runId);
        if (run.isEmpty() || run.get().getStatus().isTerminal()
                || !IntegrationRun.OP_WRITE.equals(run.get().getOperation())) {
            return;
        }
        long dead = deliveries.countByBankIdAndRunIdAndStatus(bankId, runId, DeliveryStatus.DEAD);
        if (dead > 0) {
            runs.fail(bankId, runId, dead + " delivery(ies) dead-lettered; replay them from the run.");
        } else {
            runs.succeed(bankId, runId, null, null, null, null);
        }
    }

    private static String clip(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }
}
