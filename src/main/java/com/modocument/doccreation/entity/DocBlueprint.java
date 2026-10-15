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
 * A document template. Since ws31 (V300) a template is the uploaded ORIGINAL
 * PDF (kept unchanged under {@link #originalFileKey}) plus, per version, a
 * template PDF (data removed / edits applied) and an overlay of fill-in
 * regions ({@code DocTemplateRegion}). Rows from the retired Word path are
 * {@link #KIND_DOCX_LEGACY}: hidden from the lists, 410 when opened, removed by
 * the admin purge (docs/doccreation/pdf-templates-contract.md).
 */
@Entity
@Table(name = "DocBlueprint")
public class DocBlueprint {

    /** An exact PDF template: original PDF + region overlay (ws31). */
    public static final String KIND_PDF_OVERLAY = "PDF_OVERLAY";
    /** A template of the retired Word path; only the admin purge touches it. */
    public static final String KIND_DOCX_LEGACY = "DOCX_LEGACY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DocBlueprintId")
    private int docBlueprintId;

    /** The AppUser who owns this blueprint — every read/write is scoped to it. */
    @Column(name = "OwnerUserId")
    private int ownerUserId;

    /** The owner's bank (V11), stamped on create; RLS substrate. */
    @Column(name = "BankId")
    private int bankId;

    /**
     * Free-form company key with no Company table behind it yet; null means
     * the owner acts as their own company.
     */
    @Column(name = "CompanyId", length = 64)
    private String companyId;

    @Column(name = "Name")
    private String name;

    /**
     * Legacy: the stored (timestamped) .docx name under uploads/. For a
     * PDF_OVERLAY template it repeats {@link #originalFileKey} (NOT NULL).
     */
    @Column(name = "FileName")
    private String fileName;

    @Column(name = "OriginalFileName")
    private String originalFileName;

    /** Legacy (Word path): the company flow that handled the blueprint; DEFAULT for PDF templates. */
    @Column(name = "FlowKey", length = 40)
    private String flowKey;

    @Column(name = "CreatedDate")
    private LocalDateTime createdDate;

    @Column(name = "UpdatedDate")
    private LocalDateTime updatedDate;

    /**
     * The version new documents pin (V291). 0 while a PDF template is a
     * DRAFT: its proposed version 1 is under review and not yet confirmed.
     */
    @Column(name = "CurrentVersion", nullable = false)
    private int currentVersion = 1;

    /** PDF_OVERLAY or DOCX_LEGACY (V300; existing rows default to DOCX_LEGACY). */
    @Column(name = "Kind", nullable = false, length = 16)
    private String kind = KIND_DOCX_LEGACY;

    /** FileStore key of the uploaded PDF, stored unchanged (V300). */
    @Column(name = "OriginalFileKey", length = 512)
    private String originalFileKey;

    @Column(name = "PageCount")
    private Integer pageCount;

    /** BLANK or WITH_DATA: how the upload became a template (V300). */
    @Column(name = "UploadFlow", length = 16)
    private String uploadFlow;

    /** The engine's PageInfo list as JSON (V300). */
    @Column(name = "PagesJson", columnDefinition = "text")
    private String pagesJson;

    public DocBlueprint() {
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    @JsonIgnore
    public boolean isLegacy() {
        return !KIND_PDF_OVERLAY.equals(kind);
    }

    public String getOriginalFileKey() {
        return originalFileKey;
    }

    public void setOriginalFileKey(String originalFileKey) {
        this.originalFileKey = originalFileKey;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }

    public String getUploadFlow() {
        return uploadFlow;
    }

    public void setUploadFlow(String uploadFlow) {
        this.uploadFlow = uploadFlow;
    }

    public String getPagesJson() {
        return pagesJson;
    }

    public void setPagesJson(String pagesJson) {
        this.pagesJson = pagesJson;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(int currentVersion) {
        this.currentVersion = currentVersion;
    }

    @JsonIgnore
    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
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
