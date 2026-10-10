package com.modocument.infrastructure.ai;

import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.FallbackReason;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.ratelimit.LlmQuotaGate;
import com.modocument.infrastructure.ratelimit.LlmRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AC-EXT-03/12: the pre-checks that skip the AI, cheapest first, the quota last. */
class AiPreflightTest {

    private final SettingsReader settings = mock(SettingsReader.class);
    private final AiFilesService aiFiles = mock(AiFilesService.class);
    private final LlmQuotaGate quota = mock(LlmQuotaGate.class);
    private final AiCircuitBreaker breaker = new AiCircuitBreaker(() -> 1,
            () -> Duration.ofMinutes(2), Clock.systemUTC(), opened -> {
            });
    private final AiPreflight preflight = new AiPreflight(settings, aiFiles, breaker, quota);

    @BeforeEach
    void allClear() {
        when(settings.getBool(SettingKeys.AI_ENABLED, 5)).thenReturn(true);
        when(aiFiles.isConfigured()).thenReturn(true);
        when(quota.tryAcquire(anyInt(), any())).thenReturn(true);
    }

    @Test
    void passesWhenEverythingIsAvailable() {
        assertThat(preflight.check(5, 9)).isEmpty();
        verify(quota).tryAcquire(9, 5);
    }

    @Test
    void aiSwitchedOffComesFirstAndSpendsNoQuota() {
        when(settings.getBool(SettingKeys.AI_ENABLED, 5)).thenReturn(false);
        when(aiFiles.isConfigured()).thenReturn(false);

        assertThat(preflight.check(5, 9)).contains(FallbackReason.AI_DISABLED);
        verify(quota, never()).tryAcquire(anyInt(), any());
    }

    @Test
    void noKeyIsNotConfigured() {
        when(aiFiles.isConfigured()).thenReturn(false);

        assertThat(preflight.check(5, 9)).contains(FallbackReason.NOT_CONFIGURED);
        verify(quota, never()).tryAcquire(anyInt(), any());
    }

    @Test
    void anOpenCircuitSpendsNoQuota() {
        breaker.tryAcquire();
        breaker.onFailure(new ResourceAccessException("refused"));

        assertThat(preflight.check(5, 9)).contains(FallbackReason.CIRCUIT_OPEN);
        verify(quota, never()).tryAcquire(anyInt(), any());
    }

    @Test
    void aSpentQuotaIsQuota() {
        when(quota.tryAcquire(anyInt(), any())).thenReturn(false);

        assertThat(preflight.check(5, 9)).contains(FallbackReason.QUOTA);
    }

    @Test
    void theQuotaGateSpendsTheUsersLlmQuotaWithoutThrowing() {
        LlmQuotaGate real = new LlmQuotaGate(new LlmRateLimiter(2, 3_600_000, 1000));

        assertThat(real.tryAcquire(1, 5)).isTrue();
        assertThat(real.tryAcquire(1, 5)).isTrue();
        assertThat(real.tryAcquire(1, 5)).isFalse();
        assertThat(real.tryAcquire(2, 5)).isTrue();
    }

    @Test
    void anUnreadableSwitchCountsAsOn() {
        when(settings.getBool(SettingKeys.AI_ENABLED, 5)).thenThrow(new IllegalStateException("x"));

        assertThat(preflight.aiEnabled(5)).isTrue();
    }
}
