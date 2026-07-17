package com.modocument.application.dto;

import java.util.List;

/** Composite result of POST /Templates/CreateWithFields. */
public record CreateTemplateResponse(int templateId,
                                     String name,
                                     String description,
                                     List<CreatedField> fields) {

    public record CreatedField(int templateFieldsId,
                               String fieldName,
                               int templateDataTypeId,
                               String dataType,
                               List<CreatedRule> rules) {
    }

    public record CreatedRule(String ruleType, String configJson) {
    }
}
