package com.modocument.doccreation.dto;

import com.modocument.doccreation.entity.DocInstance;

import java.time.LocalDateTime;

/** One entry of {@code GET /api/doccreation/instances}; {@code id} repeats {@code instanceId}. */
public record InstanceSummaryDto(int id,
                                 int instanceId,
                                 String title,
                                 int blueprintId,
                                 String blueprintName,
                                 String status,
                                 LocalDateTime lastGeneratedDate,
                                 LocalDateTime createdDate,
                                 boolean aiComposed,
                                 LocalDateTime submittedAt,
                                 LocalDateTime releasedAt,
                                 String reviewComment,
                                 int pageCount) {

    public static InstanceSummaryDto from(DocInstance instance, String blueprintName, int pageCount) {
        return new InstanceSummaryDto(instance.getDocInstanceId(), instance.getDocInstanceId(), instance.getTitle(),
                instance.getBlueprintId(), blueprintName, instance.getStatus(),
                instance.getLastGeneratedDate(), instance.getCreatedDate(),
                instance.isAiComposed(), instance.getSubmittedAt(),
                instance.getReleasedAt(), instance.getReviewComment(), pageCount);
    }
}
