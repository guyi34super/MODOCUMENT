package com.modocument.infrastructure.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.AuditEvent;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.RequestIdFilter;
import com.modocument.testsupport.FixedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The enrichment and transaction-routing contract of the audit recorder:
 * tenant/actor/request auto-fill, anonymous tolerance, JSON serialization
 * with the 64KB truncation marker, SUCCESS joining vs DENIED/FAILED
 * REQUIRES_NEW routing, and the failure policy per outcome.
 */
class JpaAuditRecorderTest {

    private final AuditEventWriter writer = mock(AuditEventWriter.class);
    private final CurrentUserProvider anonymous = mock(CurrentUserProvider.class,
            invocation -> {
                throw new IllegalStateException("No authenticated user in the security context.");
            });

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private JpaAuditRecorder recorder(CurrentUserProvider provider) {
        return new JpaAuditRecorder(writer, new ObjectMapper(), provider);
    }

    @Test
    void successIsEnrichedFromTenantContextActorAndRequestMdc() throws Exception {
        MDC.put(RequestIdFilter.MDC_REQUEST_ID, "req-42");
        MDC.put(RequestIdFilter.MDC_CLIENT_IP, "203.0.113.9");
        JpaAuditRecorder recorder = recorder(new FixedUser(7, 3, Role.READ_WRITE));

        TenantContext.runAs(3, () -> recorder.success(
                "TEMPLATE_UPDATED", "Template", "12",
                Map.of("name", "old"), Map.of("name", "new")));

        AuditEvent event = written(false);
        assertThat(event.getAuditEventId()).isNotNull();
        assertThat(event.getAuditEventId().version()).isEqualTo(7);
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getBankId()).isEqualTo(3);
        assertThat(event.getActorUserId()).isEqualTo(7);
        assertThat(event.getActorRole()).isEqualTo("READ_WRITE");
        assertThat(event.getRequestId()).isEqualTo("req-42");
        assertThat(event.getIp()).isEqualTo("203.0.113.9");
        assertThat(event.getAction()).isEqualTo("TEMPLATE_UPDATED");
        assertThat(event.getEntityType()).isEqualTo("Template");
        assertThat(event.getEntityId()).isEqualTo("12");
        assertThat(event.getOldValues()).isEqualTo("{\"name\":\"old\"}");
        assertThat(event.getNewValues()).isEqualTo("{\"name\":\"new\"}");
        assertThat(event.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
    }

    @Test
    void anonymousCallerIsToleratedAndLandsInTheDefaultBank() {
        JpaAuditRecorder recorder = recorder(anonymous);

        recorder.record(AuditEventSpec.builder("LOGIN_FAILED", AuditOutcome.FAILED)
                .actorEmail("nobody@example.com")
                .build());

        AuditEvent event = written(true);
        assertThat(event.getActorUserId()).isNull();
        assertThat(event.getActorEmail()).isEqualTo("nobody@example.com");
        assertThat(event.getActorRole()).isNull();
        // No tenant bound, no override: the documented default-bank fallback.
        assertThat(event.getBankId()).isEqualTo(Bank.DEFAULT_BANK_ID);
    }

    @Test
    void explicitSpecFieldsWinOverEnrichment() throws Exception {
        JpaAuditRecorder recorder = recorder(new FixedUser(7, 3, Role.ADMIN));

        TenantContext.runAs(3, () -> recorder.record(
                AuditEventSpec.builder("LOGIN_FAILED", AuditOutcome.FAILED)
                        .actorUserId(99)
                        .actorEmail("x@example.com")
                        .actorRole("READ_ONLY")
                        .bankId(5)
                        .build()));

        AuditEvent event = written(true);
        assertThat(event.getActorUserId()).isEqualTo(99);
        assertThat(event.getActorRole()).isEqualTo("READ_ONLY");
        assertThat(event.getBankId()).isEqualTo(5);
    }

    @Test
    void deniedAndFailedWriteInTheirOwnTransaction() {
        JpaAuditRecorder recorder = recorder(anonymous);

        recorder.denied("AUTHORIZATION_DENIED", "Route", "/Templates/Update");
        verify(writer).appendInNewTransaction(any(AuditEvent.class));
        verify(writer, never()).append(any(AuditEvent.class));
    }

    @Test
    void deniedWriteFailureNeverMasksTheRefusal() {
        doThrow(new RuntimeException("audit store down"))
                .when(writer).appendInNewTransaction(any(AuditEvent.class));
        JpaAuditRecorder recorder = recorder(anonymous);

        // Must NOT throw: the caller is about to signal the real refusal.
        recorder.denied("AUTHORIZATION_DENIED", "Route", "/x");
    }

    @Test
    void successWriteFailurePropagatesSoTheChangeCannotCommitUnaudited() {
        doThrow(new RuntimeException("audit store down"))
                .when(writer).append(any(AuditEvent.class));
        JpaAuditRecorder recorder = recorder(anonymous);

        assertThatThrownBy(() -> recorder.success("TEMPLATE_UPDATED", "Template", "1"))
                .hasMessageContaining("audit store down");
    }

    @Test
    void oversizedValuesBecomeTheTruncationMarkerAndStayValidJson() throws Exception {
        JpaAuditRecorder recorder = recorder(anonymous);

        recorder.success("BIG", "Blob", "1",
                Map.of("payload", "x".repeat(JpaAuditRecorder.MAX_VALUES_CHARS + 1)), null);

        AuditEvent event = written(false);
        assertThat(event.getOldValues())
                .contains("\"_truncated\":true")
                .contains("\"_originalLength\":")
                .hasSizeLessThan(JpaAuditRecorder.MAX_VALUES_CHARS);
        // The marker itself must parse: jsonb columns reject invalid JSON.
        new ObjectMapper().readTree(event.getOldValues());
        assertThat(event.getNewValues()).isNull();
    }

    @Test
    void overlongColumnValuesAreTruncatedDefensively() {
        JpaAuditRecorder recorder = recorder(anonymous);

        recorder.denied("A".repeat(100), "T".repeat(100), "I".repeat(100));

        AuditEvent event = written(true);
        assertThat(event.getAction()).hasSize(60);
        assertThat(event.getEntityType()).hasSize(40);
        assertThat(event.getEntityId()).hasSize(64);
    }

    private AuditEvent written(boolean independent) {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        if (independent) {
            verify(writer).appendInNewTransaction(captor.capture());
        } else {
            verify(writer).append(captor.capture());
        }
        return captor.getValue();
    }
}
