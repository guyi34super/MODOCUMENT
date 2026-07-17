package com.modocument.application.dto;

import java.util.Map;

/** Request body of PUT /ValidationRules/Upsert. */
public class UpsertValidationRuleRequest {

    private int templateId;
    private String fieldName;
    /** RuleType enum name (e.g. "REGEX"); never an ordinal. */
    private String ruleType;
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
