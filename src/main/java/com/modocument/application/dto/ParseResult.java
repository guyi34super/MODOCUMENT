package com.modocument.application.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/** Output of the parse stage: extracted table plus optional mapping suggestions. */
public record ParseResult(
        TableData table,
        Map<String, String> suggestedMapping
) {
    public ParseResult {
        if (suggestedMapping == null || suggestedMapping.isEmpty()) {
            suggestedMapping = Map.of();
        } else {
            suggestedMapping = new LinkedHashMap<>(suggestedMapping);
        }
    }
}
