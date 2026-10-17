package com.modocument.doccreation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A validation rule of a data template field (V330): the DocFieldRule
 * columns, keyed by data template and field name. Applied to every PDF
 * field mapped onto that data field (EffectiveRules).
 */
@Entity
@Table(name = "DocDataFieldRule")
public class DocDataFieldRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DocDataFieldRuleId")
    private Long docDataFieldRuleId;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "DataTemplateId", nullable = false)
    private int dataTemplateId;

    @Column(name = "FieldName", nullable = false, length = 64)
    private String fieldName;

    @Column(name = "RuleType", nullable = false, length = 32)
    private String ruleType;

    @Column(name = "ConfigJson", nullable = false, columnDefinition = "text")
    private String configJson = "{}";

    @Column(name = "Message", length = 300)
    private String message;

    @Column(name = "Severity", nullable = false, length = 8)
    private String severity = "ERROR";

    @Column(name = "Origin", nullable = false, length = 16)
    private String origin = "MANUAL";

    @Column(name = "SourceTemplateId")
    private Integer sourceTemplateId;

    @Column(name = "SourceFieldName", length = 255)
    private String sourceFieldName;

    @Column(name = "SortOrder", nullable = false)
    private int sortOrder;

    @Column(name = "UpdatedAt")
    private LocalDateTime updatedAt;

    public DocDataFieldRule() {
    }

    public Long getDocDataFieldRuleId() {
        return docDataFieldRuleId;
    }

    public void setDocDataFieldRuleId(Long docDataFieldRuleId) {
        this.docDataFieldRuleId = docDataFieldRuleId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public int getDataTemplateId() {
        return dataTemplateId;
    }

    public void setDataTemplateId(int dataTemplateId) {
        this.dataTemplateId = dataTemplateId;
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

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public Integer getSourceTemplateId() {
        return sourceTemplateId;
    }

    public void setSourceTemplateId(Integer sourceTemplateId) {
        this.sourceTemplateId = sourceTemplateId;
    }

    public String getSourceFieldName() {
        return sourceFieldName;
    }

    public void setSourceFieldName(String sourceFieldName) {
        this.sourceFieldName = sourceFieldName;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

}
