package com.modocument.infrastructure.integration.engine;

import com.modocument.application.port.SettingsReader;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.entity.IntegrationRun;
import com.modocument.domain.model.integration.DeliveryStatus;
import com.modocument.domain.model.integration.FlowTrigger;
import com.modocument.domain.model.integration.RunStatus;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.persistence.IntegrationFlowJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunJpaRepository;
import com.modocument.infrastructure.persistence.OutboundDeliveryJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The flow engine's clock (ws25): every 30 s ({@code @EnableScheduling} comes
 * from ExtractionSchedulingConfig; {@code modocument.integration.engine.scheduler-enabled}
 * gates only the schedule, tests call {@link #tick()} directly).
 *
 * <p><b>Tenancy</b> (call-site registry in {@link TenantContext}): ONE
 * {@code runAsSystem} read-only transaction lists the ids of the banks that
 * have work — an enabled flow, a PENDING delivery, or an unfinished run —
 * and nothing else; every bank's work then runs strictly inside
 * {@code runAs(bank)}.
 *
 * <p>Per bank:
 * <ol>
 *   <li>due SCHEDULE flows: insert the run of the slot ({@code NextRunAt}).
 *       UNIQUE (FlowId, ScheduledFor) makes this the cross-task lock — with
 *       several ECS tasks exactly one insert wins, and only the winner
 *       executes the run. Every task then advances {@code NextRunAt} to the
 *       next cron slot (an optimistic-lock loser just skips);</li>
 *   <li>runs older than {@code integration.runTimeout} are FAILED (an
 *       outbound run still holding retrying deliveries is left alone);</li>
 *   <li>due outbox deliveries are dispatched.</li>
 * </ol>
 * One bank's failure never starves the others.
 */
@Component
public class IntegrationScheduler {

    private static final Logger log = LoggerFactory.getLogger(IntegrationScheduler.class);

    private final IntegrationFlowJpaRepository flows;
    private final IntegrationRunJpaRepository runRepository;
    private final OutboundDeliveryJpaRepository deliveries;
    private final IntegrationRuns runs;
    private final IntegrationRunExecutor executor;
    private final OutboundDispatcher dispatcher;
    private final SettingsReader settings;
    private final IntegrationEngineProperties properties;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    public IntegrationScheduler(IntegrationFlowJpaRepository flows,
                                IntegrationRunJpaRepository runRepository,
                                OutboundDeliveryJpaRepository deliveries, IntegrationRuns runs,
                                IntegrationRunExecutor executor, OutboundDispatcher dispatcher,
                                SettingsReader settings, IntegrationEngineProperties properties,
                                PlatformTransactionManager transactionManager) {
        this.flows = flows;
        this.runRepository = runRepository;
        this.deliveries = deliveries;
        this.runs = runs;
        this.executor = executor;
        this.dispatcher = dispatcher;
        this.settings = settings;
        this.properties = properties;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = Clock.systemUTC();
    }

    @Scheduled(fixedDelayString = "${modocument.integration.engine.tick-ms:30000}",
            initialDelayString = "${modocument.integration.engine.tick-ms:30000}")
    public void scheduled() {
        if (!properties.isSchedulerEnabled()) {
            return;
        }
        try {
            tick();
        } catch (RuntimeException e) {
            log.error("Integration scheduler tick failed", e);
        }
    }

    /** One tick over every bank with integration work; returns the runs this task started. */
    public List<Long> tick() {
        List<Integer> banks;
        try {
            // runAsSystem: the cross-bank scan — bank ids only (see class javadoc).
            banks = TenantContext.runAsSystem(() -> readOnly.execute(status -> {
                TreeSet<Integer> ids = new TreeSet<>(flows.findBankIdsWithEnabledFlows());
                ids.addAll(deliveries.findBankIdsWithPending());
                ids.addAll(runRepository.findBankIdsWithRunsIn(IntegrationRuns.ACTIVE));
                return new ArrayList<>(ids);
            }));
        } catch (Exception e) {
            throw new IllegalStateException("The integration scheduler could not list banks.", e);
        }
        List<Long> started = new ArrayList<>();
        for (Integer bankId : banks == null ? List.<Integer>of() : banks) {
            try {
                started.addAll(TenantContext.runAs(bankId, () -> tickBank(bankId)));
            } catch (Exception e) {
                log.error("Integration tick failed for bank {} (continuing)", bankId, e);
            }
        }
        return started;
    }

    /** One bank's tick; must run inside {@code runAs(bankId)}. */
    List<Long> tickBank(int bankId) {
        Instant now = clock.instant();
        List<Long> started = new ArrayList<>();
        for (IntegrationFlow flow : flows.findDueScheduled(bankId, now)) {
            Long runId = scheduleSlot(flow, now);
            if (runId != null) {
                started.add(runId);
                executor.submit(bankId, runId);
            }
        }
        failTimedOut(bankId, now);
        dispatcher.dispatchDue(bankId);
        return started;
    }

    /**
     * Inserts the run of the flow's due slot (null when another task won it)
     * and advances the flow to its next slot.
     */
    public Long scheduleSlot(IntegrationFlow flow, Instant now) {
        Instant slot = flow.getNextRunAt();
        IntegrationRun run = runs.tryCreate(flow, operationOf(flow), FlowTrigger.SCHEDULE, slot, null);
        try {
            IntegrationFlow fresh = flows.findByIdAndBankId(flow.getId(), flow.getBankId()).orElse(null);
            if (fresh != null && slot.equals(fresh.getNextRunAt())) {
                Instant after = now.isAfter(slot) ? now : slot;
                fresh.setNextRunAt(FlowSchedule.next(fresh.getCronExpression(), after));
                flows.save(fresh);
            }
        } catch (ObjectOptimisticLockingFailureException concurrent) {
            // another task advanced it
        } catch (IllegalArgumentException badCron) {
            log.warn("Flow {} has an unusable cron expression; not rescheduled", flow.getId());
        }
        return run == null ? null : run.getId();
    }

    static String operationOf(IntegrationFlow flow) {
        return flow.getDirection() == com.modocument.domain.model.integration.FlowDirection.INBOUND
                ? IntegrationRun.OP_READ : IntegrationRun.OP_WRITE;
    }

    private void failTimedOut(int bankId, Instant now) {
        Duration timeout = settings.getDuration(SettingKeys.INTEGRATION_RUN_TIMEOUT, bankId);
        for (IntegrationRun run : runRepository.findStale(bankId, IntegrationRuns.ACTIVE,
                now.minus(timeout))) {
            if (IntegrationRun.OP_WRITE.equals(run.getOperation())
                    && run.getStatus() == RunStatus.RUNNING
                    && deliveries.countByBankIdAndRunIdAndStatus(bankId, run.getId(),
                    DeliveryStatus.PENDING) > 0) {
                continue; // retries in flight: the outbox settles it
            }
            runs.fail(bankId, run.getId(), "The run did not finish within " + timeout.toMinutes()
                    + " minutes.");
            // Deliveries parked for an agent that never came: dead-letter them
            // so the run can be replayed from the dashboard.
            for (var delivery : deliveries.findByBankIdAndRunIdAndStatusOrderByIdAsc(bankId,
                    run.getId(), DeliveryStatus.PENDING)) {
                dispatcher.markDead(delivery, null, "The run timed out.");
            }
        }
    }
}
