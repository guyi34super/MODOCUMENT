package com.modocument.infrastructure.ai;

import com.modocument.domain.model.FallbackReason;

/**
 * The AI is configured but may not be called right now (ws23): the circuit
 * breaker is open, or the bank turned AI features off. A subtype of
 * {@link AiNotConfiguredException} so every existing "no AI available" path
 * treats it the same way — HTTP endpoints answer 503 (with a Retry-After
 * while the circuit is open), degrade-gracefully callers take their non-AI
 * path, and the ingestion parse stage falls back to code with
 * {@link #reason()}.
 */
public class AiUnavailableException extends AiNotConfiguredException {

    private final FallbackReason reason;
    private final Long retryAfterSeconds;

    public AiUnavailableException(String message, FallbackReason reason, Long retryAfterSeconds) {
        super(message);
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** {@link FallbackReason#CIRCUIT_OPEN} or {@link FallbackReason#AI_DISABLED}. */
    public FallbackReason reason() {
        return reason;
    }

    /** Seconds until the circuit admits a trial call; null when not applicable. */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    static AiUnavailableException circuitOpen(long retryAfterSeconds) {
        return new AiUnavailableException("The AI assistant is temporarily unavailable after "
                + "repeated connection failures. Try again in about "
                + Math.max(1, retryAfterSeconds) + " seconds; document parsing continues "
                + "with code-based extraction meanwhile.",
                FallbackReason.CIRCUIT_OPEN, Math.max(1, retryAfterSeconds));
    }

    static AiUnavailableException disabledForBank() {
        return new AiUnavailableException("AI features are turned off for your bank. "
                + "An administrator can turn them back on in Settings; document parsing "
                + "uses code-based extraction meanwhile.",
                FallbackReason.AI_DISABLED, null);
    }
}
