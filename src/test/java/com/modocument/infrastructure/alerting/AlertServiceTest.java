package com.modocument.infrastructure.alerting;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.domain.entity.AlertLog;
import com.modocument.domain.model.AlertSeverity;
import com.modocument.domain.model.AlertStatus;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.infrastructure.persistence.AlertLogJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dedupe, delivery bookkeeping and the retry/give-up lifecycle of the ws18
 * {@link AlertService} (SEC-032), with an always-on "log" channel and a
 * switchable "webhook" channel.
 */
class AlertServiceTest {

    private final AlertLogJpaRepository repo = mock(AlertLogJpaRepository.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final AtomicBoolean webhookUp = new AtomicBoolean(true);
    private final AtomicInteger webhookCalls = new AtomicInteger();
    private AlertService service;

    private final AlertChannel log = new FakeChannel("log", () -> true, new AtomicInteger());
    private final AlertChannel webhook = new FakeChannel("webhook", webhookUp::get, webhookCalls);

    private record FakeChannel(String name, java.util.function.BooleanSupplier up,
                               AtomicInteger calls) implements AlertChannel {
        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public AlertSeverity minSeverity() {
            return AlertSeverity.INFO;
        }

        @Override
        public void deliver(AlertMessage alert) {
            calls.incrementAndGet();
            if (!up.getAsBoolean()) {
                throw new IllegalStateException("503 from " + name);
            }
        }
    }

    private AlertService service() {
        AlertProperties properties = new AlertProperties();
        properties.getDelivery().setAttempts(1);
        properties.getDelivery().setRetryWindow(Duration.ofMinutes(30));
        when(repo.save(any(AlertLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new AlertService(properties, List.of(log, webhook), repo, audit, tx);
        return service;
    }

    @AfterEach
    void shutdown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private static AlertRule rule() {
        return new AlertRule("login-failures-per-user", "", AlertRule.Trigger.WINDOWED,
                java.util.Set.of("LOGIN_FAILED"), java.util.Set.of(), java.util.Set.of(),
                AlertSeverity.HIGH, 5, Duration.ofMinutes(5), AlertRule.GroupBy.ACTOR);
    }

    private static AlertCandidate candidate() {
        return new AlertCandidate(rule(), 3, "user:42", "5 LOGIN_FAILED for user #42",
                UUID.randomUUID(), 5);
    }

    private static AlertLog pendingRow(String acceptedChannels, Instant createdAt) {
        AlertLog row = new AlertLog();
        row.setBankId(3);
        row.setRuleName("login-failures-per-user");
        row.setSeverity(AlertSeverity.HIGH);
        row.setDedupeKey("k");
        row.setSummary("s");
        row.setCreatedAt(createdAt);
        row.setChannel(acceptedChannels);
        row.setStatus(AlertStatus.PENDING);
        return row;
    }

    private List<AuditEventSpec> audits() {
        ArgumentCaptor<AuditEventSpec> specs = ArgumentCaptor.forClass(AuditEventSpec.class);
        verify(audit, atLeastOnce()).record(specs.capture());
        return specs.getAllValues();
    }

    @Test
    void aBurstRaisesOneAlertPerWindowInMemoryAndAcrossRestarts() {
        AlertService first = service();
        Optional<AlertLog> raised = first.record(candidate());
        assertThat(raised).isPresent();
        assertThat(raised.get().getStatus()).isEqualTo(AlertStatus.PENDING);
        assertThat(raised.get().getDedupeKey()).as("hashed, never the raw actor key")
                .matches("[0-9a-f]{64}");
        assertThat(first.record(candidate())).as("same burst, same window").isEmpty();

        // A fresh instance (restart) consults the persisted DedupeKey.
        first.shutdown();
        when(repo.existsByBankIdAndDedupeKeyAndCreatedAtAfter(eq(3), anyString(), any()))
                .thenReturn(true);
        assertThat(service().record(candidate())).isEmpty();
    }

    @Test
    void aFailingChannelLeavesTheRowPendingAndIsAuditedOnce() {
        AlertService alerts = service();
        webhookUp.set(false);
        AlertLog row = pendingRow(null, Instant.now());

        alerts.deliver(row, false);

        assertThat(row.getStatus()).isEqualTo(AlertStatus.PENDING);
        assertThat(row.getChannel()).isEqualTo("log");
        assertThat(row.getDeliveredAt()).isNull();
        List<AuditEventSpec> specs = audits();
        assertThat(specs).extracting(AuditEventSpec::action)
                .containsExactlyInAnyOrder("ALERT_SENT", "ALERT_DELIVERY_FAILED");
        AuditEventSpec failed = specs.stream()
                .filter(s -> s.action().equals("ALERT_DELIVERY_FAILED")).findFirst().orElseThrow();
        assertThat(failed.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(failed.bankId()).isEqualTo(3);
        assertThat(values(failed)).containsEntry("channel", "webhook")
                .containsEntry("final", false);
    }

    @Test
    void theSweepRetryDeliversOnlyTheOwedChannelAndCompletesTheRow() {
        AlertService alerts = service();
        AlertLog row = pendingRow("log", Instant.now().minus(Duration.ofMinutes(2)));
        when(repo.findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(eq(AlertStatus.PENDING),
                any(), any(), any()))
                .thenReturn(List.of(row))   // due for retry
                .thenReturn(List.of());     // nothing expired

        int retried = alerts.retryPendingDeliveries(Instant.now());

        assertThat(retried).isEqualTo(1);
        assertThat(webhookCalls.get()).isEqualTo(1);
        assertThat(row.getStatus()).isEqualTo(AlertStatus.SENT);
        assertThat(row.getChannel()).isEqualTo("log,webhook");
        assertThat(row.getDeliveredAt()).isNotNull();
        List<AuditEventSpec> specs = audits();
        assertThat(specs).extracting(AuditEventSpec::action).containsExactly("ALERT_SENT");
        assertThat(values(specs.get(0))).containsEntry("channel", "webhook");
    }

    @Test
    void afterTheRetryWindowTheAlertIsMarkedFailedAndAuditedFinal() {
        AlertService alerts = service();
        webhookUp.set(false);
        AlertLog row = pendingRow("log", Instant.now().minus(Duration.ofHours(1)));
        when(repo.findByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(eq(AlertStatus.PENDING),
                any(), any(), any()))
                .thenReturn(List.of())      // nothing due
                .thenReturn(List.of(row));  // expired

        alerts.retryPendingDeliveries(Instant.now());

        assertThat(row.getStatus()).isEqualTo(AlertStatus.FAILED);
        AuditEventSpec spec = audits().get(0);
        assertThat(spec.action()).isEqualTo("ALERT_DELIVERY_FAILED");
        assertThat(values(spec)).containsEntry("final", true)
                .containsEntry("channel", "webhook");
    }

    @Test
    void summariesAreScrubbedAndBounded() {
        AuditFacts unknown = new AuditFacts(UUID.randomUUID(), Instant.now(), 3, "LOGIN_FAILED",
                AuditOutcome.FAILED, null, "jane.doe@bank.example", null, "203.0.113.9", null, null);
        String windowed = AlertService.windowedSummary(rule(), unknown, 7);
        assertThat(windowed).contains("7 LOGIN_FAILED event(s) within 5m")
                .contains("j***@bank.example").doesNotContain("jane.doe");

        String immediate = AlertService.immediateSummary(rule(), unknown);
        assertThat(immediate).contains("LOGIN_FAILED (FAILED)").doesNotContain("jane.doe");

        assertThat(AlertService.scrubbedSummary("x".repeat(900))).hasSize(500);
        assertThat(AlertService.scrubbedSummary("account 123456789012 leaked"))
                .doesNotContain("123456789012");
    }

    @Test
    void severityGatesChannels() {
        assertThat(AlertSeverity.INFO.atLeast(AlertSeverity.MEDIUM)).isFalse();
        assertThat(AlertSeverity.CRITICAL.atLeast(AlertSeverity.MEDIUM)).isTrue();
        assertThat(AlertSeverity.LOW.atLeast(null)).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> values(AuditEventSpec spec) {
        return (Map<String, Object>) spec.newValues();
    }
}
