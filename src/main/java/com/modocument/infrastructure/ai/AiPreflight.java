package com.modocument.infrastructure.ai;

import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.SettingsReader;
import com.modocument.domain.model.FallbackReason;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.ratelimit.LlmQuotaGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The checks that send AI_WITH_CODE_FALLBACK work straight to code before
 * any prompt is built (ws23), cheapest first:
 * <ol>
 *   <li>the bank's {@code ai.enabled} switch → AI_DISABLED;</li>
 *   <li>an AI endpoint key is configured → NOT_CONFIGURED;</li>
 *   <li>the {@link AiCircuitBreaker} would admit a call → CIRCUIT_OPEN;</li>
 *   <li>one request from the user's LLM quota → QUOTA (last, because it
 *       spends a permit).</li>
 * </ol>
 */
@Component
public class AiPreflight {

    private static final Logger log = LoggerFactory.getLogger(AiPreflight.class);

    private final SettingsReader settings;
    private final AiFilesService aiFiles;
    private final AiCircuitBreaker circuitBreaker;
    private final LlmQuotaGate quota;

    public AiPreflight(SettingsReader settings, AiFilesService aiFiles,
                       AiCircuitBreaker circuitBreaker, LlmQuotaGate quota) {
        this.settings = settings;
        this.aiFiles = aiFiles;
        this.circuitBreaker = circuitBreaker;
        this.quota = quota;
    }

    /**
     * Empty when the AI may be tried for this user's document now; otherwise
     * why not.
     *
     * @param bankId the document's bank; null reads the platform layer
     */
    public Optional<FallbackReason> check(Integer bankId, int userId) {
        if (!aiEnabled(bankId)) {
            return Optional.of(FallbackReason.AI_DISABLED);
        }
        if (!aiFiles.isConfigured()) {
            return Optional.of(FallbackReason.NOT_CONFIGURED);
        }
        if (!circuitBreaker.allow()) {
            return Optional.of(FallbackReason.CIRCUIT_OPEN);
        }
        if (!quota.tryAcquire(userId, bankId)) {
            return Optional.of(FallbackReason.QUOTA);
        }
        return Optional.empty();
    }

    /** The bank's {@code ai.enabled} switch (on when it cannot be read). */
    public boolean aiEnabled(Integer bankId) {
        try {
            return bankId == null
                    ? settings.getBoolForPlatform(SettingKeys.AI_ENABLED)
                    : settings.getBool(SettingKeys.AI_ENABLED, bankId);
        } catch (RuntimeException e) {
            log.warn("Could not read ai.enabled for bank {}: {}", bankId, e.getClass().getSimpleName());
            return true;
        }
    }
}
