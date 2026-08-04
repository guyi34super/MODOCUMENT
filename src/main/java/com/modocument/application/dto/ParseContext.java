package com.modocument.application.dto;

import com.modocument.domain.entity.TemplateFields;

import java.util.List;

/** Inputs for the parse stage: file identity plus template context for combined extraction. */
public record ParseContext(
        String fileName,
        int templateId,
        int ownerUserId,
        List<TemplateFields> templateFields
) {
    public ParseContext {
        templateFields = templateFields == null ? List.of() : List.copyOf(templateFields);
    }
}
