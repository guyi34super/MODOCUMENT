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
    /** ws11: the owner sent the generated document for release review. */
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    /** ws11: a different user holding doc:release approved it (B8). */
    public static final String STATUS_RELEASED = "RELEASED";

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

    /** The blueprint section-structure version this document builds on (V291). */
    @Column(name = "BlueprintVersion", nullable = false)
    private int blueprintVersion = 1;

    @Column(name = "Title")
    private String title;

    /**
     * DRAFT | GENERATED | SUBMITTED | RELEASED (V90, ws11). A rejection is
     * not a status: it transitions SUBMITTED back to GENERATED; the decision
     * is recorded in {@link #reviewComment} and the audit trail.
     */
    @Column(name = "Status")
    private String status = STATUS_DRAFT;

    /** Who submitted for release (ws11); null until first submitted. */
    @Column(name = "SubmittedByUserId")
    private Integer submittedByUserId;

    @Column(name = "SubmittedAt")
    private LocalDateTime submittedAt;

    /**
     * The human who released it (ws11, B8) — enforced different from both
     * {@link #ownerUserId} and {@link #submittedByUserId}; the nightly
     * SodScanJob re-verifies that invariant over the data.
     */
    @Column(name = "ReleasedByUserId")
    private Integer releasedByUserId;

    @Column(name = "ReleasedAt")
    private LocalDateTime releasedAt;

    /** The approver's release/reject note (C10: the recorded human review). */
    @Column(name = "ReviewComment", length = 500)
    private String reviewComment;

    /**
     * True when the last generation's content was composed by the AI path
     * (AiDocumentComposer), false when the deterministic engine carried it.
     * Shown to the approver (C10): releasing an AI-composed document is the
     * human check of the model's text and figures.
     */
    @Column(name = "AiComposed", nullable = false)
    private boolean aiComposed;

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

    /** JSON array of form field values keyed by section + fieldKey (V292). */
    @Column(name = "FieldValuesJson", columnDefinition = "text")
    private String fieldValuesJson;

    public DocInstance() {
    }

    public String getFieldValuesJson() {
        return fieldValuesJson;
    }

    public void setFieldValuesJson(String fieldValuesJson) {
        this.fieldValuesJson = fieldValuesJson;
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

    public int getBlueprintVersion() {
        return blueprintVersion;
    }

    public void setBlueprintVersion(int blueprintVersion) {
        this.blueprintVersion = blueprintVersion;
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

    @JsonIgnore
    public Integer getSubmittedByUserId() {
        return submittedByUserId;
    }

    public void setSubmittedByUserId(Integer submittedByUserId) {
        this.submittedByUserId = submittedByUserId;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    @JsonIgnore
    public Integer getReleasedByUserId() {
        return releasedByUserId;
    }

    public void setReleasedByUserId(Integer releasedByUserId) {
        this.releasedByUserId = releasedByUserId;
    }

    public LocalDateTime getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(LocalDateTime releasedAt) {
        this.releasedAt = releasedAt;
    }

    public String getReviewComment() {
        return reviewComment;
    }

    public void setReviewComment(String reviewComment) {
        this.reviewComment = reviewComment;
    }

    public boolean isAiComposed() {
        return aiComposed;
    }

    public void setAiComposed(boolean aiComposed) {
        this.aiComposed = aiComposed;
    }
}
