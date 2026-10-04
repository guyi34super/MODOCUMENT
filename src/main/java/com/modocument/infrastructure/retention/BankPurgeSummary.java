package com.modocument.infrastructure.retention;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-bank result of one retention purge run — the counts-per-category
 * payload of the RETENTION_PURGE_COMPLETED audit event (the gate C5
 * "job log" evidence) and of the manual-trigger response.
 */
public record BankPurgeSummary(
        int bankId,
        int uploadedFiles,
        int ingestionJobs,
        int exportArchives,
        int importDetailRows,
        int extractionRawBodies,
        int extractionProcessedFields,
        int docInstances,
        int docContentBlocks,
        int docImageAssets) {

    /** Ordered category map for the audit row / API response. */
    public Map<String, Object> counts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("uploadedFiles", uploadedFiles);
        counts.put("ingestionJobs", ingestionJobs);
        counts.put("exportArchives", exportArchives);
        counts.put("importDetailRows", importDetailRows);
        counts.put("extractionRawBodies", extractionRawBodies);
        counts.put("extractionProcessedFields", extractionProcessedFields);
        counts.put("docInstances", docInstances);
        counts.put("docContentBlocks", docContentBlocks);
        counts.put("docImageAssets", docImageAssets);
        return counts;
    }

    public int total() {
        return uploadedFiles + ingestionJobs + exportArchives + importDetailRows
                + extractionRawBodies + extractionProcessedFields
                + docInstances + docContentBlocks + docImageAssets;
    }
}
