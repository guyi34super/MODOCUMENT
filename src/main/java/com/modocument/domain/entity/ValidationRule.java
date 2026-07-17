package com.modocument.domain.entity;

import com.modocument.domain.model.RuleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "ValidationRules")
@IdClass(ValidationRuleId.class)
public class ValidationRule {

    @Id
    @Column(name = "TemplateId")
    private int templateId;

    @Id
    @Column(name = "FieldName")
    private String fieldName;

    @Id
    @Enumerated(EnumType.ORDINAL)
    @Column(name = "RuleType")
    private RuleType ruleType;

    @Column(name = "ConfigJson", nullable = false)
    private String configJson = "";

    public ValidationRule() {
    }

    public ValidationRule(int templateId, String fieldName, RuleType ruleType, String configJson) {
        this.templateId = templateId;
        this.fieldName = fieldName;
        this.ruleType = ruleType;
        this.configJson = configJson;
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

    public RuleType getRuleType() {
        return ruleType;
    }

    public void setRuleType(RuleType ruleType) {
        this.ruleType = ruleType;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }
}
