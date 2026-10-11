package com.modocument.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The user dashboard in one response (ws28, docs/optimization/contracts.md
 * §5): {@code GET /api/dashboard/summary} replaces the four full lists the
 * home page used to download just to count them.
 *
 * <pre>
 * {templates,
 *  ingestion:  {total, byStatus:{STATUS:n}, recent:[IngestionJobSummary × 8]},
 *  extraction: {total, running, failed, recent:[ExtractionJobRow × 5]},
 *  imports:    {total, recent:[ImportSummary × 5]}}
 * </pre>
 * Every count is the caller's own (templates: own + bank-shared), the same
 * scope as the lists it replaces.
 */
public record DashboardSummary(long templates,
                               Ingestion ingestion,
                               Extraction extraction,
                               Imports imports) {

    /** The caller's ingestion jobs: total, per status, the 8 newest. */
    public record Ingestion(long total, Map<String, Long> byStatus, List<IngestionJobSummary> recent) {
    }

    /** The caller's extraction jobs: total, running, failed, the 5 newest. */
    public record Extraction(long total, long running, long failed, List<ExtractionJobRow> recent) {
    }

    /** The caller's imports: total and the 5 newest. */
    public record Imports(long total, List<ImportSummary> recent) {
    }

    /** One extraction job of the dashboard: ids, status and dates — no counters or data. */
    public record ExtractionJobRow(String id,
                                   String sourceId,
                                   Integer templateId,
                                   String status,
                                   Instant createdDate,
                                   Instant updatedDate) {
    }
}
