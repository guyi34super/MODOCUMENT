package com.modocument.application.dto;

import java.util.List;

/** Composite result of POST /Templates/CreateWithFields. */
public record CreateTemplateResponse(int templateId,
                                     String name,
                                     String description,
                                     List<CreatedField> fields,
                                     String visibility) {

    /** A PRIVATE template (the pre-ws22 shape). */
    public CreateTemplateResponse(int templateId, String name, String description,
                                  List<CreatedField> fields) {
        this(templateId, name, description, fields, "PRIVATE");
    }

    public record CreatedField(int templateFieldsId,
                               String fieldName,
                               int templateDataTypeId,
                               String dataType,
                               String piiKind,
                               List<CreatedRule> rules) {
    }

    public record CreatedRule(String ruleType, String configJson) {
    }
}
