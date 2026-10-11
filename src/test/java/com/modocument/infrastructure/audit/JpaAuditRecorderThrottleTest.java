package com.modocument.infrastructure.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.audit.AuditActions;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.SettingsReader;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.AuditEvent;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.RequestIdFilter;
import com.modocument.testsupport.FixedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ws28: the recorder routes ACCESS_DENIED through the aggregation window and
 * the read audits through deduplication + the {@code audit.readAuditEnabled}
 * gate, whatever call site records them; read audits write in their own
 * transaction and never fail the read.
 */
class JpaAuditRecorderThrottleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-10-11T10:00:00Z");

    private final AuditEventWriter writer = mock(AuditEventWriter.class);
    private final SettingsReader settings = mock(SettingsReader.class);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private JpaAuditRecorder recorder() {
        return new JpaAuditRecorder(writer, JSON, new FixedUser(42, 7, Role.READ_WRITE), settings,
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    private List<AuditEvent> independentWrites(int times) {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(writer, times(times)).appendInNewTransaction(captor.capture());
        verify(writer, never()).append(any());
        return captor.getAllValues();
    }

    @Test
    void accessDeniedIsAggregatedPerActorAndEntityTypeWithACount() throws Exception {
        JpaAuditRecorder recorder = recorder();
        TenantContext.runAs(7, () -> {
            for (int i = 0; i < 25; i++) {
                recorder.record(AuditActions.notFoundProbe("IngestionJob", 1000 + i));
            }
            recorder.record(AuditActions.notFoundProbe("Template", 5));
        });

        List<AuditEvent> rows = independentWrites(2);
        assertThat(rows).extracting(AuditEvent::getEntityType)
                .containsExactly("IngestionJob", "Template");
        AuditEvent first = rows.get(0);
        assertThat(first.getAction()).isEqualTo("ACCESS_DENIED");
        assertThat(first.getOutcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(first.getActorUserId()).isEqualTo(42);
        assertThat(first.getBankId()).isEqualTo(7);
        assertThat(first.getEntityId()).isEqualTo("1000");
        JsonNode values = JSON.readTree(first.getNewValues());
        assertThat(values.get("count").asLong()).isOne();
        assertThat(values.get("kind").asText()).isEqualTo("NOT_FOUND_PROBE");

        // The suppressed 24 are flushed as ONE row once the minute is over,
        // from a thread with no request: actor/bank/ip travel with it.
        reset(writer);
        recorder.flushAggregatedDenials(T0.plusSeconds(61));
        AuditEvent tail = independentWrites(1).get(0);
        assertThat(tail.getActorUserId()).isEqualTo(42);
        assertThat(tail.getBankId()).isEqualTo(7);
        assertThat(tail.getEntityType()).isEqualTo("IngestionJob");
        JsonNode tailValues = JSON.readTree(tail.getNewValues());
        assertThat(tailValues.get("count").asLong()).isEqualTo(24);
        assertThat(tailValues.get("sampleEntityIds")).isNotNull();
    }

    @Test
    void anonymousDenialsAreKeyedByIp() {
        JpaAuditRecorder recorder = new JpaAuditRecorder(writer, JSON,
                mock(com.modocument.application.port.CurrentUserProvider.class, invocation -> {
                    throw new IllegalStateException("anonymous");
                }), settings, Clock.fixed(T0, ZoneOffset.UTC));
        AuditEventSpec invalidToken = AuditEventSpec.builder("ACCESS_DENIED", AuditOutcome.DENIED)
                .entity("BearerToken", null)
                .newValues(Map.of("kind", "INVALID_TOKEN"))
                .build();
        MDC.put(RequestIdFilter.MDC_CLIENT_IP, "198.51.100.1");
        recorder.record(invalidToken);
        recorder.record(invalidToken);
        MDC.put(RequestIdFilter.MDC_CLIENT_IP, "198.51.100.2");
        recorder.record(invalidToken);

        assertThat(independentWrites(2)).extracting(AuditEvent::getIp)
                .containsExactly("198.51.100.1", "198.51.100.2");
    }

    @Test
    void sensitiveReadsAreDedupedPerActorAndEntityPerHour() throws Exception {
        when(settings.getBool(eq("audit.readAuditEnabled"), anyInt())).thenReturn(true);
        JpaAuditRecorder recorder = recorder();
        TenantContext.runAs(7, () -> {
            for (int i = 0; i < 3; i++) {
                recorder.success(AuditActions.SENSITIVE_DATA_VIEWED, "IngestionJob", "9", null,
                        Map.of("view", "detail"));
            }
            recorder.success(AuditActions.SENSITIVE_DATA_VIEWED, "IngestionJob", "10", null, null);
        });
        assertThat(independentWrites(2)).extracting(AuditEvent::getEntityId)
                .containsExactly("9", "10");
    }

    @Test
    void theReadAuditFollowsTheBankSettingAndAnAbsentKeyMeansOn() throws Exception {
        when(settings.getBool(eq("audit.readAuditEnabled"), eq(7))).thenReturn(false);
        when(settings.getBool(eq("audit.readAuditEnabled"), eq(8)))
                .thenThrow(new IllegalArgumentException("Unknown setting"));
        JpaAuditRecorder recorder = recorder();

        TenantContext.runAs(7, () -> recorder.success(AuditActions.SENSITIVE_DATA_VIEWED,
                "AppUserList", null));
        verify(writer, never()).appendInNewTransaction(any());

        recorder.record(AuditEventSpec.builder(AuditActions.SENSITIVE_DATA_VIEWED,
                AuditOutcome.SUCCESS).bankId(8).entity("AppUserList", null).build());
        verify(writer, times(1)).appendInNewTransaction(any());
    }

    @Test
    void theAuditViewIsDedupedAndAFailedWriteRetriesInsteadOfFailingTheRead() throws Exception {
        JpaAuditRecorder recorder = recorder();
        doThrow(new IllegalStateException("audit store down"))
                .when(writer).appendInNewTransaction(any());

        TenantContext.runAs(7, () -> recorder.success(AuditActions.AUDIT_LOG_VIEWED, "AuditLog",
                null)); // no exception reaches the read
        TenantContext.runAs(7, () -> recorder.success(AuditActions.AUDIT_LOG_VIEWED, "AuditLog",
                null)); // the failed claim was released: tried again
        verify(writer, times(2)).appendInNewTransaction(any());

        reset(writer);
        TenantContext.runAs(7, () -> recorder.success(AuditActions.AUDIT_LOG_VIEWED, "AuditLog",
                null));
        TenantContext.runAs(7, () -> recorder.success(AuditActions.AUDIT_LOG_VIEWED, "AuditLog",
                null));
        verify(writer, times(1)).appendInNewTransaction(any());
    }
}
