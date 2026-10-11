package com.modocument.infrastructure.integration.engine;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.entity.IntegrationRun;
import com.modocument.domain.entity.IntegrationRunEvent;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.integration.FlowTrigger;
import com.modocument.domain.model.integration.RunStatus;
import com.modocument.infrastructure.integration.connector.ConnectorErrors;
import com.modocument.infrastructure.persistence.IntegrationFlowJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunEventJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every state change of an {@link IntegrationRun} (ws25), each in its own
 * short transaction (REQUIRES_NEW) so a run's bookkeeping never rides on —
 * or rolls back with — the work it records. Terminal states are final: a
 * run the timeout sweep already failed is never flipped back by a slow
 * worker. Completed runs are audited {@code INTEGRATION_RUN_COMPLETED}
 * (with the outcome), failed ones {@code INTEGRATION_RUN_FAILED} (its own
 * transaction, so it survives).
 */
@Component
public class IntegrationRuns {

    private static final Logger log = LoggerFactory.getLogger(IntegrationRuns.class);

    public static final EnumSet<RunStatus> ACTIVE = EnumSet.of(RunStatus.QUEUED, RunStatus.RUNNING,
            RunStatus.AWAITING_AGENT, RunStatus.RUNNING_AGENT);

    private final IntegrationRunJpaRepository runs;
    private final IntegrationRunEventJpaRepository runEvents;
    private final IntegrationFlowJpaRepository flows;
    private final AuditRecorder audit;
    private final TransactionTemplate tx;
    private final Clock clock = Clock.systemUTC();
    /** Strictly increasing slot stamps for runs created by this task (MANUAL/EVENT). */
    private final AtomicLong lastMicros = new AtomicLong();

    public IntegrationRuns(IntegrationRunJpaRepository runs, IntegrationRunEventJpaRepository runEvents,
                           IntegrationFlowJpaRepository flows, AuditRecorder audit,
                           PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.runEvents = runEvents;
        this.flows = flows;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A unique "now" (microseconds) for a non-scheduled run's ScheduledFor. */
    public Instant uniqueNow() {
        long now = java.time.temporal.ChronoUnit.MICROS.between(Instant.EPOCH, clock.instant());
        long micros = lastMicros.updateAndGet(previous -> Math.max(previous + 1, now));
        return Instant.EPOCH.plusNanos(micros * 1000);
    }

    /** A new run of {@code flow}; null when another task already inserted this (flow, slot). */
    public IntegrationRun tryCreate(IntegrationFlow flow, String operation, FlowTrigger trigger,
                                    Instant scheduledFor, Integer triggeredBy) {
        IntegrationRun run = new IntegrationRun();
        run.setBankId(flow.getBankId());
        run.setFlowId(flow.getId());
        run.setConnectionId(flow.getConnectionId());
        run.setOperation(operation);
        run.setTriggerType(trigger);
        run.setScheduledFor(scheduledFor);
        run.setStatus(RunStatus.QUEUED);
        run.setTriggeredBy(triggeredBy);
        run.setCreatedAt(clock.instant());
        return insert(run);
    }

    /** A connection test routed through the agent (no flow). */
    public IntegrationRun createTest(int bankId, long connectionId, Integer triggeredBy) {
        IntegrationRun run = new IntegrationRun();
        run.setBankId(bankId);
        run.setConnectionId(connectionId);
        run.setOperation(IntegrationRun.OP_TEST);
        run.setTriggerType(FlowTrigger.MANUAL);
        run.setScheduledFor(uniqueNow());
        run.setStatus(RunStatus.AWAITING_AGENT);
        run.setTriggeredBy(triggeredBy);
        run.setCreatedAt(clock.instant());
        return insert(run);
    }

    private IntegrationRun insert(IntegrationRun run) {
        try {
            return tx.execute(status -> runs.saveAndFlush(run));
        } catch (DataIntegrityViolationException lostTheRace) {
            return null;
        }
    }

    public Optional<IntegrationRun> find(int bankId, long runId) {
        return runs.findByIdAndBankId(runId, bankId);
    }

    public void log(int bankId, long runId, String level, String message) {
        try {
            tx.executeWithoutResult(status -> runEvents.save(
                    new IntegrationRunEvent(bankId, runId, level, message)));
        } catch (RuntimeException e) {
            log.warn("Could not write the log of integration run {}: {}", runId,
                    e.getClass().getSimpleName());
        }
    }

    /** QUEUED → {@code next} (RUNNING or AWAITING_AGENT); false when the run moved on. */
    public boolean start(int bankId, long runId, RunStatus next) {
        Boolean started = tx.execute(status -> runs.findByIdAndBankId(runId, bankId)
                .filter(run -> run.getStatus() == RunStatus.QUEUED)
                .map(run -> {
                    run.setStatus(next);
                    if (next == RunStatus.RUNNING) {
                        run.setStartedAt(clock.instant());
                    }
                    runs.save(run);
                    return true;
                })
                .orElse(false));
        return Boolean.TRUE.equals(started);
    }

    /** Applies {@code change} to a non-terminal run in one transaction. */
    public void update(int bankId, long runId, java.util.function.Consumer<IntegrationRun> change) {
        tx.executeWithoutResult(status -> runs.findByIdAndBankId(runId, bankId)
                .filter(run -> !run.getStatus().isTerminal())
                .ifPresent(run -> {
                    change.accept(run);
                    runs.save(run);
                }));
    }

    /**
     * Success: counters, the linked ingestion job, and (inbound DB pulls) the
     * flow's new watermark — all with the audit row, in one transaction.
     */
    public void succeed(int bankId, long runId, Integer recordsIn, Integer recordsOut,
                        Integer ingestionJobId, String flowCursor) {
        tx.executeWithoutResult(status -> runs.findByIdAndBankId(runId, bankId)
                .filter(run -> !run.getStatus().isTerminal())
                .ifPresent(run -> {
                    if (recordsIn != null) {
                        run.setRecordsIn(recordsIn);
                    }
                    if (recordsOut != null) {
                        run.setRecordsOut(recordsOut);
                    }
                    if (ingestionJobId != null) {
                        run.setIngestionJobId(ingestionJobId);
                    }
                    run.setStatus(RunStatus.SUCCEEDED);
                    run.setFinishedAt(clock.instant());
                    run.setStagedRecordsJson(null);
                    if (run.getStartedAt() == null) {
                        run.setStartedAt(run.getFinishedAt());
                    }
                    runs.save(run);
                    if (flowCursor != null && run.getFlowId() != null) {
                        flows.findByIdAndBankId(run.getFlowId(), bankId).ifPresent(flow -> {
                            flow.setCursorValue(flowCursor);
                            flows.save(flow);
                        });
                    }
                    audit.record(AuditEventSpec.builder("INTEGRATION_RUN_COMPLETED", AuditOutcome.SUCCESS)
                            .entity("IntegrationRun", String.valueOf(runId))
                            .newValues(summary(run))
                            .build());
                }));
        log(bankId, runId, "INFO", "Run succeeded.");
    }

    /** Failure with a safe message; audited INTEGRATION_RUN_FAILED (survives any rollback). */
    public void fail(int bankId, long runId, Throwable error) {
        fail(bankId, runId, ConnectorErrors.safeMessage(error));
    }

    public void fail(int bankId, long runId, String message) {
        IntegrationRun failed = tx.execute(status -> runs.findByIdAndBankId(runId, bankId)
                .filter(run -> !run.getStatus().isTerminal())
                .map(run -> {
                    run.setStatus(RunStatus.FAILED);
                    run.setError(message == null ? "Failed." : message.length() <= 2000 ? message
                            : message.substring(0, 2000));
                    run.setFinishedAt(clock.instant());
                    run.setStagedRecordsJson(null);
                    return runs.save(run);
                })
                .orElse(null));
        if (failed == null) {
            return;
        }
        log(bankId, runId, "ERROR", failed.getError());
        Map<String, Object> values = summary(failed);
        values.put("error", failed.getError());
        audit.record(AuditEventSpec.builder("INTEGRATION_RUN_FAILED", AuditOutcome.FAILED)
                .entity("IntegrationRun", String.valueOf(runId))
                .newValues(values)
                .build());
    }

    static Map<String, Object> summary(IntegrationRun run) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("runId", run.getId());
        values.put("flowId", run.getFlowId());
        values.put("connectionId", run.getConnectionId());
        values.put("operation", run.getOperation());
        values.put("trigger", run.getTriggerType() == null ? null : run.getTriggerType().name());
        values.put("status", run.getStatus().name());
        values.put("recordsIn", run.getRecordsIn());
        values.put("recordsOut", run.getRecordsOut());
        values.put("ingestionJobId", run.getIngestionJobId());
        return values;
    }
}
