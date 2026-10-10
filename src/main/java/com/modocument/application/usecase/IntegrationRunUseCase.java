package com.modocument.application.usecase;

import com.modocument.application.dto.integration.RunView;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.domain.entity.IntegrationConnection;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.entity.IntegrationRun;
import com.modocument.domain.entity.OutboundDelivery;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.DeliveryStatus;
import com.modocument.domain.model.integration.FlowDirection;
import com.modocument.domain.model.integration.FlowTrigger;
import com.modocument.domain.model.integration.RunStatus;
import com.modocument.infrastructure.integration.engine.IntegrationRunExecutor;
import com.modocument.infrastructure.integration.engine.IntegrationRuns;
import com.modocument.infrastructure.persistence.IntegrationConnectionJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationFlowJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunEventJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunJpaRepository;
import com.modocument.infrastructure.persistence.OutboundDeliveryJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Runs (ws25, INTEGRATION_RUN — ADMIN and READ_WRITE): "Run now", the run
 * history and detail, and retry. An OUTBOUND flow runs only while it is
 * enabled (its enabling was approved by a second admin); an inbound flow may
 * be run by hand while disabled. Retry replays a run's DEAD deliveries, or
 * re-queues a FAILED run.
 */
@Service
public class IntegrationRunUseCase {

    private final IntegrationRunJpaRepository runs;
    private final IntegrationRunEventJpaRepository runEvents;
    private final OutboundDeliveryJpaRepository deliveries;
    private final IntegrationFlowJpaRepository flows;
    private final IntegrationConnectionJpaRepository connections;
    private final IntegrationRuns runRecorder;
    private final IntegrationRunExecutor executor;
    private final CurrentUserProvider currentUser;

    public IntegrationRunUseCase(IntegrationRunJpaRepository runs,
                                 IntegrationRunEventJpaRepository runEvents,
                                 OutboundDeliveryJpaRepository deliveries,
                                 IntegrationFlowJpaRepository flows,
                                 IntegrationConnectionJpaRepository connections,
                                 IntegrationRuns runRecorder, IntegrationRunExecutor executor,
                                 CurrentUserProvider currentUser) {
        this.runs = runs;
        this.runEvents = runEvents;
        this.deliveries = deliveries;
        this.flows = flows;
        this.connections = connections;
        this.runRecorder = runRecorder;
        this.executor = executor;
        this.currentUser = currentUser;
    }

    /** "Run now": a MANUAL run, executed asynchronously; returns it as queued. */
    public RunView run(long flowId) {
        int bankId = currentUser.requireBankId();
        IntegrationFlow flow = flows.findByIdAndBankId(flowId, bankId)
                .orElseThrow(() -> new NoSuchElementException("Flow " + flowId + " not found."));
        if (flow.getDirection() == FlowDirection.OUTBOUND && !flow.isEnabled()) {
            throw new IllegalStateException("An outbound flow runs only once its enabling is approved.");
        }
        IntegrationRun run = runRecorder.tryCreate(flow,
                flow.getDirection() == FlowDirection.INBOUND ? IntegrationRun.OP_READ : IntegrationRun.OP_WRITE,
                FlowTrigger.MANUAL, runRecorder.uniqueNow(), currentUser.requireUserId());
        if (run == null) {
            throw new IllegalStateException("A run was just started; try again.");
        }
        runRecorder.log(bankId, run.getId(), "INFO", "Started by user " + currentUser.requireUserId() + ".");
        executor.submit(bankId, run.getId());
        return view(runs.findByIdAndBankId(run.getId(), bankId).orElse(run), flow, false);
    }

    @Transactional(readOnly = true)
    public List<RunView> list(String status, Long flowId, Integer limit) {
        int bankId = currentUser.requireBankId();
        PageRequest page = PageRequest.of(0, limit == null ? 100 : Math.max(1, Math.min(limit, 500)));
        RunStatus parsed = null;
        if (status != null && !status.isBlank()) {
            try {
                parsed = RunStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown run status '" + status + "'.");
            }
        }
        List<IntegrationRun> rows;
        if (flowId != null) {
            RunStatus wanted = parsed;
            rows = runs.listByFlow(bankId, flowId, page).stream()
                    .filter(run -> wanted == null || run.getStatus() == wanted).toList();
        } else if (parsed != null) {
            rows = runs.listByStatus(bankId, parsed, page);
        } else {
            rows = runs.listAll(bankId, page);
        }
        Map<Long, IntegrationFlow> flowCache = new HashMap<>();
        return rows.stream().map(run -> view(run, run.getFlowId() == null ? null
                : flowCache.computeIfAbsent(run.getFlowId(),
                id -> flows.findByIdAndBankId(id, bankId).orElse(null)), false)).toList();
    }

    @Transactional(readOnly = true)
    public RunView get(long runId) {
        int bankId = currentUser.requireBankId();
        IntegrationRun run = runs.findByIdAndBankId(runId, bankId)
                .orElseThrow(() -> new NoSuchElementException("Run " + runId + " not found."));
        IntegrationFlow flow = run.getFlowId() == null ? null
                : flows.findByIdAndBankId(run.getFlowId(), bankId).orElse(null);
        return view(run, flow, true);
    }

    /** Replays DEAD deliveries, or re-queues a FAILED run. */
    public RunView retry(long runId) {
        int bankId = currentUser.requireBankId();
        IntegrationRun run = runs.findByIdAndBankId(runId, bankId)
                .orElseThrow(() -> new NoSuchElementException("Run " + runId + " not found."));
        IntegrationFlow flow = run.getFlowId() == null ? null
                : flows.findByIdAndBankId(run.getFlowId(), bankId).orElse(null);
        if (flow == null) {
            throw new IllegalStateException("Only flow runs can be retried.");
        }
        List<OutboundDelivery> dead = deliveries.findByBankIdAndRunIdAndStatusOrderByIdAsc(bankId, runId,
                DeliveryStatus.DEAD);
        IntegrationConnection connection = connections.findByIdAndBankId(run.getConnectionId(), bankId)
                .orElseThrow(() -> new IllegalStateException("The run's connection no longer exists."));
        boolean agent = connection.getMode() == ConnectionMode.AGENT;
        if (!dead.isEmpty()) {
            if (flow.getDirection() == FlowDirection.OUTBOUND && !flow.isEnabled()) {
                throw new IllegalStateException("Enable the flow (approved) before replaying its deliveries.");
            }
            Instant now = Instant.now();
            for (OutboundDelivery delivery : dead) {
                delivery.setStatus(DeliveryStatus.PENDING);
                delivery.setAttempts(0);
                delivery.setLastError(null);
                delivery.setNextAttemptAt(agent ? null : now);
            }
            deliveries.saveAll(dead);
            reopen(bankId, runId, agent ? RunStatus.AWAITING_AGENT : RunStatus.RUNNING);
            runRecorder.log(bankId, runId, "INFO", dead.size() + " dead delivery(ies) replayed by user "
                    + currentUser.requireUserId() + ".");
            return get(runId);
        }
        if (run.getStatus() != RunStatus.FAILED) {
            throw new IllegalStateException("Only a FAILED run or a run with dead deliveries can be retried.");
        }
        if (flow.getDirection() == FlowDirection.OUTBOUND && !flow.isEnabled()) {
            throw new IllegalStateException("An outbound flow runs only once its enabling is approved.");
        }
        reopen(bankId, runId, RunStatus.QUEUED);
        runRecorder.log(bankId, runId, "INFO", "Re-queued by user " + currentUser.requireUserId() + ".");
        executor.submit(bankId, runId);
        return get(runId);
    }

    private void reopen(int bankId, long runId, RunStatus status) {
        runs.findByIdAndBankId(runId, bankId).ifPresent(run -> {
            run.setStatus(status);
            run.setAttempt(run.getAttempt() + 1);
            run.setError(null);
            run.setFinishedAt(null);
            if (status == RunStatus.QUEUED) {
                run.setStartedAt(null);
            }
            runs.saveAndFlush(run);
        });
    }

    private RunView view(IntegrationRun run, IntegrationFlow flow, boolean detail) {
        List<RunView.RunEventView> events = null;
        List<RunView.DeliveryView> deliveryViews = null;
        if (detail) {
            events = runEvents.findByBankIdAndRunIdOrderByIdAsc(run.getBankId(), run.getId()).stream()
                    .map(event -> new RunView.RunEventView(event.getAt(), event.getLevel(), event.getMessage()))
                    .toList();
            deliveryViews = deliveries.findByBankIdAndRunIdOrderByIdAsc(run.getBankId(), run.getId()).stream()
                    .map(d -> new RunView.DeliveryView(d.getId(), d.getStatus().name(),
                            d.getPayloadKind().name(), d.getPayloadRef(), d.getAttempts(), d.getLastError(),
                            d.getNextAttemptAt(), d.getDeliveredAt(), d.getRemoteReference()))
                    .toList();
        }
        return new RunView(run.getId(), run.getFlowId(), flow == null ? null : flow.getName(),
                flow == null ? null : flow.getDirection().name(), run.getOperation(),
                run.getStatus().name(), run.getTriggerType().name(), run.getScheduledFor(),
                run.getStartedAt(), run.getFinishedAt(), run.getAttempt(), run.getRecordsIn(),
                run.getRecordsOut(), run.getIngestionJobId(), run.getError(), events, deliveryViews);
    }
}
