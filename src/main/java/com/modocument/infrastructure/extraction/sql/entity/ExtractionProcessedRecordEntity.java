package com.modocument.infrastructure.extraction.sql.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * SQL row backing a normalized extracted record. UNIQUE(JobId, DedupKey) is
 * the dedup guarantee saveAllIfNew relies on. Must stay in sync with
 * V8__extraction_sql_store.sql.
 */
@Entity
@Table(name = "ExtractionProcessedRecord",
        indexes = @Index(name = "ix_extractionprocessedrecord_jobid", columnList = "JobId"),
        uniqueConstraints = @UniqueConstraint(name = "ux_extractionprocessedrecord_job_dedup",
                columnNames = {"JobId", "DedupKey"}))
public class ExtractionProcessedRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ExtractionProcessedRecordId")
    private Long id;

    @Column(name = "JobId", length = 64, nullable = false)
    private String jobId;

    /** Tenant bank (V11), stamped on every insert; RLS substrate. */
    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "RawRecordId", length = 64)
    private String rawRecordId;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "FieldsJson")
    private String fieldsJson;

    @Column(name = "DedupKey", length = 512, nullable = false)
    private String dedupKey;

    @Column(name = "ProcessedAt")
    private Instant processedAt;

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

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getRawRecordId() {
        return rawRecordId;
    }

    public void setRawRecordId(String rawRecordId) {
        this.rawRecordId = rawRecordId;
    }

    public String getFieldsJson() {
        return fieldsJson;
    }

    public void setFieldsJson(String fieldsJson) {
        this.fieldsJson = fieldsJson;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }
}
