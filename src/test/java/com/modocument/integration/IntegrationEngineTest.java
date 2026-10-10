package com.modocument.integration;

import com.modocument.application.port.TenantContext;
import com.modocument.application.port.integration.IntegrationEventLog;
import com.modocument.application.port.integration.RecordsIngestion;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.IntegrationConnection;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.entity.IntegrationRun;
import com.modocument.domain.entity.OutboundDelivery;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.DeliveryStatus;
import com.modocument.domain.model.integration.FlowDirection;
import com.modocument.domain.model.integration.FlowEvent;
import com.modocument.domain.model.integration.FlowTrigger;
import com.modocument.domain.model.integration.PayloadKind;
import com.modocument.domain.model.integration.RunStatus;
import com.modocument.infrastructure.integration.engine.IntegrationJson;
import com.modocument.infrastructure.integration.engine.IntegrationScheduler;
import com.modocument.infrastructure.integration.engine.OutboundDispatcher;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationRunJpaRepository;
import com.modocument.infrastructure.persistence.OutboundDeliveryJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flow engine on H2 (ws25): the scheduler's (flow, slot) lock, an
 * inbound pull becoming a validated IngestionJob linked to its run, events
 * creating outbox rows only for subscribed enabled flows, masked delivery
 * with EXPORT_RUN, and retries ending in DEAD with
 * INTEGRATION_DELIVERY_DEAD and a FAILED run.
 */
class IntegrationEngineTest extends IntegrationTestSupport {

    @Autowired
    private IntegrationScheduler scheduler;
    @Autowired
    private OutboundDispatcher dispatcher;
    @Autowired
    private IntegrationEventLog eventLog;
    @Autowired
    private RecordsIngestion recordsIngestion;
    @Autowired
    private IntegrationRunJpaRepository runs;
    @Autowired
    private OutboundDeliveryJpaRepository deliveries;
    @Autowired
    private IngestionJobJpaRepository jobs;

    private static final String CUSTOMERS = "{\"data\":[{\"name\":\"Ada Lovelace\",\"account\":\"123456789\","
            + "\"amount\":\"10\"},{\"name\":\"Bob Stone\",\"account\":\"987654321\",\"amount\":\"20\"}]}";

    private IntegrationFlow inboundScheduled() {
        stubResponses.put("/bank/customers", CUSTOMERS);
        IntegrationConnection rest = restConnection("core-api", "BEARER", Map.of("token", "t"),
                ConnectionType.REST_API);
        IntegrationFlow flow = flow("pull customers", rest, FlowDirection.INBOUND, FlowTrigger.SCHEDULE,
                Map.of("path", "customers", "recordsPath", "data"));
        flow.setCronExpression("0 */5 * * * *");
        flow.setFieldMappingJson(IntegrationJson.write(List.of(
                new IntegrationJson.FieldMapping("name", "CustomerName"),
                new IntegrationJson.FieldMapping("account", "AccountNo"),
                new IntegrationJson.FieldMapping("amount", "Amount"))));
        flow.setEnabled(true);
        flow.setNextRunAt(Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MINUTES));
        return flows.save(flow);
    }

    @Test
    void twoTasksRacingForOneSlotCreateExactlyOneRun() throws Exception {
        IntegrationFlow flow = inboundScheduled();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Long> task = () -> {
            start.await();
            return TenantContext.runAs(bank.getBankId(), () -> scheduler.scheduleSlot(flow, Instant.now()));
        };
        List<Future<Long>> results = new ArrayList<>();
        results.add(pool.submit(task));
        results.add(pool.submit(task));
        start.countDown();
        List<Long> created = new ArrayList<>();
        for (Future<Long> result : results) {
            Long id = result.get();
            if (id != null) {
                created.add(id);
            }
        }
        pool.shutdown();
        assertThat(created).hasSize(1);
        assertThat(runs.findByBankIdAndFlowId(bank.getBankId(), flow.getId())).hasSize(1);
        IntegrationFlow advanced = flows.findById(flow.getId()).orElseThrow();
        assertThat(advanced.getNextRunAt()).isAfter(flow.getNextRunAt());
    }

    @Test
    void aDueInboundFlowBecomesAValidatedJobLinkedToItsRun() {
        IntegrationFlow flow = inboundScheduled();
        List<Long> started = scheduler.tick();
        assertThat(started).hasSize(1);

        IntegrationRun run = runs.findById(started.get(0)).orElseThrow();
        assertThat(run.getStatus()).as(run.getError()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getRecordsIn()).isEqualTo(2);
        assertThat(run.getTriggerType()).isEqualTo(FlowTrigger.SCHEDULE);
        IngestionJob job = jobs.findById(run.getIngestionJobId()).orElseThrow();
        assertThat(job.getCurrentStep()).isEqualTo(JobStep.VALIDATED);
        assertThat(job.getOwnerUserId()).isEqualTo(admin.getAppUserId());
        assertThat(job.getBankId()).isEqualTo(bank.getBankId());
        assertThat(job.getOrigin()).isEqualTo("flow:" + flow.getId() + "/run:" + run.getId());
        assertThat(job.getMappedRowsJson()).contains("Ada Lovelace").contains("AccountNo");
        assertThat(stubRequests).contains("GET /bank/customers");
        assertThat(auditCount("INTEGRATION_RUN_COMPLETED")).isEqualTo(1);

        // The same slot is never run twice: the next tick has nothing due.
        assertThat(scheduler.tick()).isEmpty();
    }

    @Test
    void eventsCreateDeliveriesOnlyForSubscribedEnabledOutboundFlows() {
        IntegrationConnection hook = restConnection("hook", "HMAC_SIGNATURE",
                Map.of("hmacSecret", "0123456789abcdef0123"), ConnectionType.WEBHOOK);
        IntegrationFlow subscribed = eventFlow("subscribed", hook, EnumSet.of(FlowEvent.INGESTION_VALIDATED),
                EnumSet.of(PayloadKind.VALIDATED_RECORDS, PayloadKind.JOB_STATUS), true);
        eventFlow("disabled", hook, EnumSet.of(FlowEvent.INGESTION_VALIDATED),
                EnumSet.of(PayloadKind.JOB_STATUS), false);
        eventFlow("other event", hook, EnumSet.of(FlowEvent.DOC_RELEASED),
                EnumSet.of(PayloadKind.EVENTS), true);

        TenantContext.runAs(bank.getBankId(), () -> eventLog.publish(bank.getBankId(),
                FlowEvent.INGESTION_VALIDATED, "IngestionJob", "42", Map.of("jobId", 42, "templateId", templateId)));

        List<OutboundDelivery> rows = deliveries.findAll().stream()
                .filter(d -> d.getBankId() == bank.getBankId()).toList();
        assertThat(rows).hasSize(2).allMatch(d -> d.getFlowId() == subscribed.getId()
                && d.getStatus() == DeliveryStatus.PENDING && d.getPayloadRef().equals("job:42"));
        assertThat(rows).extracting(OutboundDelivery::getPayloadKind)
                .containsExactlyInAnyOrder(PayloadKind.VALIDATED_RECORDS, PayloadKind.JOB_STATUS);
        assertThat(eventLog.page(bank.getBankId(), 0, 10)).singleElement()
                .satisfies(event -> assertThat(event.type()).isEqualTo(FlowEvent.INGESTION_VALIDATED));
    }

    @Test
    void validatedRecordsLeaveMaskedAndAreLoggedAsExports() throws Exception {
        IntegrationConnection hook = restConnection("hook", "HMAC_SIGNATURE",
                Map.of("hmacSecret", "0123456789abcdef0123"), ConnectionType.WEBHOOK);
        IntegrationFlow flow = eventFlow("send validated", hook, EnumSet.of(FlowEvent.INGESTION_VALIDATED),
                EnumSet.of(PayloadKind.VALIDATED_RECORDS), true);
        assertThat(flow.isMaskPii()).isTrue();

        ingestTwoCustomers();
        dispatchAll();

        OutboundDelivery delivery = onlyDelivery();
        assertThat(delivery.getStatus()).as(delivery.getLastError()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivery.getIdempotencyKey()).hasSize(40);
        String body = stubBodies.get(stubBodies.size() - 1);
        assertThat(body).doesNotContain("123456789").doesNotContain("Ada Lovelace").contains("6789");
        IntegrationRun run = runs.findById(delivery.getRunId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getRecordsOut()).isEqualTo(2);
        assertThat(run.getTriggerType()).isEqualTo(FlowTrigger.EVENT);
        assertThat(auditCount("EXPORT_RUN")).isEqualTo(1);
    }

    @Test
    void failingDeliveriesRetryWithTheSameKeyThenGoDead() throws Exception {
        IntegrationConnection hook = restConnection("hook", "HMAC_SIGNATURE",
                Map.of("hmacSecret", "0123456789abcdef0123"), ConnectionType.WEBHOOK);
        eventFlow("status", hook, EnumSet.of(FlowEvent.INGESTION_VALIDATED), EnumSet.of(PayloadKind.JOB_STATUS),
                true);
        stubStatus.set(500);
        ingestTwoCustomers();

        for (int attempt = 0; attempt < 10 && onlyDelivery().getStatus() == DeliveryStatus.PENDING; attempt++) {
            jdbc.update("UPDATE OutboundDelivery SET NextAttemptAt = ? WHERE BankId = ? AND Status = 'PENDING'",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)), bank.getBankId());
            dispatchAll();
        }

        OutboundDelivery delivery = onlyDelivery();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DEAD);
        assertThat(delivery.getAttempts()).isEqualTo(6); // the first try + 5 retries (default setting)
        assertThat(delivery.getLastError()).contains("500");
        List<String> keys = stubBodies.stream().map(body -> {
            try {
                return MAPPER.readTree(body).get("idempotencyKey").asText();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).distinct().toList();
        assertThat(keys).singleElement().isEqualTo(delivery.getIdempotencyKey());
        assertThat(runs.findById(delivery.getRunId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(auditCount("INTEGRATION_DELIVERY_DEAD")).isEqualTo(1);
        assertThat(auditCount("INTEGRATION_RUN_FAILED")).isEqualTo(1);
    }

    @Test
    void theBackoffDoublesUpToTheCap() {
        assertThat(java.time.Duration.ofSeconds(30)).isEqualTo(backoff(1));
        assertThat(java.time.Duration.ofSeconds(60)).isEqualTo(backoff(2));
        assertThat(java.time.Duration.ofSeconds(120)).isEqualTo(backoff(3));
        assertThat(java.time.Duration.ofHours(1)).isEqualTo(backoff(30));
    }

    private java.time.Duration backoff(int attempts) {
        try {
            var method = OutboundDispatcher.class.getDeclaredMethod("backoff", int.class);
            method.setAccessible(true);
            return (java.time.Duration) method.invoke(dispatcher, attempts);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    private IntegrationFlow eventFlow(String name, IntegrationConnection connection, EnumSet<FlowEvent> events,
                                      EnumSet<PayloadKind> kinds, boolean enabled) {
        IntegrationFlow flow = flow(name, connection, FlowDirection.OUTBOUND, FlowTrigger.EVENT,
                Map.of("path", "events"));
        flow.setEventSet(events);
        flow.setPayloadKindSet(kinds);
        flow.setEnabled(enabled);
        return flows.save(flow);
    }

    private void ingestTwoCustomers() {
        TenantContext.runAs(bank.getBankId(), (Runnable) () -> recordsIngestion.createFromRecords(
                new RecordsIngestion.Command(bank.getBankId(), admin.getAppUserId(), templateId, "batch-1",
                        List.of(Map.of("CustomerName", "Ada Lovelace", "AccountNo", "123456789", "Amount", "10"),
                                Map.of("CustomerName", "Bob Stone", "AccountNo", "987654321", "Amount", "20")),
                        "api-client:1")));
    }

    private void dispatchAll() {
        TenantContext.runAs(bank.getBankId(), (Runnable) () -> dispatcher.dispatchDue(bank.getBankId()));
    }

    private OutboundDelivery onlyDelivery() {
        List<OutboundDelivery> rows = deliveries.findAll().stream()
                .filter(d -> d.getBankId() == bank.getBankId()).toList();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private int auditCount(String action) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE BankId = ? AND Action = ?",
                Integer.class, bank.getBankId(), action);
        return count == null ? 0 : count;
    }
}
