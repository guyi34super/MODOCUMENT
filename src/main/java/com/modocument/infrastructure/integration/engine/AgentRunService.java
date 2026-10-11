package com.modocument.infrastructure.integration.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.modocument.application.port.integration.AgentRunPort;
import com.modocument.connector.core.wire.AgentSftpFile;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.RecordsIngestion;
import com.modocument.domain.entity.IntegrationConnection;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.entity.IntegrationRun;
import com.modocument.domain.entity.OutboundDelivery;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.DeliveryStatus;
import com.modocument.domain.model.integration.PayloadFormat;
import com.modocument.domain.model.integration.RunStatus;
import com.modocument.infrastructure.persistence.IntegrationConnectionJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationFlowJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunJpaRepository;
import com.modocument.infrastructure.persistence.OutboundDeliveryJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The flow engine's side of the agent protocol (ws25, {@link AgentRunPort});
 * SE5's {@code /api/agent/v1/**} controllers call it inside
 * {@code runAs(agent's bank)}. Every call is scoped to the calling agent: a
 * run whose connection is not an AGENT connection of {@code agentId} in
 * {@code bankId} is "not found" ({@link NoSuchElementException}).
 *
 * <ul>
 *   <li>{@link #claimTasks}: AWAITING_AGENT → RUNNING_AGENT, oldest first;
 *       the task carries the non-secret config (plus {@code authType}), the
 *       {@code secretRef}, the spec (identifiers only), the stored cursor
 *       (DATABASE watermark) and the row cap.</li>
 *   <li>{@link #acceptRecords} (READ): stages the pages (capped by
 *       {@code integration.maxRowsPerRun} / the flow's RowCap; over the cap
 *       fails the run); {@code last=true} ingests them into ONE validated
 *       IngestionJob linked to the run.</li>
 *   <li>{@link #payload} (WRITE): the run's next PENDING delivery, built and
 *       keyed exactly as a DIRECT dispatch would (masked, mapped, capped).</li>
 *   <li>{@link #complete}: READ → SUCCEEDED (watermark persisted) / FAILED;
 *       WRITE → that delivery DELIVERED (+ EXPORT_RUN with the flow id and
 *       the agent id, as for DIRECT deliveries), or retried with backoff / DEAD; the
 *       run returns to AWAITING_AGENT while deliveries remain; TEST → the
 *       connection's status and last test result.</li>
 * </ul>
 */
@Component
public class AgentRunService implements AgentRunPort {

    private final IntegrationRunJpaRepository runRepository;
    private final IntegrationRuns runs;
    private final IntegrationFlowJpaRepository flows;
    private final IntegrationConnectionJpaRepository connections;
    private final OutboundDeliveryJpaRepository deliveries;
    private final RecordsIngestion recordsIngestion;
    private final PayloadBuilder payloads;
    private final OutboundDispatcher dispatcher;
    private final FlowRunner runner;
    private final TransactionTemplate tx;
    private final long maxFileBytes;
    private final Clock clock = Clock.systemUTC();

    public AgentRunService(IntegrationRunJpaRepository runRepository, IntegrationRuns runs,
                           IntegrationFlowJpaRepository flows,
                           IntegrationConnectionJpaRepository connections,
                           OutboundDeliveryJpaRepository deliveries, RecordsIngestion recordsIngestion,
                           PayloadBuilder payloads, OutboundDispatcher dispatcher, FlowRunner runner,
                           PlatformTransactionManager transactionManager,
                           com.modocument.infrastructure.integration.connector.IntegrationConnectorProperties
                                   connectorProperties) {
        this.maxFileBytes = connectorProperties.limits().maxFileBytes();
        this.runRepository = runRepository;
        this.runs = runs;
        this.flows = flows;
        this.connections = connections;
        this.deliveries = deliveries;
        this.recordsIngestion = recordsIngestion;
        this.payloads = payloads;
        this.dispatcher = dispatcher;
        this.runner = runner;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public List<AgentTask> claimTasks(int bankId, int agentId, int max) {
        int limit = Math.max(1, Math.min(max, 50));
        List<AgentTask> tasks = new ArrayList<>();
        for (IntegrationRun candidate : runRepository.findAwaitingAgent(bankId, agentId,
                PageRequest.of(0, limit))) {
            Boolean claimed = tx.execute(status -> runRepository.findByIdAndBankId(candidate.getId(), bankId)
                    .filter(run -> run.getStatus() == RunStatus.AWAITING_AGENT)
                    .map(run -> {
                        run.setStatus(RunStatus.RUNNING_AGENT);
                        if (run.getStartedAt() == null) {
                            run.setStartedAt(clock.instant());
                        }
                        runRepository.saveAndFlush(run);
                        return true;
                    })
                    .orElse(false));
            if (!Boolean.TRUE.equals(claimed)) {
                continue;
            }
            tasks.add(task(bankId, candidate));
            runs.log(bankId, candidate.getId(), "INFO", "Claimed by agent " + agentId + ".");
        }
        return tasks;
    }

    private AgentTask task(int bankId, IntegrationRun run) {
        IntegrationConnection connection = connections.findByIdAndBankId(run.getConnectionId(), bankId)
                .orElseThrow(() -> new NoSuchElementException("run " + run.getId()));
        Map<String, String> config = IntegrationJson.config(connection.getConfigJson());
        config.put("authType", connection.getAuthType());
        Map<String, Object> spec = Map.of();
        String cursor = null;
        int maxRecords = 0;
        if (run.getFlowId() != null) {
            IntegrationFlow flow = flows.findByIdAndBankId(run.getFlowId(), bankId)
                    .orElseThrow(() -> new NoSuchElementException("run " + run.getId()));
            boolean read = IntegrationRun.OP_READ.equals(run.getOperation());
            spec = IntegrationJson.spec(read ? flow.getSourceSpecJson() : flow.getTargetSpecJson());
            if (read && connection.getType() == ConnectionType.DATABASE) {
                cursor = flow.getCursorValue();
            }
            maxRecords = runner.cap(flow);
        }
        return new AgentTask(run.getId(), connection.getId(), connection.getType(), run.getOperation(),
                config, connection.getSecretRef(), spec, cursor, maxRecords);
    }

    @Override
    public void acceptRecords(int bankId, int agentId, long runId, List<Map<String, Object>> records,
                              String nextCursor, boolean last) {
        IntegrationRun run = requireRun(bankId, agentId, runId, IntegrationRun.OP_READ);
        IntegrationFlow flow = requireFlow(bankId, run);
        if (connectionType(bankId, run) == ConnectionType.SFTP) {
            acceptFiles(bankId, run, flow, records);
            return;
        }
        int cap = runner.cap(flow);
        List<Map<String, Object>> staged = new ArrayList<>(staged(run));
        if (records != null) {
            staged.addAll(records);
        }
        if (staged.size() > cap) {
            runs.fail(bankId, runId, "The agent sent more than the cap of " + cap + " records.");
            throw new IllegalStateException("The run's record cap of " + cap + " is exceeded.");
        }
        runs.update(bankId, runId, current -> {
            current.setStagedRecordsJson(IntegrationJson.write(staged));
            current.setRecordsIn(staged.size());
            if (nextCursor != null) {
                current.setCursorValue(nextCursor.length() <= 500 ? nextCursor : null);
            }
        });
        if (last) {
            ingestStaged(bankId, runId, flow);
        }
    }

    /**
     * SFTP via the agent (SE5): each record is ONE pulled file, shaped as
     * {@link AgentSftpFile} ({@code {fileName, remotePath, size, contentBase64}});
     * it is decoded, size-capped and sent through the normal file pipeline
     * right away (the same path as a DIRECT SFTP pull). The run counts files.
     */
    private void acceptFiles(int bankId, IntegrationRun run, IntegrationFlow flow,
                             List<Map<String, Object>> records) {
        long runId = run.getId();
        String origin = "flow:" + flow.getId() + "/run:" + runId;
        for (Map<String, Object> record : records == null ? List.<Map<String, Object>>of() : records) {
            AgentSftpFile file = AgentSftpFile.fromRecord(record, maxFileBytes);
            byte[] bytes = file.decode(maxFileBytes);
            var job = runner.ingestFile(flow, new com.modocument.application.port.integration.ReadBatch.InboundFile(
                    file.remotePath(), file.fileName(), bytes), origin);
            runs.update(bankId, runId, current -> {
                current.setIngestionJobId(job.getIngestionJobId());
                current.setRecordsIn(current.getRecordsIn() + 1);
            });
            runs.log(bankId, runId, "INFO", "File " + file.fileName() + " → ingestion job "
                    + job.getIngestionJobId() + " (" + job.getCurrentStep() + ", " + job.getStatus() + ").");
        }
    }

    private Integer ingestStaged(int bankId, long runId, IntegrationFlow flow) {
        IntegrationRun run = runs.find(bankId, runId).orElseThrow();
        List<Map<String, Object>> staged = staged(run);
        if (staged.isEmpty()) {
            return run.getIngestionJobId();
        }
        List<Map<String, Object>> mapped = IntegrationJson.applyMapping(staged,
                IntegrationJson.mapping(flow.getFieldMappingJson()));
        int jobId = recordsIngestion.createFromRecords(new RecordsIngestion.Command(bankId,
                flow.getCreatedBy(), flow.getTemplateId(), "run-" + runId, mapped,
                "flow:" + flow.getId() + "/run:" + runId));
        runs.update(bankId, runId, current -> {
            current.setIngestionJobId(jobId);
            current.setRecordsIn(staged.size());
            current.setStagedRecordsJson(null);
        });
        runs.log(bankId, runId, "INFO", staged.size() + " record(s) → ingestion job " + jobId + ".");
        return jobId;
    }

    @Override
    public OutboundBatch payload(int bankId, int agentId, long runId) {
        IntegrationRun run = requireRun(bankId, agentId, runId, IntegrationRun.OP_WRITE);
        IntegrationFlow flow = requireFlow(bankId, run);
        OutboundDelivery delivery = nextDelivery(bankId, runId);
        IntegrationConnection connection = connections.findByIdAndBankId(run.getConnectionId(), bankId)
                .orElseThrow(() -> new NoSuchElementException("run " + runId));
        OutboundBatch batch;
        try {
            batch = payloads.build(flow, delivery, connection.getType() == ConnectionType.SFTP);
        } catch (PayloadBuilder.PayloadRefusedException refused) {
            dispatcher.markDead(delivery, flow, refused.getMessage());
            throw new IllegalStateException(refused.getMessage());
        }
        String key = dispatcher.idempotencyKey(delivery, batch);
        return new OutboundBatch(key, batch.kind(), batch.format(), batch.records(), batch.fileName(),
                batch.contentType(), batch.fileContent());
    }

    @Override
    public void complete(int bankId, int agentId, long runId, AgentRunResult result) {
        IntegrationRun run = requireRun(bankId, agentId, runId, null);
        String error = result == null || result.error() == null ? "The agent reported a failure."
                : result.error();
        boolean success = result != null && result.success();
        switch (run.getOperation()) {
            case IntegrationRun.OP_TEST -> completeTest(bankId, run, success, error);
            case IntegrationRun.OP_READ -> {
                IntegrationFlow flow = requireFlow(bankId, run);
                if (!success) {
                    runs.fail(bankId, runId, clip(error));
                    return;
                }
                try {
                    Integer jobId = ingestStaged(bankId, runId, flow);
                    IntegrationRun current = runs.find(bankId, runId).orElseThrow();
                    String cursor = connectionType(bankId, run) == ConnectionType.DATABASE
                            ? current.getCursorValue() : null;
                    runs.succeed(bankId, runId, current.getRecordsIn(), 0, jobId, cursor);
                } catch (RuntimeException e) {
                    runs.fail(bankId, runId, e);
                }
            }
            default -> {
                IntegrationFlow flow = requireFlow(bankId, run);
                OutboundDelivery delivery = nextDelivery(bankId, runId);
                if (success) {
                    // The agent wrote the payload: log it as EXPORT_RUN with the
                    // flow id exactly like a DIRECT delivery (format as built
                    // by payload(), rows as the agent reports them).
                    PayloadFormat format = connectionType(bankId, run) == ConnectionType.SFTP
                            ? flow.getFormat() : PayloadFormat.JSON;
                    dispatcher.markDelivered(delivery, flow,
                            new PayloadBuilder.ExportFacts(format, result.recordsOut(), agentId),
                            result.recordsOut(), result.remoteReference());
                } else {
                    dispatcher.recordFailure(delivery, flow, clip(error));
                }
                if (deliveries.countByBankIdAndRunIdAndStatus(bankId, runId, DeliveryStatus.PENDING) > 0) {
                    runs.update(bankId, runId, current -> current.setStatus(RunStatus.AWAITING_AGENT));
                } else {
                    dispatcher.settleRun(bankId, runId);
                }
            }
        }
    }

    private void completeTest(int bankId, IntegrationRun run, boolean success, String error) {
        tx.executeWithoutResult(status -> connections.findByIdAndBankId(run.getConnectionId(), bankId)
                .ifPresent(connection -> {
                    connection.setLastTestAt(Instant.now());
                    connection.setLastTestResult(clip(success ? "OK (via the agent)" : error));
                    if (!IntegrationConnectionStatus.DISABLED.equals(connection.getStatus())) {
                        connection.setStatus(success ? IntegrationConnectionStatus.OK
                                : IntegrationConnectionStatus.FAILED);
                    }
                    connections.save(connection);
                }));
        if (success) {
            runs.succeed(bankId, run.getId(), 0, 0, null, null);
        } else {
            runs.fail(bankId, run.getId(), clip(error));
        }
    }

    // ---- scoping -----------------------------------------------------------------------

    /** The agent's own RUNNING_AGENT run, else "not found". */
    private IntegrationRun requireRun(int bankId, int agentId, long runId, String operation) {
        IntegrationRun run = runRepository.findByIdAndBankId(runId, bankId)
                .filter(r -> r.getStatus() == RunStatus.RUNNING_AGENT)
                .filter(r -> operation == null || operation.equals(r.getOperation()))
                .orElseThrow(() -> new NoSuchElementException("run " + runId));
        IntegrationConnection connection = connections.findByIdAndBankId(run.getConnectionId(), bankId)
                .filter(c -> c.getMode() == ConnectionMode.AGENT)
                .filter(c -> c.getAgentId() != null && c.getAgentId() == agentId)
                .orElseThrow(() -> new NoSuchElementException("run " + runId));
        return run;
    }

    private IntegrationFlow requireFlow(int bankId, IntegrationRun run) {
        if (run.getFlowId() == null) {
            throw new NoSuchElementException("run " + run.getId());
        }
        return flows.findByIdAndBankId(run.getFlowId(), bankId)
                .orElseThrow(() -> new NoSuchElementException("run " + run.getId()));
    }

    private OutboundDelivery nextDelivery(int bankId, long runId) {
        return deliveries.findByBankIdAndRunIdAndStatusOrderByIdAsc(bankId, runId, DeliveryStatus.PENDING)
                .stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("run " + runId + " has nothing to send"));
    }

    private ConnectionType connectionType(int bankId, IntegrationRun run) {
        return connections.findByIdAndBankId(run.getConnectionId(), bankId)
                .map(IntegrationConnection::getType).orElse(null);
    }

    private static List<Map<String, Object>> staged(IntegrationRun run) {
        if (run.getStagedRecordsJson() == null || run.getStagedRecordsJson().isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> parsed = IntegrationJson.MAPPER.readValue(run.getStagedRecordsJson(),
                    new TypeReference<List<LinkedHashMap<String, Object>>>() {
                    }).stream().map(m -> (Map<String, Object>) m).toList();
            return parsed;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String clip(String message) {
        if (message == null) {
            return null;
        }
        String safe = com.modocument.application.privacy.LogSafe.scrub(message);
        return safe.length() <= 500 ? safe : safe.substring(0, 500);
    }

    /** Status vocabulary of IntegrationConnection (mirrors the entity constants). */
    static final class IntegrationConnectionStatus {
        static final String OK = IntegrationConnection.STATUS_OK;
        static final String FAILED = IntegrationConnection.STATUS_FAILED;
        static final String DISABLED = IntegrationConnection.STATUS_DISABLED;

        private IntegrationConnectionStatus() {
        }
    }
}
