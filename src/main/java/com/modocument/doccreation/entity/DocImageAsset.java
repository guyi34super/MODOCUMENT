package com.modocument.doccreation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * An uploaded image belonging to a document instance. Invariant (kept by
 * DocInstanceUseCase): {@code assignedSectionId} is set iff exactly one IMAGE
 * content block references this asset, and that block's section matches.
 * Null = the "Unsorted" tray.
 */
@Entity
@Table(name = "DocImageAsset")
public class DocImageAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DocImageAssetId")
    private int docImageAssetId;

    @Column(name = "InstanceId")
    private int instanceId;

    /** The owning root's bank (V11), stamped on create; RLS substrate. */
    @Column(name = "BankId")
    private int bankId;

    /** Stored (timestamped) file name under the uploads directory. */
    @Column(name = "FileName")
    private String fileName;

    @Column(name = "OriginalFileName")
    private String originalFileName;

    @Column(name = "ContentType")
    private String contentType;

    @Column(name = "AssignedSectionId")
    private Integer assignedSectionId;

    @Column(name = "UploadedDate")
    private LocalDateTime uploadedDate;

    public DocImageAsset() {
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public int getDocImageAssetId() {
        return docImageAssetId;
    }

    public void setDocImageAssetId(int docImageAssetId) {
        this.docImageAssetId = docImageAssetId;
    }

    public int getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(int instanceId) {
        this.instanceId = instanceId;
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

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Integer getAssignedSectionId() {
        return assignedSectionId;
    }

    public void setAssignedSectionId(Integer assignedSectionId) {
        this.assignedSectionId = assignedSectionId;
    }

    public LocalDateTime getUploadedDate() {
        return uploadedDate;
    }

    public void setUploadedDate(LocalDateTime uploadedDate) {
        this.uploadedDate = uploadedDate;
    }
}
