package com.modocument.infrastructure.ai;

/**
 * LLM table extraction did not produce a usable table (ws23): the reply was
 * not parseable JSON even after the corrective retry, or it had no columns or
 * rows. Replaces the parse stage's plain IllegalStateException so the
 * fallback can be classified ({@code INVALID_OUTPUT} unless the cause chain
 * names a more specific transport/availability failure); still an
 * IllegalStateException, so AI_ONLY jobs fail exactly as before.
 */
public class LlmExtractionFailedException extends IllegalStateException {

    public LlmExtractionFailedException(String message) {
        super(message);
    }

    public LlmExtractionFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
