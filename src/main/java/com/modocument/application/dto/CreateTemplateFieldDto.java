package com.modocument.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** One field of a custom template: name, data type and its validation rules. */
public class CreateTemplateFieldDto {

    /** TemplateFields.FieldName is varchar(255). */
    @NotBlank
    @Size(max = 255)
    private String fieldName;

    /** TemplateDataType name ("Text"/"Number"/"Date") or its numeric id as a string. */
    @NotBlank
    @Size(max = 100)
    private String dataType;

    @Size(max = 100)
    @Valid
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
