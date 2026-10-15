package com.modocument.doccreation.dto;

import com.modocument.doccreation.entity.DocBlueprintSection;
import com.modocument.doccreation.template.TemplateFieldSchema;

import java.util.Arrays;
import java.util.List;

public record SectionDto(int sectionId,
                         int orderIndex,
                         String title,
                         int headingLevel,
                         List<String> keywords,
                         int anchorParagraphIndex,
                         String anchorText,
                         String sectionKind,
                         List<TemplateFieldDto> fields) {

    public static SectionDto from(DocBlueprintSection section) {
        return from(section, null);
    }

    public static SectionDto from(DocBlueprintSection section, java.util.Map<String, String> fieldValues) {
        List<TemplateFieldSchema.FieldDef> defs = TemplateFieldSchema.parse(section.getFieldSchema());
        List<TemplateFieldDto> fields = defs.stream()
                .map(def -> new TemplateFieldDto(def.fieldKey(), def.label(), def.anchorParagraphIndex(),
                        def.normalizedStyle(),
                        fieldValues == null ? null
                                : fieldValues.get(section.getDocBlueprintSectionId() + ":" + def.fieldKey())))
                .toList();
        String kind = section.getSectionKind() == null ? "NARRATIVE" : section.getSectionKind();
        return new SectionDto(section.getDocBlueprintSectionId(), section.getOrderIndex(),
                section.getTitle(), section.getHeadingLevel(), splitKeywords(section.getKeywords()),
                section.getAnchorParagraphIndex(), section.getAnchorText(), kind, fields);
    }

    public static List<String> splitKeywords(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return List.of();
        }
        return Arrays.stream(commaSeparated.split(","))
                .map(String::strip)
                .filter(k -> !k.isEmpty())
                .toList();
    }
}
