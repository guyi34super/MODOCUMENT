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
 * A document built from a template: its values ({@link #valuesJson}, region
 * key to value), its photos (DocInstanceImage) and generation state; the
 * output is the template PDF with the values drawn in (ws31).
 */
@Entity
@Table(name = "DocInstance")
public class DocInstance {

    public static final String STATUS_DRAFT = "DRAFT";
    /** ws33: the values passed the template's checks; it may be generated. */
    public static final String STATUS_VALIDATED = "VALIDATED";
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
     * True when the last generation's content was composed by the retired AI
     * composer of the Word path. PDF generation draws exactly the values the
     * user entered, so it always writes false. Shown to the approver (C10).
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

    /** Legacy (V292, Word path): form field values keyed by section + fieldKey; no longer written. */
    @Column(name = "FieldValuesJson", columnDefinition = "text")
    private String fieldValuesJson;

    /** The document's values: a JSON object of region key to value (V302). */
    @Column(name = "ValuesJson", columnDefinition = "text")
    private String valuesJson;

    /** FileStore key of the last generated PDF (V302). */
    @Column(name = "GeneratedPdfKey", length = 512)
    private String generatedPdfKey;

    /**
     * The records the document was filled from (V310, ws32): a JSON array of
     * {@code {recordId, templateId, role}} — ids only, never values.
     */
    @Column(name = "SourceRecordsJson", columnDefinition = "text")
    private String sourceRecordsJson;

    /** V322 (ws33): when the document last passed validation (null while a DRAFT). */
    @Column(name = "ValidatedAt")
    private LocalDateTime validatedAt;

    @Column(name = "ValidatedByUserId")
    private Integer validatedByUserId;

    /** V322: sha256 of the validated content; generate/download require it to match. */
    @Column(name = "ValidatedHash", length = 64)
    private String validatedHash;

    /** V322: DocBlueprint.RulesRevision when the document was validated. */
    @Column(name = "ValidatedRulesRevision")
    private Integer validatedRulesRevision;

    /** V322: the last validation result (rule ids, codes, messages; never values). */
    @Column(name = "ValidationJson", columnDefinition = "text")
    private String validationJson;

    /** V334: whether this document must pass validation before generate/download. */
    @org.hibernate.annotations.ColumnDefault("true")
    @Column(name = "ValidationEnabled", nullable = false)
    private boolean validationEnabled = true;

    public DocInstance() {
    }

    @JsonIgnore
    public String getSourceRecordsJson() {
        return sourceRecordsJson;
    }

    public void setSourceRecordsJson(String sourceRecordsJson) {
        this.sourceRecordsJson = sourceRecordsJson;
    }

    @JsonIgnore
    public String getValuesJson() {
        return valuesJson;
    }

    public void setValuesJson(String valuesJson) {
        this.valuesJson = valuesJson;
    }

    @JsonIgnore
    public String getGeneratedPdfKey() {
        return generatedPdfKey;
    }

    public void setGeneratedPdfKey(String generatedPdfKey) {
        this.generatedPdfKey = generatedPdfKey;
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

    public LocalDateTime getValidatedAt() {
        return validatedAt;
    }

    public void setValidatedAt(LocalDateTime validatedAt) {
        this.validatedAt = validatedAt;
    }

    public Integer getValidatedByUserId() {
        return validatedByUserId;
    }

    public void setValidatedByUserId(Integer validatedByUserId) {
        this.validatedByUserId = validatedByUserId;
    }

    public String getValidatedHash() {
        return validatedHash;
    }

    public void setValidatedHash(String validatedHash) {
        this.validatedHash = validatedHash;
    }

    public Integer getValidatedRulesRevision() {
        return validatedRulesRevision;
    }

    public void setValidatedRulesRevision(Integer validatedRulesRevision) {
        this.validatedRulesRevision = validatedRulesRevision;
    }

    public String getValidationJson() {
        return validationJson;
    }

    public void setValidationJson(String validationJson) {
        this.validationJson = validationJson;
    }

    public boolean isValidationEnabled() {
        return validationEnabled;
    }

    public void setValidationEnabled(boolean validationEnabled) {
        this.validationEnabled = validationEnabled;
    }
}
