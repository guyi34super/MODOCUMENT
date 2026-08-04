package com.modocument.application.dto;

/** One chunk of data rows sent to the LLM in batched table extraction. */
public record TableRowBatch(
        int batchIndex,
        int totalBatches,
        int startRow,
        int endRow,
        String rowText
) {
}
