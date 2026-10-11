package com.modocument.application.dto;

import com.modocument.domain.entity.IngestionJob;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * API view of an ingestion job with its checkpoints deserialized and (ws23)
 * its parse provenance: {@code parsedBy} AI/CODE/AI_PARTIAL,
 * {@code parseStrategy}, {@code parseConfidence} (0..1, code extractions
 * only) and {@code parseFallbackReason} (a {@code FallbackReason} name) — all
 * null until the job is parsed. Wave 4 adds {@code parseMinConfidence} (the
 * bank's current {@code extraction.code.minConfidence}) and
 * {@code parseLowConfidence} (a code-scored table below it — only CODE_ONLY
 * lets one through, as LOW_CONFIDENCE), so the job page can show its
 * low-confidence callout; both null when no code score exists.
 */
public record IngestionJobDto(int ingestionJobId,
                              int templateId,
                              String fileName,
                              Integer importHistoryId,
                              String currentStep,
                              String status,
                              String wizardStep,
                              String errorMessage,
                              TableData parsedTable,
                              Map<String, String> suggestedMapping,
                              Map<String, String> mapping,
                              TableData mappedRows,
                              List<RowValidationResult> validationResults,
                              Long validationErrorCount,
                              List<String> unmappedFields,
                              List<String> reachableSteps,
                              Map<String, Object> exportInfo,
                              LocalDateTime createdDate,
                              LocalDateTime updatedDate,
                              String parsedBy,
                              String parseStrategy,
                              Double parseConfidence,
                              String parseFallbackReason,
                              Double parseMinConfidence,
                              Boolean parseLowConfidence) {

    /** A view with parse provenance but no confidence threshold (ws23 shape). */
    public IngestionJobDto(int ingestionJobId, int templateId, String fileName,
                           Integer importHistoryId, String currentStep, String status,
                           String wizardStep, String errorMessage, TableData parsedTable,
                           Map<String, String> suggestedMapping, Map<String, String> mapping,
                           TableData mappedRows, List<RowValidationResult> validationResults,
                           Long validationErrorCount, List<String> unmappedFields,
                           List<String> reachableSteps, Map<String, Object> exportInfo,
                           LocalDateTime createdDate, LocalDateTime updatedDate,
                           String parsedBy, String parseStrategy, Double parseConfidence,
                           String parseFallbackReason) {
        this(ingestionJobId, templateId, fileName, importHistoryId, currentStep, status,
                wizardStep, errorMessage, parsedTable, suggestedMapping, mapping, mappedRows,
                validationResults, validationErrorCount, unmappedFields, reachableSteps,
                exportInfo, createdDate, updatedDate, parsedBy, parseStrategy, parseConfidence,
                parseFallbackReason, null, null);
    }

    /** A view without parse provenance (pre-ws23 shape). */
    public IngestionJobDto(int ingestionJobId, int templateId, String fileName,
                           Integer importHistoryId, String currentStep, String status,
                           String wizardStep, String errorMessage, TableData parsedTable,
                           Map<String, String> suggestedMapping, Map<String, String> mapping,
                           TableData mappedRows, List<RowValidationResult> validationResults,
                           Long validationErrorCount, List<String> unmappedFields,
                           List<String> reachableSteps, Map<String, Object> exportInfo,
                           LocalDateTime createdDate, LocalDateTime updatedDate) {
        this(ingestionJobId, templateId, fileName, importHistoryId, currentStep, status,
                wizardStep, errorMessage, parsedTable, suggestedMapping, mapping, mappedRows,
                validationResults, validationErrorCount, unmappedFields, reachableSteps,
                exportInfo, createdDate, updatedDate, null, null, null, null, null, null);
    }

    /**
     * This view judged against {@code minConfidence} (the bank's
     * {@code extraction.code.minConfidence}): sets {@code parseMinConfidence}
     * and, when code scored the table, {@code parseLowConfidence}. A view
     * without a code score (AI, fast paths, not parsed) is returned as is.
     */
    public IngestionJobDto withConfidenceThreshold(Double minConfidence) {
        if (parseConfidence == null || minConfidence == null || "AI".equals(parsedBy)) {
            return this;
        }
        return new IngestionJobDto(ingestionJobId, templateId, fileName, importHistoryId,
                currentStep, status, wizardStep, errorMessage, parsedTable, suggestedMapping,
                mapping, mappedRows, validationResults, validationErrorCount, unmappedFields,
                reachableSteps, exportInfo, createdDate, updatedDate, parsedBy, parseStrategy,
                parseConfidence, parseFallbackReason, minConfidence,
                parseConfidence < minConfidence);
    }

    /**
     * {@code unmappedFields} and {@code reachableSteps} are computed by
     * {@link com.modocument.application.usecase.IngestionJobUseCase#reachableSteps}
     * — this factory has no repository access, and the wizard rule must have
     * exactly one home. Build views through {@code IngestionJobUseCase.view}.
     */
    public static IngestionJobDto from(IngestionJob job,
                                       List<String> unmappedFields,
                                       List<String> reachableSteps) {
        return from(IngestionJobCheckpoints.of(job), unmappedFields, reachableSteps);
    }

    /**
     * ws28: the view from checkpoints already parsed for this request — the
     * gates that computed {@code unmappedFields} / {@code reachableSteps}
     * read the same memo, so no JSON column is parsed twice.
     */
    public static IngestionJobDto from(IngestionJobCheckpoints checkpoints,
                                       List<String> unmappedFields,
                                       List<String> reachableSteps) {
        IngestionJob job = checkpoints.job();
        return new IngestionJobDto(
                job.getIngestionJobId(),
                job.getTemplateId(),
                job.getFileName(),
                job.getImportHistoryId(),
                job.getCurrentStep() == null ? null : job.getCurrentStep().name(),
                job.getStatus() == null ? null : job.getStatus().name(),
                job.getWizardStep() == null ? null : job.getWizardStep().name(),
                job.getErrorMessage(),
                checkpoints.parsedTable(),
                checkpoints.suggestedMapping(),
                checkpoints.mapping(),
                checkpoints.mappedRows(),
                checkpoints.validationResults(),
                checkpoints.validationErrorCount(),
                unmappedFields,
                reachableSteps,
                checkpoints.exportInfo(),
                job.getCreatedDate(),
                job.getUpdatedDate(),
                job.getParsedBy(),
                job.getParseStrategy(),
                job.getParseConfidence() == null ? null : job.getParseConfidence().doubleValue(),
                job.getParseFallbackReason());
    }
}
