package com.modocument.application.dto;

import com.modocument.domain.model.FallbackReason;
import com.modocument.domain.model.ParsedBy;

/**
 * Who produced a parsed table and how much to trust it (ws23,
 * docs/phase2/contracts.md §3/§4). Persisted on the ingestion job
 * (ParsedBy, ParseStrategy, ParseConfidence, ParseFallbackReason) and shown
 * as the job's provenance badge. Never carries cell values.
 *
 * @param parsedBy       AI, CODE or AI_PARTIAL
 * @param strategy       how: a code strategy name ({@code DELIMITED},
 *                       {@code KEY_VALUE}, …), {@code STRUCTURED} (the
 *                       deterministic fast path), {@code LLM_SINGLE_PASS} or
 *                       {@code LLM_BATCHED}; at most 30 characters
 * @param confidence     the code extractor's 0..1 score; null when nothing was scored
 * @param fallbackReason why code stood in for the AI; null when nothing fell back
 */
public record ParseProvenance(ParsedBy parsedBy,
                              String strategy,
                              Double confidence,
                              FallbackReason fallbackReason) {

    /** Strategy recorded for the deterministic structured fast path. */
    public static final String STRUCTURED = "STRUCTURED";
    /** Strategy recorded for the Excel fast path (spreadsheet cells, no AI). */
    public static final String NATIVE_SHEET = "NATIVE_SHEET";
    /** Strategy recorded for a single-prompt LLM extraction. */
    public static final String LLM_SINGLE_PASS = "LLM_SINGLE_PASS";
    /** Strategy recorded for a batched LLM extraction. */
    public static final String LLM_BATCHED = "LLM_BATCHED";

    public ParseProvenance {
        if (parsedBy == null) {
            throw new IllegalArgumentException("parsedBy is required.");
        }
        if (strategy != null && strategy.length() > 30) {
            strategy = strategy.substring(0, 30);
        }
        if (confidence != null) {
            // numeric(4,3): three decimals, within 0..1.
            confidence = Math.round(Math.max(0, Math.min(1, confidence)) * 1000) / 1000.0;
        }
    }

    public static ParseProvenance ai(String strategy) {
        return new ParseProvenance(ParsedBy.AI, strategy, null, null);
    }

    public static ParseProvenance code(String strategy, Double confidence, FallbackReason reason) {
        return new ParseProvenance(ParsedBy.CODE, strategy, confidence, reason);
    }

    /**
     * Whether code stood in for an AI that was meant to run — what the
     * {@code INGESTION_PARSE_FALLBACK} audit and the fallback-surge alert
     * count. The fast paths (no reason) and code-only mode are not fallbacks.
     */
    public boolean isFallback() {
        return parsedBy != ParsedBy.AI && fallbackReason != null
                && fallbackReason != FallbackReason.MODE_CODE_ONLY;
    }
}
