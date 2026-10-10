package com.modocument.application.dto;

import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.ExtractionMode;

import java.util.List;

/**
 * Inputs for the parse stage: file identity plus template context for
 * combined extraction, and (ws23) the job's bank and the template's own
 * extraction mode.
 *
 * @param bankId         the job's bank, for the per-bank extraction settings;
 *                       null falls back to the bound tenant
 * @param extractionMode the template's mode; null inherits the bank default
 */
public record ParseContext(
        String fileName,
        int templateId,
        int ownerUserId,
        List<TemplateFields> templateFields,
        Integer bankId,
        ExtractionMode extractionMode
) {
    public ParseContext {
        templateFields = templateFields == null ? List.of() : List.copyOf(templateFields);
    }

    public ParseContext(String fileName, int templateId, int ownerUserId,
                        List<TemplateFields> templateFields) {
        this(fileName, templateId, ownerUserId, templateFields, null, null);
    }

    /** The same context with the template's mode set to {@code mode}. */
    public ParseContext withExtractionMode(ExtractionMode mode) {
        return new ParseContext(fileName, templateId, ownerUserId, templateFields, bankId, mode);
    }
}
