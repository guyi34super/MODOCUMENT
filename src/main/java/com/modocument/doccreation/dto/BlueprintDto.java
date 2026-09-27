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
                           List<SectionDto> sections,
                           String sectionSource) {

    public static BlueprintDto from(DocBlueprint blueprint, List<DocBlueprintSection> sections) {
        return from(blueprint, sections, null);
    }

    /** {@code sectionSource} ("AI"/"HEURISTIC") is known only at upload time. */
    public static BlueprintDto from(DocBlueprint blueprint, List<DocBlueprintSection> sections,
                                    String sectionSource) {
        return new BlueprintDto(blueprint.getDocBlueprintId(), blueprint.getName(),
                blueprint.getCompanyId(), blueprint.getFlowKey(), blueprint.getOriginalFileName(),
                blueprint.getCreatedDate(), sections.stream().map(SectionDto::from).toList(),
                sectionSource);
    }
}
