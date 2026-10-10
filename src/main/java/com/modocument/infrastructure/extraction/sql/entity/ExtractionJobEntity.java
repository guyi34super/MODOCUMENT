package com.modocument.infrastructure.extraction.sql.entity;

import com.modocument.domain.model.ExtractionJobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * SQL row backing an extraction job. Counters live in ExtractionJobCounter so
 * concurrent worker increments never race a job save. Must stay in sync with
 * V8__extraction_sql_store.sql.
 */
@Entity
@Table(name = "ExtractionJob", indexes = {
        @Index(name = "ix_extractionjob_owneruserid", columnList = "OwnerUserId"),
        @Index(name = "ix_extractionjob_sourceid", columnList = "SourceId"),
        @Index(name = "ix_extractionjob_status", columnList = "Status")})
public class ExtractionJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ExtractionJobId")
    private Long id;

    @Column(name = "OwnerUserId", nullable = false)
    private int ownerUserId;

    /** Tenant bank (V11), stamped on every insert; RLS substrate. */
    @Column(name = "BankId", nullable = false)
    private int bankId;

    /**
     * Always populated (ExtractionJobUseCase.create requires a source); made
     * NOT NULL in V30. Still no FK on purpose — V8 design: deleting a source
     * keeps its historical jobs.
     */
    @Column(name = "SourceId", length = 64, nullable = false)
    private String sourceId;

    @Column(name = "TemplateId")
    private Integer templateId;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "DataSpec")
    private String dataSpec;

    @Enumerated(EnumType.STRING)
    @Column(name = "Status", length = 30, nullable = false)
    private ExtractionJobStatus status;

    @Column(name = "Incremental", nullable = false)
    private boolean incremental;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "ErrorSummary")
    private String errorSummary;

    @Column(name = "CreatedDate")
    private Instant createdDate;

    @Column(name = "UpdatedDate")
    private Instant updatedDate;

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(int ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public Integer getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Integer templateId) {
        this.templateId = templateId;
    }

    public String getDataSpec() {
        return dataSpec;
    }

    public void setDataSpec(String dataSpec) {
        this.dataSpec = dataSpec;
    }

    public ExtractionJobStatus getStatus() {
        return status;
    }

    public void setStatus(ExtractionJobStatus status) {
        this.status = status;
    }

    public boolean isIncremental() {
        return incremental;
    }

    public void setIncremental(boolean incremental) {
        this.incremental = incremental;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public void setErrorSummary(String errorSummary) {
        this.errorSummary = errorSummary;
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Instant createdDate) {
        this.createdDate = createdDate;
    }

    public Instant getUpdatedDate() {
        return updatedDate;
    }

    public void setUpdatedDate(Instant updatedDate) {
        this.updatedDate = updatedDate;
    }
}
