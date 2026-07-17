package com.modocument.application.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.IngestionJob;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** API view of an ingestion job with its checkpoints deserialized. */
public record IngestionJobDto(int ingestionJobId,
                              int templateId,
                              String fileName,
                              Integer importHistoryId,
                              String currentStep,
                              String status,
                              String errorMessage,
                              TableData parsedTable,
                              Map<String, String> mapping,
                              TableData mappedRows,
                              List<RowValidationResult> validationResults,
                              Long validationErrorCount,
                              Map<String, Object> exportInfo,
                              LocalDateTime createdDate,
                              LocalDateTime updatedDate) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static IngestionJobDto from(IngestionJob job) {
        TableData parsed = read(job.getParsedTableJson(), new TypeReference<TableData>() {
        });
        Map<String, String> mapping = read(job.getMappingJson(), new TypeReference<Map<String, String>>() {
        });
        TableData mapped = read(job.getMappedRowsJson(), new TypeReference<TableData>() {
        });
        List<RowValidationResult> validation =
                read(job.getValidationResultJson(), new TypeReference<List<RowValidationResult>>() {
                });
        Map<String, Object> exportInfo =
                read(job.getExportInfoJson(), new TypeReference<Map<String, Object>>() {
                });

        Long errorCount = validation == null ? null
                : validation.stream().filter(r -> r.errorMessage() != null).count();

        return new IngestionJobDto(
                job.getIngestionJobId(),
                job.getTemplateId(),
                job.getFileName(),
                job.getImportHistoryId(),
                job.getCurrentStep() == null ? null : job.getCurrentStep().name(),
                job.getStatus() == null ? null : job.getStatus().name(),
                job.getErrorMessage(),
                parsed,
                mapping,
                mapped,
                validation,
                errorCount,
                exportInfo,
                job.getCreatedDate(),
                job.getUpdatedDate());
    }

    private static <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }
}
