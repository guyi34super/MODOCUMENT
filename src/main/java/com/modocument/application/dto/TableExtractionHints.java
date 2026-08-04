package com.modocument.application.dto;

import java.util.List;

/** Pre-scan metadata passed into the table-extraction LLM prompt. */
public record TableExtractionHints(
        String sourceFormat,
        String extractionSource,
        int expectedColumnCount,
        List<String> candidateHeaderCells,
        int dataRowCount
) {
    public static TableExtractionHints empty(String sourceFormat, String extractionSource) {
        return new TableExtractionHints(sourceFormat, extractionSource, 0, List.of(), 0);
    }
}
