package com.modocument.doccreation.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * The uploaded source document (.docx) a company builds documents from. The
 * stored file is immutable — sections anchor into it by body-element index,
 * and generation always re-opens a fresh copy (see V9__document_creation.sql).
 */
@Entity
@Table(name = "DocBlueprint")
public class DocBlueprint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DocBlueprintId")
    private int docBlueprintId;

    /** The AppUser who owns this blueprint — every read/write is scoped to it. */
    @Column(name = "OwnerUserId")
    private int ownerUserId;

    /**
     * Free-form company key with no Company table behind it yet; null means
     * the owner acts as their own company. Selects the DocumentCreationFlow.
     */
    @Column(name = "CompanyId")
    private String companyId;

    @Column(name = "Name")
    private String name;

    /** Stored (timestamped) file name under the uploads directory. */
    @Column(name = "FileName")
    private String fileName;

    @Column(name = "OriginalFileName")
    private String originalFileName;

    /** Which DocumentCreationFlow handled this blueprint (DEFAULT unless a company flow matched). */
    @Column(name = "FlowKey")
    private String flowKey;

    @Column(name = "CreatedDate")
    private LocalDateTime createdDate;

    @Column(name = "UpdatedDate")
    private LocalDateTime updatedDate;

    public DocBlueprint() {
    }

    public int getDocBlueprintId() {
        return docBlueprintId;
    }

    public void setDocBlueprintId(int docBlueprintId) {
        this.docBlueprintId = docBlueprintId;
    }

    @JsonIgnore
    public int getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(int ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getCompanyId() {
        return companyId;
    }

    public void setCompanyId(String companyId) {
        this.companyId = companyId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getFlowKey() {
        return flowKey;
    }

    public void setFlowKey(String flowKey) {
        this.flowKey = flowKey;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDateTime createdDate) {
        this.createdDate = createdDate;
    }

    public LocalDateTime getUpdatedDate() {
        return updatedDate;
    }

    public void setUpdatedDate(LocalDateTime updatedDate) {
        this.updatedDate = updatedDate;
    }
}
