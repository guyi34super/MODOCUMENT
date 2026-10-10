package com.modocument.infrastructure.ai;

import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.PlatformBankLookup;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.SettingsReader;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.FallbackReason;
import com.modocument.domain.model.settings.SettingKeys;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AC-EXT-11: CLOSED → OPEN after N counted failures, one HALF_OPEN trial, close on success. */
class AiCircuitBreakerTest {

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-10T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final TestClock clock = new TestClock();
    private final List<AiCircuitBreaker.Opened> openings = new ArrayList<>();
    private final AiCircuitBreaker breaker = new AiCircuitBreaker(
            () -> 3, () -> Duration.ofSeconds(120), clock, openings::add);

    private static final RuntimeException TRANSPORT = new ResourceAccessException("Connection refused");

    private void failures(int count, RuntimeException error) {
        for (int i = 0; i < count; i++) {
            assertThat(breaker.tryAcquire()).isTrue();
            breaker.onFailure(error);
        }
    }

    @Test
    void opensAfterTheThresholdOfConsecutiveCountedFailures() {
        failures(2, TRANSPORT);
        assertThat(breaker.state()).isEqualTo(AiCircuitBreaker.State.CLOSED);

        failures(1, new TransientAiException("HTTP 500"));

        assertThat(breaker.state()).isEqualTo(AiCircuitBreaker.State.OPEN);
        assertThat(breaker.allow()).isFalse();
        assertThat(breaker.tryAcquire()).isFalse();
        assertThat(breaker.secondsUntilRetry()).isEqualTo(120);
        assertThat(openings).singleElement().satisfies(opened -> {
            assertThat(opened.consecutiveFailures()).isEqualTo(3);
            assertThat(opened.threshold()).isEqualTo(3);
            assertThat(opened.lastFailureType()).isEqualTo("TransientAiException");
        });
    }

    @Test
    void anAnswerResetsTheRunAndClientErrorsDoNotCount() {
        failures(2, TRANSPORT);
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.onSuccess();
        failures(2, TRANSPORT);
        failures(5, new NonTransientAiException("HTTP 400 - bad request"));
        failures(2, TRANSPORT);

        assertThat(breaker.state()).isEqualTo(AiCircuitBreaker.State.CLOSED);
        assertThat(openings).isEmpty();
    }

    @Test
    void afterTheOpenDurationExactlyOneTrialCallIsLetThroughAndSuccessCloses() {
        failures(3, TRANSPORT);
        clock.advance(Duration.ofSeconds(119));
        assertThat(breaker.allow()).isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(breaker.allow()).isTrue();
        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.state()).isEqualTo(AiCircuitBreaker.State.HALF_OPEN);
        // The trial slot is taken: everyone else is still refused.
        assertThat(breaker.allow()).isFalse();
        assertThat(breaker.tryAcquire()).isFalse();

        breaker.onSuccess();

        assertThat(breaker.state()).isEqualTo(AiCircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void aFailedTrialReopensForAFullDurationWithoutASecondAudit() {
        failures(3, TRANSPORT);
        clock.advance(Duration.ofSeconds(120));
        assertThat(breaker.tryAcquire()).isTrue();

        breaker.onFailure(TRANSPORT);

        assertThat(breaker.state()).isEqualTo(AiCircuitBreaker.State.OPEN);
        assertThat(breaker.secondsUntilRetry()).isEqualTo(120);
        assertThat(openings).hasSize(1);
    }

    @Test
    void aTrialThatReachesTheEndpointClosesEvenWhenTheReplyIsAnError() {
        failures(3, TRANSPORT);
        clock.advance(Duration.ofMinutes(5));
        assertThat(breaker.tryAcquire()).isTrue();

        breaker.onFailure(new NonTransientAiException("HTTP 400"));

        assertThat(breaker.state()).isEqualTo(AiCircuitBreaker.State.CLOSED);
    }

    @Test
    void thresholdAndDurationAreReadOnEveryDecision() {
        AtomicInteger threshold = new AtomicInteger(5);
        AiCircuitBreaker live = new AiCircuitBreaker(threshold::get, () -> Duration.ofSeconds(30),
                clock, opened -> {
                });
        live.tryAcquire();
        live.onFailure(TRANSPORT);
        threshold.set(2);
        live.tryAcquire();
        live.onFailure(TRANSPORT);

        assertThat(live.state()).isEqualTo(AiCircuitBreaker.State.OPEN);
        assertThat(live.secondsUntilRetry()).isEqualTo(30);
    }

    @Test
    void openingIsAuditedUnderThePlatformBankWithCountsOnly() {
        SettingsReader settings = mock(SettingsReader.class);
        when(settings.getIntForPlatform(SettingKeys.AI_CIRCUIT_FAILURE_THRESHOLD)).thenReturn(1);
        when(settings.getDurationForPlatform(SettingKeys.AI_CIRCUIT_OPEN_DURATION))
                .thenReturn(Duration.ofSeconds(120));
        AuditRecorder audit = mock(AuditRecorder.class);
        PlatformBankLookup platform = () -> OptionalInt.of(99);
        List<Integer> boundBank = new ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            boundBank.add(TenantContext.currentBankId().orElse(null));
            return null;
        }).when(audit).record(any());
        AiCircuitBreaker spring = new AiCircuitBreaker(settings, audit, platform);

        TenantContext.runAs(7, () -> {
            spring.tryAcquire();
            spring.onFailure(TRANSPORT);
        });

        ArgumentCaptor<AuditEventSpec> spec = ArgumentCaptor.forClass(AuditEventSpec.class);
        verify(audit).record(spec.capture());
        assertThat(spec.getValue().action()).isEqualTo("AI_CIRCUIT_OPENED");
        assertThat(spec.getValue().outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(spec.getValue().bankId()).isEqualTo(99);
        assertThat(boundBank).containsExactly(99);
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) spec.getValue().newValues();
        assertThat(values).containsOnlyKeys(
                "consecutiveFailures", "threshold", "openSeconds", "lastFailure");
    }

    @Test
    void withoutAPlatformBankTheOpeningIsNotAudited() {
        SettingsReader settings = mock(SettingsReader.class);
        when(settings.getIntForPlatform(SettingKeys.AI_CIRCUIT_FAILURE_THRESHOLD)).thenReturn(1);
        AuditRecorder audit = mock(AuditRecorder.class);
        AiCircuitBreaker spring = new AiCircuitBreaker(settings, audit, OptionalInt::empty);

        spring.tryAcquire();
        spring.onFailure(TRANSPORT);

        assertThat(spring.state()).isEqualTo(AiCircuitBreaker.State.OPEN);
        verify(audit, never()).record(any());
    }

    @Test
    void theGatewayRefusesAtOnceWhileOpenAndCallsTheModelAgainAfterwards() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenThrow(new ResourceAccessException("Connection refused"))
                .thenThrow(new ResourceAccessException("Connection refused"))
                .thenThrow(new ResourceAccessException("Connection refused"))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
        AiFilesProperties properties = new AiFilesProperties();
        properties.setApiKey("real-key");
        AiGateway gateway = new AiGateway(properties, ChatClient.create(model), new AiOutputScanner(),
                mock(AuditRecorder.class), breaker, null);

        TenantContext.runAs(1, () -> {
            PromptEnvelope envelope = PromptEnvelope.of(AiPurpose.DIAGNOSTIC, "hi");
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> gateway.sendPrompt(envelope))
                        .isInstanceOf(ResourceAccessException.class);
            }
            assertThatThrownBy(() -> gateway.sendPrompt(envelope))
                    .isInstanceOfSatisfying(AiUnavailableException.class, unavailable -> {
                        assertThat(unavailable.reason()).isEqualTo(FallbackReason.CIRCUIT_OPEN);
                        assertThat(unavailable.retryAfterSeconds()).isEqualTo(120L);
                    })
                    // 503 at the API, like "not configured".
                    .isInstanceOf(AiNotConfiguredException.class);
            verify(model, times(3)).call(any(Prompt.class));

            clock.advance(Duration.ofSeconds(120));
            assertThat(gateway.sendPrompt(envelope)).isEqualTo("ok");
        });
        assertThat(breaker.state()).isEqualTo(AiCircuitBreaker.State.CLOSED);
    }

    @Test
    void theGatewayRefusesEveryCallForABankWithAiSwitchedOff() {
        ChatModel model = mock(ChatModel.class);
        SettingsReader settings = mock(SettingsReader.class);
        when(settings.getBool(SettingKeys.AI_ENABLED, 1)).thenReturn(false);
        AiFilesProperties properties = new AiFilesProperties();
        properties.setApiKey("real-key");
        AiGateway gateway = new AiGateway(properties, ChatClient.create(model), new AiOutputScanner(),
                mock(AuditRecorder.class), breaker, settings);

        Runnable call = () -> assertThatThrownBy(() ->
                gateway.sendPrompt(PromptEnvelope.of(AiPurpose.DIAGNOSTIC, "hi")))
                .isInstanceOfSatisfying(AiUnavailableException.class, unavailable ->
                        assertThat(unavailable.reason()).isEqualTo(FallbackReason.AI_DISABLED));
        TenantContext.runAs(1, call);
        verify(model, never()).call(any(Prompt.class));
    }
}
