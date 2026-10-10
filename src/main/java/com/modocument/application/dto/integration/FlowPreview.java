package com.modocument.application.dto.integration;

import java.util.List;
import java.util.Map;

/** {@code POST …/flows/{id}/preview} (ws25): at most 20 rows; nothing is written. */
public record FlowPreview(List<String> columns, List<Map<String, Object>> rows,
                          List<SuggestedMapping> suggestedMapping) {

    public record SuggestedMapping(String from, String to, double score) {
    }
}
