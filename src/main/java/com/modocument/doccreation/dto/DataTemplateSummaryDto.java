package com.modocument.doccreation.dto;

import java.time.LocalDateTime;

/**
 * One entry of {@code GET /api/doccreation/data-templates}
 * (docs/doccreation/data-templates-contract.md §2). {@code sourceTemplateName}
 * is null when the template was made from scratch or its flow template is no
 * longer readable by the caller; {@code usedBy} counts the PDF templates
 * that chose it.
 */
public record DataTemplateSummaryDto(int id,
                                     String name,
                                     String description,
                                     int fieldCount,
                                     int ruleCount,
                                     Integer sourceTemplateId,
                                     String sourceTemplateName,
                                     int usedBy,
                                     LocalDateTime updatedAt) {
}
