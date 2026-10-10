package com.modocument.domain.model;

/**
 * Why code, not the AI, produced (some of) a parsed table (ws23). Stored by
 * NAME in {@code IngestionJob.ParseFallbackReason}. The first four are
 * pre-checks that skip the AI before any call; the next five classify an AI
 * call that failed ({@code AiFailureClassifier}); {@link #MODE_CODE_ONLY}
 * records that the template or bank chose code only.
 */
public enum FallbackReason {

    /** The bank turned AI features off ({@code ai.enabled}). */
    AI_DISABLED("AI features are turned off"),

    /** No usable AI endpoint key is configured on the server. */
    NOT_CONFIGURED("AI is not configured"),

    /** The AI circuit breaker is open after repeated transport/server failures. */
    CIRCUIT_OPEN("AI temporarily unavailable"),

    /** The user's AI request quota is spent. */
    QUOTA("AI quota reached"),

    /** The provider refused or failed the call (4xx/5xx, misconfiguration). */
    ERROR("AI error"),

    /** The provider could not be reached or did not answer in time. */
    TIMEOUT("AI did not answer in time"),

    /** The output scanner withheld the completion (gate C10). */
    OUTPUT_REJECTED("AI answer withheld by the output scanner"),

    /** The completion was not a usable table (bad JSON, no columns/rows). */
    INVALID_OUTPUT("AI answer was not a usable table"),

    /** The gateway refused to send the prompt (tenancy, raw identifiers, instruction echo). */
    PROMPT_REFUSED("AI prompt refused by the privacy checks"),

    /** The template or bank uses code-only extraction. */
    MODE_CODE_ONLY("code-only extraction");

    private final String description;

    FallbackReason(String description) {
        this.description = description;
    }

    /** A short phrase for job messages and badges, e.g. "AI quota reached". */
    public String description() {
        return description;
    }
}
