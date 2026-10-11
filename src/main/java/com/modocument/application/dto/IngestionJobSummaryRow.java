package com.modocument.application.dto;

import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;

import java.time.LocalDateTime;

/**
 * The database projection behind {@link IngestionJobSummary}: the job's
 * scalar columns, its template's name, the small {@code MappingJson} and
 * whether mapped rows exist — never the large checkpoint columns themselves.
 */
public record IngestionJobSummaryRow(int ingestionJobId,
                                     String fileName,
                                     int templateId,
                                     String templateName,
                                     JobStatus status,
                                     JobStep currentStep,
                                     String parsedBy,
                                     String parseFallbackReason,
                                     java.math.BigDecimal parseConfidence,
                                     Integer validationErrorCount,
                                     String mappingJson,
                                     boolean hasMappedRows,
                                     LocalDateTime createdDate,
                                     LocalDateTime updatedDate) {
}
