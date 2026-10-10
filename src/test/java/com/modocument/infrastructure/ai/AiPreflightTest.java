package com.modocument.infrastructure.ai;

import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.FallbackReason;
import com.modocument.domain.model.settings.SettingKeys;
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
    private final LlmRateLimiter quota = mock(LlmRateLimiter.class);
    private final AiCircuitBreaker breaker = new AiCircuitBreaker(() -> 1,
            () -> Duration.ofMinutes(2), Clock.systemUTC(), opened -> {
            });
    private final AiPreflight preflight = new AiPreflight(settings, aiFiles, breaker, quota);

    @BeforeEach
    void allClear() {
        when(settings.getBool(SettingKeys.AI_ENABLED, 5)).thenReturn(true);
        when(aiFiles.isConfigured()).thenReturn(true);
        when(quota.tryCheck(anyInt())).thenReturn(true);
        when(quota.tryCheck(anyInt(), anyInt())).thenReturn(true);
    }

    @Test
    void passesWhenEverythingIsAvailable() {
        assertThat(preflight.check(5, 9)).isEmpty();
        verify(quota).tryCheck(9, 5);
    }

    @Test
    void aiSwitchedOffComesFirstAndSpendsNoQuota() {
        when(settings.getBool(SettingKeys.AI_ENABLED, 5)).thenReturn(false);
        when(aiFiles.isConfigured()).thenReturn(false);

        assertThat(preflight.check(5, 9)).contains(FallbackReason.AI_DISABLED);
        verify(quota, never()).tryCheck(anyInt(), anyInt());
    }

    @Test
    void noKeyIsNotConfigured() {
        when(aiFiles.isConfigured()).thenReturn(false);

        assertThat(preflight.check(5, 9)).contains(FallbackReason.NOT_CONFIGURED);
        verify(quota, never()).tryCheck(anyInt(), anyInt());
    }

    @Test
    void anOpenCircuitSpendsNoQuota() {
        breaker.tryAcquire();
        breaker.onFailure(new ResourceAccessException("refused"));

        assertThat(preflight.check(5, 9)).contains(FallbackReason.CIRCUIT_OPEN);
        verify(quota, never()).tryCheck(anyInt(), anyInt());
    }

    @Test
    void aSpentQuotaIsQuota() {
        when(quota.tryCheck(anyInt(), anyInt())).thenReturn(false);

        assertThat(preflight.check(5, 9)).contains(FallbackReason.QUOTA);
    }

    @Test
    void theRealLimiterSpendsTheUsersLlmQuotaWithoutThrowing() {
        AiPreflight real = new AiPreflight(settings, aiFiles, AiCircuitBreaker.disabled(),
                new LlmRateLimiter(2, 3_600_000, 1000));

        assertThat(real.check(5, 1)).isEmpty();
        assertThat(real.check(5, 1)).isEmpty();
        assertThat(real.check(5, 1)).contains(FallbackReason.QUOTA);
        assertThat(real.check(5, 2)).isEmpty();
    }

    @Test
    void unavailableReasonRunsTheSameChecksButNeverSpendsQuota() {
        assertThat(preflight.unavailableReason(5)).isEmpty();
        when(aiFiles.isConfigured()).thenReturn(false);
        assertThat(preflight.unavailableReason(5)).contains(FallbackReason.NOT_CONFIGURED);
        when(settings.getBool(SettingKeys.AI_ENABLED, 5)).thenReturn(false);
        assertThat(preflight.unavailableReason(5)).contains(FallbackReason.AI_DISABLED);

        verify(quota, never()).tryCheck(anyInt());
        verify(quota, never()).tryCheck(anyInt(), anyInt());
    }

    @Test
    void anUnreadableSwitchCountsAsOn() {
        when(settings.getBool(SettingKeys.AI_ENABLED, 5)).thenThrow(new IllegalStateException("x"));

        assertThat(preflight.aiEnabled(5)).isTrue();
    }
}
