package com.modocument.doccreation.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** A document being built from a blueprint: per-section content plus generation state. */
@Entity
@Table(name = "DocInstance")
public class DocInstance {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_GENERATED = "GENERATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DocInstanceId")
    private int docInstanceId;

    /** The AppUser who owns this document — every read/write is scoped to it. */
    @Column(name = "OwnerUserId")
    private int ownerUserId;

    /** The owner's bank (V11), stamped on create; RLS substrate. */
    @Column(name = "BankId")
    private int bankId;

    @Column(name = "BlueprintId")
    private int blueprintId;

    @Column(name = "Title")
    private String title;

    /** DRAFT | GENERATED. */
    @Column(name = "Status")
    private String status = STATUS_DRAFT;

    /** Stored export file names of the last generation, if any. */
    @Column(name = "LastGeneratedDocx")
    private String lastGeneratedDocx;

    @Column(name = "LastGeneratedPdf")
    private String lastGeneratedPdf;

    @Column(name = "LastGeneratedDate")
    private LocalDateTime lastGeneratedDate;

    @Column(name = "CreatedDate")
    private LocalDateTime createdDate;

    @Column(name = "UpdatedDate")
    private LocalDateTime updatedDate;

    /**
     * When the retention purge removed this instance's customer content
     * (V120, ws14): content blocks, image assets (+ files) and generated
     * exports are gone, the shell (title/status/dates) remains. Null while
     * the content is still within its retention period.
     */
    @Column(name = "ContentPurgedAt")
    private LocalDateTime contentPurgedAt;

    public DocInstance() {
    }

    @JsonIgnore
    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public int getDocInstanceId() {
        return docInstanceId;
    }

    public void setDocInstanceId(int docInstanceId) {
        this.docInstanceId = docInstanceId;
    }

    @JsonIgnore
    public int getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(int ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public int getBlueprintId() {
        return blueprintId;
    }

    public void setBlueprintId(int blueprintId) {
        this.blueprintId = blueprintId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLastGeneratedDocx() {
        return lastGeneratedDocx;
    }

    public void setLastGeneratedDocx(String lastGeneratedDocx) {
        this.lastGeneratedDocx = lastGeneratedDocx;
    }

    public String getLastGeneratedPdf() {
        return lastGeneratedPdf;
    }

    public void setLastGeneratedPdf(String lastGeneratedPdf) {
        this.lastGeneratedPdf = lastGeneratedPdf;
    }

    public LocalDateTime getLastGeneratedDate() {
        return lastGeneratedDate;
    }

    public void setLastGeneratedDate(LocalDateTime lastGeneratedDate) {
        this.lastGeneratedDate = lastGeneratedDate;
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

    public LocalDateTime getContentPurgedAt() {
        return contentPurgedAt;
    }

    public void setContentPurgedAt(LocalDateTime contentPurgedAt) {
        this.contentPurgedAt = contentPurgedAt;
    }
}
