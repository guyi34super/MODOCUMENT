package com.modocument.doccreation.dto;

import com.modocument.doccreation.entity.DocBlueprint;

import java.time.LocalDateTime;

public record BlueprintSummaryDto(int blueprintId,
                                  String name,
                                  String companyId,
                                  String flowKey,
                                  int sectionCount,
                                  int instanceCount,
                                  LocalDateTime createdDate,
                                  int currentVersion) {

    public static BlueprintSummaryDto from(DocBlueprint blueprint, int sectionCount, int instanceCount) {
        return new BlueprintSummaryDto(blueprint.getDocBlueprintId(), blueprint.getName(),
                blueprint.getCompanyId(), blueprint.getFlowKey(), sectionCount, instanceCount,
                blueprint.getCreatedDate(), blueprint.getCurrentVersion());
    }
}
