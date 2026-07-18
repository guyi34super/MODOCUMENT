package com.modocument.domain.entity;

import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * A resumable ingestion run. Each pipeline stage persists its result into one
 * of the *Json checkpoint columns and advances CurrentStep, so after a crash
 * or failure the job can continue from the last completed stage.
 */
@Entity
@Table(name = "IngestionJob")
public class IngestionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "IngestionJobId")
    private int ingestionJobId;

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

    @Column(name = "ErrorMessage", length = 2000)
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "ParsedTableJson")
    private String parsedTableJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "MappingJson")
    private String mappingJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "MappedRowsJson")
    private String mappedRowsJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "ValidationResultJson")
    private String validationResultJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "ExportInfoJson")
    private String exportInfoJson;

    @Column(name = "CreatedDate")
    private LocalDateTime createdDate;

    @Column(name = "UpdatedDate")
    private LocalDateTime updatedDate;

    public IngestionJob() {
    }

    public int getIngestionJobId() {
        return ingestionJobId;
    }

    public void setIngestionJobId(int ingestionJobId) {
        this.ingestionJobId = ingestionJobId;
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

    public String getMappedRowsJson() {
        return mappedRowsJson;
    }

    public void setMappedRowsJson(String mappedRowsJson) {
        this.mappedRowsJson = mappedRowsJson;
    }

    public String getValidationResultJson() {
        return validationResultJson;
    }

    public void setValidationResultJson(String validationResultJson) {
        this.validationResultJson = validationResultJson;
    }

    public String getExportInfoJson() {
        return exportInfoJson;
    }

    public void setExportInfoJson(String exportInfoJson) {
        this.exportInfoJson = exportInfoJson;
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
