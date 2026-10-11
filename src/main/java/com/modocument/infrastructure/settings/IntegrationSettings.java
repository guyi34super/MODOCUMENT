package com.modocument.infrastructure.settings;

import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.integration.ApiAuthMethod;
import com.modocument.domain.model.settings.SettingKeys;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/**
 * Typed access to the Integrations settings tab (ws25/ws26) on top of
 * {@link SettingsReader}, so the flow engine (SE4), machine identity (SE2)
 * and the agent endpoints (SE5) read one bank's integration guardrails
 * without repeating keys and types. Every value is resolved per call from
 * the in-memory settings snapshot (bank override → platform value → shipped
 * default, inside the hard limits of {@code SettingDefinitions}).
 */
@Component
public class IntegrationSettings {

    private final SettingsReader settings;

    public IntegrationSettings(SettingsReader settings) {
        this.settings = settings;
    }

    /** Rows one integration run may pull or push (1–500 000). */
    public int maxRowsPerRun(int bankId) {
        return settings.getInt(SettingKeys.INTEGRATION_MAX_ROWS_PER_RUN, bankId);
    }

    /** A run still busy after this long is failed (1 min–2 h). */
    public Duration runTimeout(int bankId) {
        return settings.getDuration(SettingKeys.INTEGRATION_RUN_TIMEOUT, bankId);
    }

    /** Shortest interval between two scheduled runs of one flow (never below 5 min). */
    public Duration minScheduleInterval(int bankId) {
        return settings.getDuration(SettingKeys.INTEGRATION_MIN_SCHEDULE_INTERVAL, bankId);
    }

    /** Delivery retries before an outbound delivery is dead-lettered (0–10). */
    public int webhookRetryAttempts(int bankId) {
        return settings.getInt(SettingKeys.INTEGRATION_WEBHOOK_RETRY_ATTEMPTS, bankId);
    }

    /** Whether NEW outbound flows mask PII-classified fields (security tier; default true). */
    public boolean outboundPiiMaskingDefault(int bankId) {
        return settings.getBool(SettingKeys.INTEGRATION_OUTBOUND_PII_MASKING, bankId);
    }

    /** Lifetime of an access token issued to an API client (5–15 min, security tier). */
    public Duration clientTokenTtl(int bankId) {
        return settings.getDuration(SettingKeys.INTEGRATION_CLIENT_TOKEN_TTL, bankId);
    }

    /** A Connector Agent silent for this long is shown offline (1–60 min). */
    public Duration agentHeartbeatTimeout(int bankId) {
        return settings.getDuration(SettingKeys.INTEGRATION_AGENT_HEARTBEAT_TIMEOUT, bankId);
    }

    /** The API-client authentication methods the platform allows (platform layer). */
    public Set<ApiAuthMethod> allowedAuthMethods() {
        EnumSet<ApiAuthMethod> allowed = EnumSet.noneOf(ApiAuthMethod.class);
        for (ApiAuthMethod method : ApiAuthMethod.values()) {
            if (isAuthMethodAllowed(method)) {
                allowed.add(method);
            }
        }
        return allowed;
    }

    /** Whether the platform allows this API-client authentication method (platform layer). */
    public boolean isAuthMethodAllowed(ApiAuthMethod method) {
        return settings.getBoolForPlatform(authMethodKey(method));
    }

    /** Whether {@code bankId}'s API clients may use this authentication method. */
    public boolean isAuthMethodAllowed(ApiAuthMethod method, int bankId) {
        return settings.getBool(authMethodKey(method), bankId);
    }

    /** The {@code integration.auth.allow*} key governing one authentication method. */
    public static String authMethodKey(ApiAuthMethod method) {
        return switch (method) {
            case OAUTH2_CLIENT_CREDENTIALS -> SettingKeys.INTEGRATION_ALLOW_OAUTH2;
            case API_KEY -> SettingKeys.INTEGRATION_ALLOW_API_KEYS;
            case MTLS -> SettingKeys.INTEGRATION_ALLOW_MTLS;
        };
    }
}
