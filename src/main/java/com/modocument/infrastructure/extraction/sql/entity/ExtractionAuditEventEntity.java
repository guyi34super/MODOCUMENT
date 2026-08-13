package com.modocument.infrastructure.extraction.sql.entity;

import com.modocument.domain.model.AuditAction;
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
 * SQL row backing one extraction audit event (fetch, block, retry, LLM call).
 * Append-only. Must stay in sync with V8__extraction_sql_store.sql.
 */
@Entity
@Table(name = "ExtractionAuditEvent",
        indexes = @Index(name = "ix_extractionauditevent_jobid", columnList = "JobId"))
public class ExtractionAuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ExtractionAuditEventId")
    private Long id;

    @Column(name = "JobId", length = 64, nullable = false)
    private String jobId;

    @Column(name = "SourceId", length = 64)
    private String sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "Action", length = 30, nullable = false)
    private AuditAction action;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "Uri")
    private String uri;

    @Column(name = "Outcome", length = 30)
    private String outcome;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "Detail")
    private String detail;

    @Column(name = "EventTimestamp")
    private Instant timestamp;

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

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public AuditAction getAction() {
        return action;
    }

    public void setAction(AuditAction action) {
        this.action = action;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
