package com.modocument.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Request body of PUT /ValidationRules/Upsert. */
public class UpsertValidationRuleRequest {

    @Positive
    private int templateId;

    @NotBlank
    @Size(max = 255)
    private String fieldName;

    /** RuleType enum name (e.g. "REGEX"); never an ordinal. */
    @NotBlank
    @Size(max = 40)
    private String ruleType;

    @Size(max = 100)
    private Map<String, Object> config;

    public UpsertValidationRuleRequest() {
    }

    public UpsertValidationRuleRequest(int templateId, String fieldName, String ruleType,
                                       Map<String, Object> config) {
        this.templateId = templateId;
        this.fieldName = fieldName;
        this.ruleType = ruleType;
        this.config = config;
    }

    public int getTemplateId() {
        return templateId;
    }

    public void setTemplateId(int templateId) {
        this.templateId = templateId;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }
}
