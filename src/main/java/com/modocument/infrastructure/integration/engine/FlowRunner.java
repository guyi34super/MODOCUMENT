package com.modocument.infrastructure.integration.engine;

import com.modocument.application.port.SettingsReader;
import com.modocument.application.port.UploadedFileRepository;
import com.modocument.application.port.integration.ConnectionContext;
import com.modocument.application.port.integration.IntegrationConnector;
import com.modocument.application.port.integration.ReadBatch;
import com.modocument.application.port.integration.RecordsIngestion;
import com.modocument.application.usecase.IngestionJobUseCase;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.IntegrationConnection;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.entity.IntegrationRun;
import com.modocument.domain.entity.OutboundDelivery;
import com.modocument.domain.entity.UploadedFile;
import com.modocument.domain.model.Hashing;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.DeliveryStatus;
import com.modocument.domain.model.integration.FlowDirection;
import com.modocument.domain.model.integration.PayloadKind;
import com.modocument.domain.model.integration.RunStatus;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.file.SupportedDocumentTypes;
import com.modocument.infrastructure.file.UploadContentChecker;
import com.modocument.infrastructure.file.UploadStorage;
import com.modocument.infrastructure.persistence.IntegrationConnectionJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationFlowJpaRepository;
import com.modocument.infrastructure.persistence.OutboundDeliveryJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes one run (ws25), always inside {@code TenantContext.runAs(bank)}.
 * <ul>
 *   <li><b>AGENT</b> connections: the run waits in AWAITING_AGENT for the
 *       bank's agent ({@code AgentRunPort}); outbound deliveries are queued
 *       first and parked for it.</li>
 *   <li><b>Inbound</b>: page through {@link IntegrationConnector#read} up to
 *       the cap (the flow's RowCap and the bank's
 *       {@code integration.maxRowsPerRun}) within {@code integration.runTimeout};
 *       records → field mapping → {@link RecordsIngestion} (one validated
 *       IngestionJob linked to the run, owned by the flow's creator, origin
 *       {@code flow:<id>/run:<id>}); SFTP files → stored like an upload →
 *       the normal file pipeline (AI or code extraction). A DATABASE pull
 *       persists its watermark only when the run succeeds.</li>
 *   <li><b>Outbound</b> (MANUAL/SCHEDULE): queue one delivery per validated
 *       job (per job payload kind) and one EVENTS delivery since the flow's
 *       cursor, move the cursor in the same transaction, then dispatch.</li>
 * </ul>
 */
@Component
public class FlowRunner {

    private static final Set<PayloadKind> JOB_KINDS = Set.of(PayloadKind.VALIDATED_RECORDS,
            PayloadKind.REJECTED_ROWS, PayloadKind.JOB_STATUS);
    private static final int MAX_JOBS_PER_RUN = 500;
    private static final int MAX_FILES_PER_RUN = 100;

    private final IntegrationRuns runs;
    private final IntegrationFlowJpaRepository flows;
    private final IntegrationConnectionJpaRepository connections;
    private final OutboundDeliveryJpaRepository deliveries;
    private final IntegrationConnectors connectors;
    private final RecordsIngestion recordsIngestion;
    private final IngestionJobUseCase ingestion;
    private final UploadStorage uploads;
    private final UploadedFileRepository uploadedFiles;
    private final SupportedDocumentTypes supportedTypes;
    private final UploadContentChecker contentChecker;
    private final OutboundDispatcher dispatcher;
    private final SettingsReader settings;
    private final IntegrationEngineProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock = Clock.systemUTC();

    public FlowRunner(IntegrationRuns runs, IntegrationFlowJpaRepository flows,
                      IntegrationConnectionJpaRepository connections,
                      OutboundDeliveryJpaRepository deliveries, IntegrationConnectors connectors,
                      RecordsIngestion recordsIngestion, IngestionJobUseCase ingestion,
                      UploadStorage uploads, UploadedFileRepository uploadedFiles,
                      SupportedDocumentTypes supportedTypes, UploadContentChecker contentChecker,
                      OutboundDispatcher dispatcher, SettingsReader settings,
                      IntegrationEngineProperties properties, JdbcTemplate jdbc,
                      PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.flows = flows;
        this.connections = connections;
        this.deliveries = deliveries;
        this.connectors = connectors;
        this.recordsIngestion = recordsIngestion;
        this.ingestion = ingestion;
        this.uploads = uploads;
        this.uploadedFiles = uploadedFiles;
        this.supportedTypes = supportedTypes;
        this.contentChecker = contentChecker;
        this.dispatcher = dispatcher;
        this.settings = settings;
        this.properties = properties;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Runs a QUEUED run to its next state; never throws. Call inside {@code runAs(bank)}. */
    public void execute(int bankId, long runId) {
        IntegrationRun run = runs.find(bankId, runId).orElse(null);
        if (run == null || run.getStatus() != RunStatus.QUEUED || run.getFlowId() == null) {
            return;
        }
        IntegrationFlow flow = flows.findByIdAndBankId(run.getFlowId(), bankId).orElse(null);
        IntegrationConnection connection = connections.findByIdAndBankId(run.getConnectionId(), bankId)
                .orElse(null);
        if (flow == null || connection == null) {
            runs.fail(bankId, runId, "The flow or its connection no longer exists.");
            return;
        }
        boolean agent = connection.getMode() == ConnectionMode.AGENT;
        if (flow.getDirection() == FlowDirection.INBOUND) {
            if (agent) {
                if (runs.start(bankId, runId, RunStatus.AWAITING_AGENT)) {
                    runs.log(bankId, runId, "INFO", "Waiting for the connector agent.");
                }
                return;
            }
            if (runs.start(bankId, runId, RunStatus.RUNNING)) {
                runInbound(run, flow, connection);
            }
            return;
        }
        if (!runs.start(bankId, runId, agent ? RunStatus.AWAITING_AGENT : RunStatus.RUNNING)) {
            return;
        }
        try {
            int queued = queueOutbound(run, flow, agent);
            runs.log(bankId, runId, "INFO", queued + " delivery(ies) queued.");
            if (queued == 0) {
                runs.succeed(bankId, runId, 0, 0, null, null);
            } else if (!agent) {
                dispatcher.dispatchRun(bankId, runId);
            }
        } catch (Exception e) {
            runs.fail(bankId, runId, e);
        }
    }

    // ---- inbound ---------------------------------------------------------------------

    private void runInbound(IntegrationRun run, IntegrationFlow flow, IntegrationConnection connection) {
        int bankId = run.getBankId();
        long runId = run.getId();
        try {
            if (flow.getTemplateId() == null) {
                throw new IllegalStateException("An inbound flow needs a template.");
            }
            ConnectionContext context = connectors.context(connection, true);
            IntegrationConnector connector = connectors.forType(connection.getType());
            Map<String, Object> spec = IntegrationJson.spec(flow.getSourceSpecJson());
            connector.validate(context, spec, false);
            Instant deadline = clock.instant().plus(runTimeout(bankId));
            int cap = cap(flow);
            String origin = "flow:" + flow.getId() + "/run:" + runId;

            if (connection.getType() == ConnectionType.SFTP) {
                ReadBatch batch = connector.read(context, spec, null, Math.min(cap, MAX_FILES_PER_RUN));
                Integer lastJob = null;
                int ingested = 0;
                for (ReadBatch.InboundFile file : batch.files()) {
                    IngestionJob job = ingestFile(flow, file, origin);
                    lastJob = job.getIngestionJobId();
                    ingested++;
                    runs.log(bankId, runId, job.getStatus() == JobStatus.FAILED ? "WARN" : "INFO",
                            "File " + file.fileName() + " → ingestion job " + lastJob + " ("
                                    + job.getCurrentStep() + ", " + job.getStatus() + ").");
                }
                if (batch.hasMore()) {
                    runs.log(bankId, runId, "INFO", "More files are waiting; the next run takes them.");
                }
                runs.succeed(bankId, runId, ingested, 0, lastJob, null);
                return;
            }

            String cursor = connection.getType() == ConnectionType.DATABASE ? flow.getCursorValue() : null;
            List<Map<String, Object>> records = new ArrayList<>();
            boolean more = true;
            int pageSize = Math.max(1, properties.getReadPageSize());
            while (more && records.size() < cap) {
                if (clock.instant().isAfter(deadline)) {
                    throw new IllegalStateException("The run timed out while reading.");
                }
                ReadBatch batch = connector.read(context, spec, cursor,
                        Math.min(pageSize, cap - records.size()));
                records.addAll(batch.records());
                more = batch.hasMore() && batch.nextCursor() != null
                        && !batch.nextCursor().equals(cursor);
                if (batch.nextCursor() != null) {
                    cursor = batch.nextCursor();
                }
            }
            if (more) {
                runs.log(bankId, runId, "WARN", "The row cap of " + cap
                        + " was reached; the remaining rows follow in the next run.");
            }
            String newCursor = connection.getType() == ConnectionType.DATABASE ? cursor : null;
            if (records.isEmpty()) {
                runs.log(bankId, runId, "INFO", "No new records.");
                runs.succeed(bankId, runId, 0, 0, null, null);
                return;
            }
            List<Map<String, Object>> mapped = IntegrationJson.applyMapping(records,
                    IntegrationJson.mapping(flow.getFieldMappingJson()));
            int jobId = recordsIngestion.createFromRecords(new RecordsIngestion.Command(bankId,
                    flow.getCreatedBy(), flow.getTemplateId(), "run-" + runId, mapped, origin));
            runs.update(bankId, runId, r -> {
                r.setIngestionJobId(jobId);
                r.setRecordsIn(records.size());
            });
            runs.log(bankId, runId, "INFO", records.size() + " record(s) → ingestion job " + jobId + ".");
            runs.succeed(bankId, runId, records.size(), 0, jobId, newCursor);
        } catch (Exception e) {
            runs.fail(bankId, runId, e);
        }
    }

    /** Stores a delivered file like an upload and runs the normal file pipeline. */
    IngestionJob ingestFile(IntegrationFlow flow, ReadBatch.InboundFile file, String origin) {
        if (!supportedTypes.isSupported(file.fileName())) {
            throw new IllegalArgumentException("'" + file.fileName() + "' is not a supported document "
                    + "type: " + supportedTypes.describeSupported() + ".");
        }
        contentChecker.requireContentMatchesExtension(file.fileName(), file.content());
        String stored = uploads.store(file.fileName(), file.content());
        UploadedFile record = new UploadedFile();
        record.setStoredName(stored);
        record.setOriginalName(file.fileName());
        record.setOwnerUserId(flow.getCreatedBy());
        record.setBankId(flow.getBankId());
        record.setSizeBytes((long) file.content().length);
        record.setSha256Hex(Hashing.sha256(new java.io.ByteArrayInputStream(file.content())));
        record.setUploadedAt(LocalDateTime.now(ZoneOffset.UTC));
        uploadedFiles.add(record);
        return ingestion.createFromStoredFile(flow.getBankId(), flow.getCreatedBy(),
                flow.getTemplateId(), stored, origin);
    }

    // ---- outbound --------------------------------------------------------------------

    /**
     * Queues the run's deliveries and moves the flow cursor ({@code j=<last job
     * id>;e=<last event id>}) in ONE transaction.
     */
    int queueOutbound(IntegrationRun run, IntegrationFlow flow, boolean agent) {
        Set<PayloadKind> kinds = flow.payloadKindSet();
        long[] cursor = parseOutboundCursor(flow.getCursorValue());
        Instant now = clock.instant();
        List<OutboundDelivery> queued = new ArrayList<>();
        long lastJob = cursor[0];
        long lastEvent = cursor[1];
        if (flow.getTemplateId() != null && kinds.stream().anyMatch(JOB_KINDS::contains)) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT IngestionJobId, Status, "
                    + "CurrentStep FROM IngestionJob WHERE BankId = ? AND TemplateId = ? "
                    + "AND IngestionJobId > ? AND (CurrentStep IN ('VALIDATED', 'EXPORTED') "
                    + "OR Status = 'FAILED') ORDER BY IngestionJobId LIMIT " + MAX_JOBS_PER_RUN,
                    flow.getBankId(), flow.getTemplateId(), lastJob);
            for (Map<String, Object> row : rows) {
                long jobId = ((Number) value(row, "IngestionJobId")).longValue();
                boolean validated = !"FAILED".equals(String.valueOf(value(row, "Status")));
                lastJob = Math.max(lastJob, jobId);
                for (PayloadKind kind : kinds) {
                    if (!JOB_KINDS.contains(kind) || (!validated && kind != PayloadKind.JOB_STATUS)) {
                        continue;
                    }
                    queued.add(delivery(run, flow, kind, "job:" + jobId, agent, now));
                }
            }
        }
        if (kinds.contains(PayloadKind.EVENTS)) {
            List<Long> ids = jdbc.queryForList("SELECT IntegrationEventId FROM IntegrationEvent "
                    + "WHERE BankId = ? AND IntegrationEventId > ? ORDER BY IntegrationEventId LIMIT 1000",
                    Long.class, flow.getBankId(), lastEvent);
            if (!ids.isEmpty()) {
                long from = ids.get(0);
                long to = ids.get(ids.size() - 1);
                lastEvent = to;
                queued.add(delivery(run, flow, PayloadKind.EVENTS, "events:" + from + "-" + to, agent, now));
            }
        }
        if (kinds.contains(PayloadKind.RELEASED_DOCUMENTS)) {
            runs.log(run.getBankId(), run.getId(), "INFO", "Released documents are sent by "
                    + "EVENT flows on DOC_RELEASED, not by scheduled runs.");
        }
        String newCursor = "j=" + lastJob + ";e=" + lastEvent;
        tx.executeWithoutResult(status -> {
            deliveries.saveAll(queued);
            flows.findByIdAndBankId(flow.getId(), flow.getBankId()).ifPresent(current -> {
                current.setCursorValue(newCursor);
                flows.save(current);
            });
        });
        return queued.size();
    }

    private static Object value(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static OutboundDelivery delivery(IntegrationRun run, IntegrationFlow flow, PayloadKind kind,
                                             String ref, boolean agent, Instant now) {
        OutboundDelivery delivery = new OutboundDelivery();
        delivery.setBankId(flow.getBankId());
        delivery.setFlowId(flow.getId());
        delivery.setRunId(run.getId());
        delivery.setPayloadKind(kind);
        delivery.setPayloadRef(ref);
        delivery.setStatus(DeliveryStatus.PENDING);
        delivery.setNextAttemptAt(agent ? null : now);
        delivery.setCreatedAt(now);
        return delivery;
    }

    static long[] parseOutboundCursor(String cursor) {
        long[] values = {0, 0};
        if (cursor == null) {
            return values;
        }
        for (String part : cursor.split(";")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            try {
                if (pair[0].equals("j")) {
                    values[0] = Long.parseLong(pair[1]);
                } else if (pair[0].equals("e")) {
                    values[1] = Long.parseLong(pair[1]);
                }
            } catch (NumberFormatException ignored) {
                // start from the beginning
            }
        }
        return values;
    }

    // ---- limits ----------------------------------------------------------------------

    int cap(IntegrationFlow flow) {
        int maxRows = settings.getInt(SettingKeys.INTEGRATION_MAX_ROWS_PER_RUN, flow.getBankId());
        return flow.getRowCap() == null ? maxRows : Math.min(flow.getRowCap(), maxRows);
    }

    Duration runTimeout(int bankId) {
        return settings.getDuration(SettingKeys.INTEGRATION_RUN_TIMEOUT, bankId);
    }
}
