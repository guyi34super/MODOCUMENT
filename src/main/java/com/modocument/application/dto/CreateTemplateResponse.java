package com.modocument.application.dto;

import java.util.List;

/** Composite result of POST /Templates/CreateWithFields (ws23: with extraction mode and hints). */
public record CreateTemplateResponse(int templateId,
                                     String name,
                                     String description,
                                     List<CreatedField> fields,
                                     String visibility,
                                     String extractionMode) {

    /** A PRIVATE template inheriting the bank's extraction mode (the pre-ws22 shape). */
    public CreateTemplateResponse(int templateId, String name, String description,
                                  List<CreatedField> fields) {
        this(templateId, name, description, fields, "PRIVATE", null);
    }

    public record CreatedField(int templateFieldsId,
                               String fieldName,
                               int templateDataTypeId,
                               String dataType,
                               String piiKind,
                               List<CreatedRule> rules,
                               List<String> aliases,
                               String labelPattern,
                               String valuePattern,
                               List<String> dateFormats) {
    }

    public record CreatedRule(String ruleType, String configJson) {
    }
}
