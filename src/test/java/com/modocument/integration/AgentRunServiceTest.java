package com.modocument.integration;

import com.modocument.application.port.TenantContext;
import com.modocument.application.port.integration.AgentRunPort;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.port.integration.RecordsIngestion;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.IntegrationConnection;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.entity.IntegrationRun;
import com.modocument.domain.entity.OutboundDelivery;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.DeliveryStatus;
import com.modocument.domain.model.integration.FlowDirection;
import com.modocument.domain.model.integration.FlowEvent;
import com.modocument.domain.model.integration.FlowTrigger;
import com.modocument.domain.model.integration.PayloadKind;
import com.modocument.domain.model.integration.RunStatus;
import com.modocument.infrastructure.integration.engine.IntegrationJson;
import com.modocument.infrastructure.integration.engine.IntegrationRunExecutor;
import com.modocument.infrastructure.integration.engine.IntegrationRuns;
import com.modocument.infrastructure.integration.engine.OutboundDispatcher;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunJpaRepository;
import com.modocument.infrastructure.persistence.OutboundDeliveryJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentRunPort} semantics (ws25, the contract SE5's agent endpoints
 * build on): AGENT-connection runs wait in AWAITING_AGENT; only the owning
 * agent claims them (RUNNING_AGENT) and gets config + secretRef + spec, never
 * a secret; records streamed up become one validated job on {@code last};
 * outbound payloads are masked and keyed; another agent's run is "not found".
 */
class AgentRunServiceTest extends IntegrationTestSupport {

    private static final int AGENT = 77;
    private static final int OTHER_AGENT = 78;

    @Autowired
    private AgentRunPort agentPort;
    @Autowired
    private IntegrationRuns runRecorder;
    @Autowired
    private IntegrationRunExecutor executor;
    @Autowired
    private IntegrationRunJpaRepository runs;
    @Autowired
    private OutboundDeliveryJpaRepository deliveries;
    @Autowired
    private OutboundDispatcher dispatcher;
    @Autowired
    private RecordsIngestion recordsIngestion;
    @Autowired
    private IngestionJobJpaRepository jobs;

    private IntegrationConnection agentConnection(ConnectionType type) {
        IntegrationConnection connection = restConnection("on-prem " + type, type == ConnectionType.WEBHOOK
                ? "HMAC_SIGNATURE" : "BASIC", null, type);
        connection.setMode(ConnectionMode.AGENT);
        connection.setAgentId(AGENT);
        connection.setSecretRef("core-db-password");
        connection.setConfigJson(IntegrationJson.write(Map.of("vendor", "POSTGRESQL", "host", "core.bank.local",
                "database", "core", "username", "reader")));
        return connections.save(connection);
    }

    private long startRun(IntegrationFlow flow) {
        IntegrationRun run = runRecorder.tryCreate(flow, flow.getDirection() == FlowDirection.INBOUND
                ? IntegrationRun.OP_READ : IntegrationRun.OP_WRITE, FlowTrigger.MANUAL, runRecorder.uniqueNow(),
                admin.getAppUserId());
        executor.submit(bank.getBankId(), run.getId());
        return run.getId();
    }

    @Test
    void anInboundAgentRunIsClaimedByItsAgentAndIngestedOnTheLastPage() throws Exception {
        IntegrationConnection connection = agentConnection(ConnectionType.DATABASE);
        IntegrationFlow flow = flow("agent pull", connection, FlowDirection.INBOUND, FlowTrigger.MANUAL,
                Map.of("table", "v_customers", "columns", List.of("name", "account"),
                        "watermarkColumn", "updated_at"));
        flow.setFieldMappingJson(IntegrationJson.write(List.of(
                new IntegrationJson.FieldMapping("name", "CustomerName"),
                new IntegrationJson.FieldMapping("account", "AccountNo"))));
        flow.setCursorValue("T:2026-01-01T00:00");
        flow = flows.save(flow);
        long runId = startRun(flow);
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.AWAITING_AGENT);

        int bankId = bank.getBankId();
        assertThat(TenantContext.runAs(bankId, () -> agentPort.claimTasks(bankId, OTHER_AGENT, 5))).isEmpty();
        List<AgentRunPort.AgentTask> tasks = TenantContext.runAs(bankId,
                () -> agentPort.claimTasks(bankId, AGENT, 5));
        assertThat(tasks).singleElement().satisfies(task -> {
            assertThat(task.runId()).isEqualTo(runId);
            assertThat(task.operation()).isEqualTo("READ");
            assertThat(task.type()).isEqualTo(ConnectionType.DATABASE);
            assertThat(task.secretRef()).isEqualTo("core-db-password");
            assertThat(task.config()).containsEntry("host", "core.bank.local").containsEntry("authType", "BASIC")
                    .doesNotContainKey("password");
            assertThat(task.spec()).containsEntry("table", "v_customers");
            assertThat(task.cursor()).isEqualTo("T:2026-01-01T00:00");
            assertThat(task.maxRecords()).isPositive();
        });
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING_AGENT);
        assertThat(TenantContext.runAs(bankId, () -> agentPort.claimTasks(bankId, AGENT, 5))).isEmpty();

        // Another agent cannot touch the run.
        assertThatThrownBy(() -> TenantContext.runAs(bankId, (Runnable) () -> agentPort.acceptRecords(bankId,
                OTHER_AGENT, runId, List.of(Map.of("name", "x")), null, false)))
                .isInstanceOf(NoSuchElementException.class);

        TenantContext.runAs(bankId, (Runnable) () -> agentPort.acceptRecords(bankId, AGENT, runId,
                List.of(Map.of("name", "Ada Lovelace", "account", "123456789")), "T:2026-02-01T00:00", false));
        TenantContext.runAs(bankId, (Runnable) () -> agentPort.acceptRecords(bankId, AGENT, runId,
                List.of(Map.of("name", "Bob Stone", "account", "987654321")), "T:2026-03-01T00:00", true));
        TenantContext.runAs(bankId, (Runnable) () -> agentPort.complete(bankId, AGENT, runId,
                new AgentRunPort.AgentRunResult(true, 2, 0, null, null)));

        IntegrationRun run = runs.findById(runId).orElseThrow();
        assertThat(run.getStatus()).as(run.getError()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getRecordsIn()).isEqualTo(2);
        assertThat(run.getStagedRecordsJson()).isNull();
        IngestionJob job = jobs.findById(run.getIngestionJobId()).orElseThrow();
        assertThat(job.getCurrentStep()).isEqualTo(JobStep.VALIDATED);
        assertThat(job.getMappedRowsJson()).contains("Bob Stone");
        assertThat(flows.findById(flow.getId()).orElseThrow().getCursorValue()).isEqualTo("T:2026-03-01T00:00");
    }

    @Test
    void sftpFilesStreamedByTheAgentGoThroughTheFilePipeline() throws Exception {
        IntegrationConnection connection = agentConnection(ConnectionType.SFTP);
        IntegrationFlow flow = flows.save(flow("agent sftp", connection, FlowDirection.INBOUND, FlowTrigger.MANUAL,
                Map.of("folder", "inbox", "glob", "*.csv")));
        long runId = startRun(flow);
        int bankId = bank.getBankId();
        TenantContext.runAs(bankId, () -> agentPort.claimTasks(bankId, AGENT, 5));
        String csv = "CustomerName,AccountNo,Amount\nAda Lovelace,123456789,10\n";
        String base64 = java.util.Base64.getEncoder().encodeToString(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> TenantContext.runAs(bankId, (Runnable) () -> agentPort.acceptRecords(bankId, AGENT,
                runId, List.of(Map.of("fileName", "../x.csv", "contentBase64", base64)), null, false)))
                .isInstanceOf(IllegalArgumentException.class);
        TenantContext.runAs(bankId, (Runnable) () -> agentPort.acceptRecords(bankId, AGENT, runId,
                List.of(Map.of("fileName", "customers.csv", "remotePath", "inbox/customers.csv",
                        "size", csv.length(), "contentBase64", base64)), null, true));
        TenantContext.runAs(bankId, (Runnable) () -> agentPort.complete(bankId, AGENT, runId,
                new AgentRunPort.AgentRunResult(true, 1, 0, null, null)));

        IntegrationRun run = runs.findById(runId).orElseThrow();
        assertThat(run.getStatus()).as(run.getError()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getRecordsIn()).isEqualTo(1);
        IngestionJob job = jobs.findById(run.getIngestionJobId()).orElseThrow();
        assertThat(job.getOrigin()).isEqualTo("flow:" + flow.getId() + "/run:" + runId);
        assertThat(job.getOwnerUserId()).isEqualTo(admin.getAppUserId());
        assertThat(job.getFileName()).startsWith("customers_");
    }

    @Test
    void anOutboundAgentDeliveryIsBuiltMaskedAndSettledByTheAgent() throws Exception {
        IntegrationConnection connection = agentConnection(ConnectionType.DATABASE);
        IntegrationFlow flow = flow("agent push", connection, FlowDirection.OUTBOUND, FlowTrigger.EVENT,
                Map.of("table", "stage_customers"));
        flow.setEventSet(EnumSet.of(FlowEvent.INGESTION_VALIDATED));
        flow.setPayloadKindSet(EnumSet.of(PayloadKind.VALIDATED_RECORDS));
        flow.setEnabled(true);
        flows.save(flow);
        int bankId = bank.getBankId();
        TenantContext.runAs(bankId, (Runnable) () -> recordsIngestion.createFromRecords(new RecordsIngestion.Command(
                bankId, admin.getAppUserId(), templateId, null,
                List.of(Map.of("CustomerName", "Ada Lovelace", "AccountNo", "123456789", "Amount", "10")),
                "api-client:9")));
        TenantContext.runAs(bankId, (Runnable) () -> dispatcher.dispatchDue(bankId));

        OutboundDelivery parked = deliveries.findAll().stream().filter(d -> d.getBankId() == bankId)
                .findFirst().orElseThrow();
        assertThat(parked.getNextAttemptAt()).isNull();
        long runId = parked.getRunId();
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.AWAITING_AGENT);

        List<AgentRunPort.AgentTask> tasks = TenantContext.runAs(bankId, () -> agentPort.claimTasks(bankId, AGENT, 5));
        assertThat(tasks).singleElement().satisfies(task -> {
            assertThat(task.operation()).isEqualTo("WRITE");
            assertThat(task.spec()).containsEntry("table", "stage_customers");
        });
        OutboundBatch batch = TenantContext.runAs(bankId, () -> agentPort.payload(bankId, AGENT, runId));
        assertThat(batch.idempotencyKey()).hasSize(40);
        assertThat(batch.records()).singleElement().satisfies(record -> {
            assertThat(String.valueOf(record.get("AccountNo"))).endsWith("6789").doesNotContain("12345");
        });
        // The same payload twice keeps its key (the agent may retry).
        OutboundBatch again = TenantContext.runAs(bankId, () -> agentPort.payload(bankId, AGENT, runId));
        assertThat(again.idempotencyKey()).isEqualTo(batch.idempotencyKey());

        TenantContext.runAs(bankId, (Runnable) () -> agentPort.complete(bankId, AGENT, runId,
                new AgentRunPort.AgentRunResult(true, 0, 1, null, "stage_customers")));
        assertThat(deliveries.findById(parked.getId()).orElseThrow().getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(runs.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(Instant.now()).isNotNull();

        // Like a DIRECT delivery, the agent's delivery is in the export log
        // (EXPORT_RUN on the flow), marked as written by the agent.
        List<Map<String, Object>> exports = jdbc.queryForList("SELECT EntityType, EntityId, NewValues "
                + "FROM AuditEvent WHERE BankId = ? AND Action = 'EXPORT_RUN'", bankId);
        assertThat(exports).singleElement().satisfies(row -> {
            assertThat(row.get("EntityType")).isEqualTo("IntegrationFlow");
            assertThat(row.get("EntityId")).isEqualTo(String.valueOf(flow.getId()));
            Object raw = row.get("NewValues");
            String values = (raw instanceof byte[] bytes
                    ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : String.valueOf(raw))
                    .replace(" ", "");
            assertThat(values).contains("\"flowId\":" + flow.getId())
                    .contains("\"mode\":\"AGENT\"").contains("\"agentId\":" + AGENT)
                    .contains("\"rowCount\":1").contains("\"format\":\"JSON\"");
        });
    }

    @Test
    void theSftpFileRecordIsOneSharedShape() {
        com.modocument.connector.core.wire.AgentSftpFile file =
                com.modocument.connector.core.wire.AgentSftpFile.of("stmt.csv", "inbox/stmt.csv",
                        "a,b\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> record = file.toRecord();
        assertThat(record).containsOnlyKeys("fileName", "remotePath", "size", "contentBase64");
        assertThat(com.modocument.connector.core.wire.AgentSftpFile.fromRecord(record, 1024))
                .isEqualTo(file);
        assertThat(file.decode(1024)).hasSize(4);
        assertThatThrownBy(() -> file.decode(3)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3-byte cap");
        assertThatThrownBy(() -> com.modocument.connector.core.wire.AgentSftpFile.fromRecord(
                Map.of("fileName", "a\\b.csv", "contentBase64", "YQ=="), 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(com.modocument.connector.core.wire.AgentSftpFile.fromRecord(
                Map.of("fileName", "a.csv", "contentBase64", "YQ=="), 1024).remotePath()).isEqualTo("a.csv");
    }
}
