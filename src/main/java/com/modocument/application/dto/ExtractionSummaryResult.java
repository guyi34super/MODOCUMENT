package com.modocument.application.dto;

import com.modocument.domain.model.FallbackReason;
import com.modocument.domain.model.ParsedBy;

/**
 * Response of {@code GET /api/extraction/jobs/{id}/summary}.
 *
 * @param generatedBy    {@code AI} for the model's prose, {@code CODE} for the
 *                       summary built from the run's counters when the AI was
 *                       unavailable or failed
 * @param fallbackReason why code was used ({@link FallbackReason}); null for AI
 */
public record ExtractionSummaryResult(String summary, ParsedBy generatedBy,
                                      FallbackReason fallbackReason) {

    public static ExtractionSummaryResult ai(String summary) {
        return new ExtractionSummaryResult(summary, ParsedBy.AI, null);
    }

    public static ExtractionSummaryResult code(String summary, FallbackReason reason) {
        return new ExtractionSummaryResult(summary, ParsedBy.CODE, reason);
    }
}
