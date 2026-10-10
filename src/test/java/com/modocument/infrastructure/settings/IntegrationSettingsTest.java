package com.modocument.infrastructure.settings;

import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.domain.model.settings.SettingKeys;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link IntegrationSettings} is a typed facade only: every accessor reads
 * exactly its {@code integration.*} key, for the asked bank, through
 * {@link SettingsReader} — so switching the engine and the client
 * authentication to it changes no behaviour.
 */
class IntegrationSettingsTest {

    private static final int BANK = 42;

    private final SettingsReader reader = mock(SettingsReader.class);
    private final IntegrationSettings settings = new IntegrationSettings(reader);

    @Test
    void eachAccessorReadsItsKeyForTheBank() {
        when(reader.getInt(SettingKeys.INTEGRATION_MAX_ROWS_PER_RUN, BANK)).thenReturn(123);
        when(reader.getDuration(SettingKeys.INTEGRATION_RUN_TIMEOUT, BANK))
                .thenReturn(Duration.ofMinutes(7));
        when(reader.getDuration(SettingKeys.INTEGRATION_MIN_SCHEDULE_INTERVAL, BANK))
                .thenReturn(Duration.ofMinutes(9));
        when(reader.getInt(SettingKeys.INTEGRATION_WEBHOOK_RETRY_ATTEMPTS, BANK)).thenReturn(3);
        when(reader.getBool(SettingKeys.INTEGRATION_OUTBOUND_PII_MASKING, BANK)).thenReturn(false);
        when(reader.getDuration(SettingKeys.INTEGRATION_CLIENT_TOKEN_TTL, BANK))
                .thenReturn(Duration.ofMinutes(6));
        when(reader.getDuration(SettingKeys.INTEGRATION_AGENT_HEARTBEAT_TIMEOUT, BANK))
                .thenReturn(Duration.ofMinutes(4));

        assertThat(settings.maxRowsPerRun(BANK)).isEqualTo(123);
        assertThat(settings.runTimeout(BANK)).isEqualTo(Duration.ofMinutes(7));
        assertThat(settings.minScheduleInterval(BANK)).isEqualTo(Duration.ofMinutes(9));
        assertThat(settings.webhookRetryAttempts(BANK)).isEqualTo(3);
        assertThat(settings.outboundPiiMaskingDefault(BANK)).isFalse();
        assertThat(settings.clientTokenTtl(BANK)).isEqualTo(Duration.ofMinutes(6));
        assertThat(settings.agentHeartbeatTimeout(BANK)).isEqualTo(Duration.ofMinutes(4));
    }

    @Test
    void authMethodsMapToTheirAllowFlags() {
        assertThat(IntegrationSettings.authMethodKey(ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS))
                .isEqualTo(SettingKeys.INTEGRATION_ALLOW_OAUTH2);
        assertThat(IntegrationSettings.authMethodKey(ApiAuthMethod.API_KEY))
                .isEqualTo(SettingKeys.INTEGRATION_ALLOW_API_KEYS);
        assertThat(IntegrationSettings.authMethodKey(ApiAuthMethod.MTLS))
                .isEqualTo(SettingKeys.INTEGRATION_ALLOW_MTLS);

        when(reader.getBool(SettingKeys.INTEGRATION_ALLOW_API_KEYS, BANK)).thenReturn(false);
        when(reader.getBool(SettingKeys.INTEGRATION_ALLOW_MTLS, BANK)).thenReturn(true);
        assertThat(settings.isAuthMethodAllowed(ApiAuthMethod.API_KEY, BANK)).isFalse();
        assertThat(settings.isAuthMethodAllowed(ApiAuthMethod.MTLS, BANK)).isTrue();

        when(reader.getBoolForPlatform(SettingKeys.INTEGRATION_ALLOW_OAUTH2)).thenReturn(true);
        when(reader.getBoolForPlatform(SettingKeys.INTEGRATION_ALLOW_API_KEYS)).thenReturn(false);
        when(reader.getBoolForPlatform(SettingKeys.INTEGRATION_ALLOW_MTLS)).thenReturn(true);
        assertThat(settings.allowedAuthMethods())
                .containsExactlyInAnyOrder(ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS,
                        ApiAuthMethod.MTLS);
    }
}
