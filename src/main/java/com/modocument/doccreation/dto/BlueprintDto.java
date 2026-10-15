package com.modocument.doccreation.dto;

import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintSection;
import com.modocument.doccreation.parse.SectionCoverageValidator;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A blueprint with the sections of one of its versions. {@code version} is
 * the version shown, {@code currentVersion} the one new documents use; they
 * differ only for a read-only look at an older version.
 */
public record BlueprintDto(int blueprintId,
                           String name,
                           String companyId,
                           String flowKey,
                           String originalFileName,
                           LocalDateTime createdDate,
                           List<SectionDto> sections,
                           String sectionSource,
                           int version,
                           int currentVersion,
                           int coveragePercent,
                           List<BlueprintGapDto> gaps,
                           List<String> healthIssues) {

    /** {@code sectionSource}: "AI", "HEURISTIC", "MANUAL", or null when unknown. */
    public static BlueprintDto from(DocBlueprint blueprint, List<DocBlueprintSection> sections,
                                    String sectionSource, int version) {
        return from(blueprint, sections, sectionSource, version, SectionCoverageValidator.Result.empty(),
                List.of());
    }

    public static BlueprintDto from(DocBlueprint blueprint, List<DocBlueprintSection> sections,
                                    String sectionSource, int version,
                                    SectionCoverageValidator.Result coverage,
                                    List<String> healthIssues) {
        return new BlueprintDto(blueprint.getDocBlueprintId(), blueprint.getName(),
                blueprint.getCompanyId(), blueprint.getFlowKey(), blueprint.getOriginalFileName(),
                blueprint.getCreatedDate(), sections.stream().map(SectionDto::from).toList(),
                sectionSource, version, blueprint.getCurrentVersion(), coverage.coveragePercent(),
                coverage.gaps().stream()
                        .map(g -> new BlueprintGapDto(g.fromIndex(), g.toIndex(), g.preview()))
                        .toList(),
                healthIssues == null ? List.of() : List.copyOf(healthIssues));
    }
}
