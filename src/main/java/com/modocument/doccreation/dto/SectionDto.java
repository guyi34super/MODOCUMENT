package com.modocument.doccreation.dto;

import com.modocument.doccreation.entity.DocBlueprintSection;

import java.util.Arrays;
import java.util.List;

public record SectionDto(int sectionId,
                         int orderIndex,
                         String title,
                         int headingLevel,
                         List<String> keywords) {

    public static SectionDto from(DocBlueprintSection section) {
        return new SectionDto(section.getDocBlueprintSectionId(), section.getOrderIndex(),
                section.getTitle(), section.getHeadingLevel(), splitKeywords(section.getKeywords()));
    }

    static List<String> splitKeywords(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return List.of();
        }
        return Arrays.stream(commaSeparated.split(","))
                .map(String::strip)
                .filter(k -> !k.isEmpty())
                .toList();
    }
}
