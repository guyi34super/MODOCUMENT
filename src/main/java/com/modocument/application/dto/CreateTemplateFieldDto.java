package com.modocument.application.dto;

import java.util.List;

/** One field of a custom template: name, data type and its validation rules. */
public class CreateTemplateFieldDto {

    private String fieldName;

    /** TemplateDataType name ("Text"/"Number"/"Date") or its numeric id as a string. */
    private String dataType;

    private List<CreateTemplateRuleDto> rules;

    public CreateTemplateFieldDto() {
    }

    public CreateTemplateFieldDto(String fieldName, String dataType, List<CreateTemplateRuleDto> rules) {
        this.fieldName = fieldName;
        this.dataType = dataType;
        this.rules = rules;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public List<CreateTemplateRuleDto> getRules() {
        return rules;
    }

    public void setRules(List<CreateTemplateRuleDto> rules) {
        this.rules = rules;
    }
}
