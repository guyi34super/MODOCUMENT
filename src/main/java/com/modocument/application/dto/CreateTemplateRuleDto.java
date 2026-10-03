package com.modocument.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** One validation rule for a field: rule type by name plus its config payload. */
public class CreateTemplateRuleDto {

    /** RuleType enum name (e.g. "REGEX", "REQUIRED"); never an ordinal. */
    @NotBlank
    @Size(max = 40)
    private String ruleType;

    /** Rule configuration, serialized verbatim into ValidationRules.ConfigJson. */
    @Size(max = 100)
    private Map<String, Object> config;

    public CreateTemplateRuleDto() {
    }

    public CreateTemplateRuleDto(String ruleType, Map<String, Object> config) {
        this.ruleType = ruleType;
        this.config = config;
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
