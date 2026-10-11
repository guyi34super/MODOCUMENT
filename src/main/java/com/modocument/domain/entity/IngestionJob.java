package com.modocument.domain.entity;

import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.WizardStep;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A resumable ingestion run. Each pipeline stage persists its result into one
 * of the *Json checkpoint columns and advances CurrentStep, so after a crash
 * or failure the job can continue from the last completed stage.
 */
@Entity
@Table(name = "IngestionJob")
// SCHEMA PARITY with V230 (ck_ingestionjob_parsedby) on the H2 test schema.
@Check(name = "ck_ingestionjob_parsedby", constraints = "ParsedBy in ('AI', 'CODE', 'AI_PARTIAL')")
public class IngestionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "IngestionJobId")
    private int ingestionJobId;

    /** The AppUser who owns this job — every read/write is scoped to it. */
    @Column(name = "OwnerUserId")
    private int ownerUserId;

    /** The owner's bank (V11), stamped on create; RLS substrate. */
    @Column(name = "BankId")
    private int bankId;

    @Column(name = "TemplateId")
    private int templateId;

    @Column(name = "FileName")
    private String fileName;

    @Column(name = "ImportHistoryId")
    private Integer importHistoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "CurrentStep", length = 20)
    private JobStep currentStep = JobStep.UPLOADED;

    @Enumerated(EnumType.STRING)
    @Column(name = "Status", length = 20)
    private JobStatus status = JobStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "WizardStep", length = 20)
    private WizardStep wizardStep;

    @Column(name = "ErrorMessage", length = 2000)
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "ParsedTableJson")
    private String parsedTableJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "MappingJson")
    private String mappingJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "SuggestedMappingJson")
    private String suggestedMappingJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "MappedRowsJson")
    private String mappedRowsJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "ValidationResultJson")
    private String validationResultJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "ExportInfoJson")
    private String exportInfoJson;

    /**
     * ws28 (V285): failing validation results, written by the validate step
     * so the job list never parses ValidationResultJson. Null when the job
     * has not been validated (or was validated before V285) — cleared
     * together with ValidationResultJson.
     */
    @Column(name = "ValidationErrorCount")
    private Integer validationErrorCount;

    /**
     * Optimistic-lock guard. Two requests editing the same job (a resume run
     * and a cell-edit save, say) both write the whole checkpoint set, so the
     * slower writer would otherwise silently revert the other's work. The
     * loser's UPDATE now matches no row and fails with a 409 instead.
     */
    @Version
    @Column(name = "RowVersion")
    private long rowVersion;

    @Column(name = "CreatedDate")
    private LocalDateTime createdDate;

    @Column(name = "UpdatedDate")
    private LocalDateTime updatedDate;

    /**
     * When the retention purge removed this job's customer-data checkpoints
     * (V120, ws14): ParsedTableJson, MappedRowsJson and ValidationResultJson
     * are nulled and this marker is set, keeping the row as an empty report
     * skeleton. Null while the data is still within its retention period.
     */
    @Column(name = "PurgedAt")
    private LocalDateTime purgedAt;

    /*
     * ws23 parse provenance (V230), written by the parse stage: who produced
     * the parsed table (AI / CODE / AI_PARTIAL), how (strategy), how sure the
     * code extractor was (0..1, three decimals) and why code stood in for the
     * AI. Labels and a score only — never cell values. Null for jobs parsed
     * before V230 or not parsed yet.
     */

    @Column(name = "ParsedBy", length = 15)
    private String parsedBy;

    @Column(name = "ParseStrategy", length = 30)
    private String parseStrategy;

    @Column(name = "ParseConfidence", precision = 4, scale = 3)
    private BigDecimal parseConfidence;

    @Column(name = "ParseFallbackReason", length = 30)
    private String parseFallbackReason;

    /**
     * ws25 (V252): who created a machine job — {@code flow:<id>/run:<id>} or
     * {@code api-client:<id>}; null for a person's upload.
     */
    @Column(name = "Origin", length = 100)
    private String origin;

    public IngestionJob() {
    }

    public int getIngestionJobId() {
        return ingestionJobId;
    }

    public void setIngestionJobId(int ingestionJobId) {
        this.ingestionJobId = ingestionJobId;
    }

    @JsonIgnore
    public int getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(int ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    @JsonIgnore
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

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Integer getImportHistoryId() {
        return importHistoryId;
    }

    public void setImportHistoryId(Integer importHistoryId) {
        this.importHistoryId = importHistoryId;
    }

    public JobStep getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(JobStep currentStep) {
        this.currentStep = currentStep;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public WizardStep getWizardStep() {
        return wizardStep;
    }

    public void setWizardStep(WizardStep wizardStep) {
        this.wizardStep = wizardStep;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getParsedTableJson() {
        return parsedTableJson;
    }

    public void setParsedTableJson(String parsedTableJson) {
        this.parsedTableJson = parsedTableJson;
    }

    public String getMappingJson() {
        return mappingJson;
    }

    public void setMappingJson(String mappingJson) {
        this.mappingJson = mappingJson;
    }

    public String getSuggestedMappingJson() {
        return suggestedMappingJson;
    }

    public void setSuggestedMappingJson(String suggestedMappingJson) {
        this.suggestedMappingJson = suggestedMappingJson;
    }

    public String getMappedRowsJson() {
        return mappedRowsJson;
    }

    public void setMappedRowsJson(String mappedRowsJson) {
        this.mappedRowsJson = mappedRowsJson;
    }

    public String getValidationResultJson() {
        return validationResultJson;
    }

    /**
     * Clearing the validation checkpoint (null) clears its
     * {@link #getValidationErrorCount() count} too; a writer that stores
     * results sets the count explicitly.
     */
    public void setValidationResultJson(String validationResultJson) {
        this.validationResultJson = validationResultJson;
        if (validationResultJson == null) {
            this.validationErrorCount = null;
        }
    }

    public Integer getValidationErrorCount() {
        return validationErrorCount;
    }

    public void setValidationErrorCount(Integer validationErrorCount) {
        this.validationErrorCount = validationErrorCount;
    }

    public String getExportInfoJson() {
        return exportInfoJson;
    }

    public void setExportInfoJson(String exportInfoJson) {
        this.exportInfoJson = exportInfoJson;
    }

    public long getRowVersion() {
        return rowVersion;
    }

    public void setRowVersion(long rowVersion) {
        this.rowVersion = rowVersion;
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

    public LocalDateTime getPurgedAt() {
        return purgedAt;
    }

    public void setPurgedAt(LocalDateTime purgedAt) {
        this.purgedAt = purgedAt;
    }

    public String getParsedBy() {
        return parsedBy;
    }

    public void setParsedBy(String parsedBy) {
        this.parsedBy = parsedBy;
    }

    public String getParseStrategy() {
        return parseStrategy;
    }

    public void setParseStrategy(String parseStrategy) {
        this.parseStrategy = parseStrategy;
    }

    public BigDecimal getParseConfidence() {
        return parseConfidence;
    }

    public void setParseConfidence(BigDecimal parseConfidence) {
        this.parseConfidence = parseConfidence;
    }

    public String getParseFallbackReason() {
        return parseFallbackReason;
    }

    public void setParseFallbackReason(String parseFallbackReason) {
        this.parseFallbackReason = parseFallbackReason;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }
}
