package com.modocument.domain.model;

import java.util.Arrays;
import java.util.Locale;

/**
 * How the ingestion parse stage may use the AI (ws23, docs/phase2/contracts.md
 * §4). Stored by NAME in {@code Template.ExtractionMode} (null = inherit the
 * bank's {@code extraction.defaultMode} setting) and in that setting.
 */
public enum ExtractionMode {

    /** AI first; code extraction takes over when the AI is off, unavailable or fails. The default. */
    AI_WITH_CODE_FALLBACK,

    /** Never call the AI: deterministic parsers and the code extractor only. */
    CODE_ONLY,

    /** The AI or nothing: an AI failure fails the job (the pre-phase-2 behaviour). */
    AI_ONLY;

    /** The mode used when neither the template nor the bank chooses one. */
    public static final ExtractionMode DEFAULT = AI_WITH_CODE_FALLBACK;

    /**
     * Parses a client- or database-supplied value: null/blank is null
     * ("inherit"), names match case-insensitively.
     *
     * @throws IllegalArgumentException (a 400 at the API) for anything else
     */
    public static ExtractionMode parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown extraction mode '" + value
                    + "'; use one of " + Arrays.toString(values()) + " or null to inherit "
                    + "the bank default.");
        }
    }

    /** Like {@link #parse} but an unknown value is null (inherit) instead of an error. */
    public static ExtractionMode parseLenient(String value) {
        try {
            return parse(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
