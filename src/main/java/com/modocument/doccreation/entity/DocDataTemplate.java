package com.modocument.doccreation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A reusable validation template of document creation (V330,
 * docs/doccreation/data-templates-contract.md §1): the fields a document
 * collects and their rules ({@link DocDataField}, {@link DocDataFieldRule}),
 * chosen by PDF templates after upload and mapped onto their fields.
 */
@Entity
@Table(name = "DocDataTemplate")
public class DocDataTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DocDataTemplateId")
    private int docDataTemplateId;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "OwnerUserId", nullable = false)
    private int ownerUserId;

    @Column(name = "Name", nullable = false, length = 200)
    private String name;

    @Column(name = "Description", length = 1000)
    private String description;

    @Column(name = "SourceTemplateId")
    private Integer sourceTemplateId;

    @Column(name = "RulesRevision", nullable = false)
    private int rulesRevision;

    @Column(name = "CreatedAt", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "UpdatedAt")
    private LocalDateTime updatedAt;

    public DocDataTemplate() {
    }

    public int getDocDataTemplateId() {
        return docDataTemplateId;
    }

    public void setDocDataTemplateId(int docDataTemplateId) {
        this.docDataTemplateId = docDataTemplateId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public int getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(int ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getSourceTemplateId() {
        return sourceTemplateId;
    }

    public void setSourceTemplateId(Integer sourceTemplateId) {
        this.sourceTemplateId = sourceTemplateId;
    }

    public int getRulesRevision() {
        return rulesRevision;
    }

    public void setRulesRevision(int rulesRevision) {
        this.rulesRevision = rulesRevision;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

}
