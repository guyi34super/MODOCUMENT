package com.modocument.doccreation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * One valid row of a VALIDATED/EXPORTED ingestion job, indexed for search
 * (V310, docs/doccreation/flow-records-contract.md §1). {@link #valuesJson}
 * is the row (template field name to value); {@link #searchText} the values
 * lower-cased, accent-folded and joined with a space. Written only by
 * TemplateRecordIndexer.
 */
@Entity
@Table(name = "TemplateRecord", uniqueConstraints = @UniqueConstraint(
        name = "ux_templaterecord_job_row", columnNames = {"IngestionJobId", "RowIndex"}))
public class TemplateRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "TemplateRecordId")
    private Long templateRecordId;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "TemplateId", nullable = false)
    private int templateId;

    @Column(name = "IngestionJobId", nullable = false)
    private int ingestionJobId;

    @Column(name = "RowIndex", nullable = false)
    private int rowIndex;

    @Column(name = "OwnerUserId", nullable = false)
    private int ownerUserId;

    @Column(name = "ValuesJson", nullable = false, columnDefinition = "text")
    private String valuesJson;

    @Column(name = "SearchText", nullable = false, columnDefinition = "text")
    private String searchText;

    @Column(name = "CreatedAt", nullable = false)
    private LocalDateTime createdAt;

    public TemplateRecord() {
    }

    public Long getTemplateRecordId() {
        return templateRecordId;
    }

    public void setTemplateRecordId(Long templateRecordId) {
        this.templateRecordId = templateRecordId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public int getTemplateId() {
        return templateId;
    }

    public void setTemplateId(int templateId) {
        this.templateId = templateId;
    }

    public int getIngestionJobId() {
        return ingestionJobId;
    }

    public void setIngestionJobId(int ingestionJobId) {
        this.ingestionJobId = ingestionJobId;
    }

    public int getRowIndex() {
        return rowIndex;
    }

    public void setRowIndex(int rowIndex) {
        this.rowIndex = rowIndex;
    }

    public int getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(int ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getValuesJson() {
        return valuesJson;
    }

    public void setValuesJson(String valuesJson) {
        this.valuesJson = valuesJson;
    }

    public String getSearchText() {
        return searchText;
    }

    public void setSearchText(String searchText) {
        this.searchText = searchText;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
