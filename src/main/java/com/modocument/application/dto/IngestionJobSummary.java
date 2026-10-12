package com.modocument.application.dto;

import java.time.LocalDateTime;

/**
 * One row of the paged ingestion job list (ws28,
 * docs/optimization/contracts.md §5): what the job list and the dashboard
 * show, and nothing they do not — no checkpoint JSON, so a page of 50 jobs
 * costs kilobytes instead of every job's parsed, mapped and validated
 * tables. The full view stays on {@code GET /api/ingestion/jobs/{id}}.
 *
 * <p>{@code parseConfidence} is the code extractor's 0..1 score (null for
 * AI parses and unparsed jobs); {@code parseLowConfidence} is true when a
 * code-scored table sits below the bank's {@code extraction.code.minConfidence}
 * (null when there is no code score) — the same rule as the full view.
 *
 * <p>{@code validationErrorCount} is the stored count of failing validation
 * results (null: not validated, or validated before V285).
 * {@code unmappedFieldCount} is the number of the template's fields no
 * source column feeds (every field before mapping) — the same answer as the
 * full view's {@code unmappedFields}, computed from the job's small mapping
 * instead of its mapped rows.
 */
public record IngestionJobSummary(int ingestionJobId,
                                  String fileName,
                                  int templateId,
                                  String templateName,
                                  String status,
                                  String currentStep,
                                  String parsedBy,
                                  String parseFallbackReason,
                                  Double parseConfidence,
                                  Boolean parseLowConfidence,
                                  Integer validationErrorCount,
                                  Integer unmappedFieldCount,
                                  LocalDateTime createdDate,
                                  LocalDateTime updatedDate) {
}
