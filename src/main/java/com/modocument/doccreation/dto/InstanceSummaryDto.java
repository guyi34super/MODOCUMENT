package com.modocument.doccreation.dto;

import com.modocument.doccreation.entity.DocInstance;

import java.time.LocalDateTime;

public record InstanceSummaryDto(int instanceId,
                                 String title,
                                 int blueprintId,
                                 String blueprintName,
                                 String status,
                                 LocalDateTime lastGeneratedDate,
                                 LocalDateTime createdDate) {

    public static InstanceSummaryDto from(DocInstance instance, String blueprintName) {
        return new InstanceSummaryDto(instance.getDocInstanceId(), instance.getTitle(),
                instance.getBlueprintId(), blueprintName, instance.getStatus(),
                instance.getLastGeneratedDate(), instance.getCreatedDate());
    }
}
