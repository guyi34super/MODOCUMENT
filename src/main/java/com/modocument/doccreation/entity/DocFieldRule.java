package com.modocument.doccreation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A validation rule of a PDF template's field (V321,
 * docs/doccreation/field-validation-contract.md §1/§3), per blueprint and
 * region key, not per version. {@link #ruleType} is a
 * {@link com.modocument.doccreation.rules.DocRuleType} name and
 * {@link #configJson} its settings.
 */
@Entity
@Table(name = "DocFieldRule")
public class DocFieldRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DocFieldRuleId")
    private Long docFieldRuleId;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "BlueprintId", nullable = false)
    private int blueprintId;

    @Column(name = "RegionKey", nullable = false, length = 64)
    private String regionKey;

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

    public DocFieldRule() {
    }

    public Long getDocFieldRuleId() {
        return docFieldRuleId;
    }

    public void setDocFieldRuleId(Long docFieldRuleId) {
        this.docFieldRuleId = docFieldRuleId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public int getBlueprintId() {
        return blueprintId;
    }

    public void setBlueprintId(int blueprintId) {
        this.blueprintId = blueprintId;
    }

    public String getRegionKey() {
        return regionKey;
    }

    public void setRegionKey(String regionKey) {
        this.regionKey = regionKey;
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
