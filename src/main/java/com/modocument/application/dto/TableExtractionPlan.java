package com.modocument.application.dto;

import java.util.List;

/** Pre-computed batch plan for LLM table extraction. */
public record TableExtractionPlan(
        boolean singlePass,
        String headerText,
        List<String> sampleRows,
        List<TableRowBatch> batches,
        int estimatedDataRows,
        int estimatedColumns,
        String fullDocumentText
) {
    public static TableExtractionPlan singlePass(String fullText, int estimatedRows, int estimatedColumns) {
        return new TableExtractionPlan(
                true, "", List.of(), List.of(), estimatedRows, estimatedColumns, fullText);
    }
}
