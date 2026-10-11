package com.modocument.application.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Output of the parse stage: extracted table plus optional mapping
 * suggestions, and (ws23) the provenance of the table — null when the
 * producer did not say (legacy callers of the two-argument constructor).
 */
public record ParseResult(
        TableData table,
        Map<String, String> suggestedMapping,
        ParseProvenance provenance
) {
    public ParseResult {
        if (suggestedMapping == null || suggestedMapping.isEmpty()) {
            suggestedMapping = Map.of();
        } else {
            suggestedMapping = new LinkedHashMap<>(suggestedMapping);
        }
    }

    public ParseResult(TableData table, Map<String, String> suggestedMapping) {
        this(table, suggestedMapping, null);
    }

    /** The same result with {@code value} as its provenance. */
    public ParseResult withProvenance(ParseProvenance value) {
        return new ParseResult(table, suggestedMapping, value);
    }
}
