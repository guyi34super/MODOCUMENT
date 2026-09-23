package com.modocument.doccreation.dto;

import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintSection;

import java.time.LocalDateTime;
import java.util.List;

public record BlueprintDto(int blueprintId,
                           String name,
                           String companyId,
                           String flowKey,
                           String originalFileName,
                           LocalDateTime createdDate,
                           List<SectionDto> sections) {

    public static BlueprintDto from(DocBlueprint blueprint, List<DocBlueprintSection> sections) {
        return new BlueprintDto(blueprint.getDocBlueprintId(), blueprint.getName(),
                blueprint.getCompanyId(), blueprint.getFlowKey(), blueprint.getOriginalFileName(),
                blueprint.getCreatedDate(), sections.stream().map(SectionDto::from).toList());
    }
}
