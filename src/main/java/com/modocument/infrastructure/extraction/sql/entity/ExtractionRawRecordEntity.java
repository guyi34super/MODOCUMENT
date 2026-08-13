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
 * SQL row backing a raw fetched payload. UNIQUE(SourceId, ContentHash) is the
 * dedup guarantee saveIfNew relies on. Must stay in sync with
 * V8__extraction_sql_store.sql.
 */
@Entity
@Table(name = "ExtractionRawRecord",
        indexes = @Index(name = "ix_extractionrawrecord_jobid", columnList = "JobId"),
        uniqueConstraints = @UniqueConstraint(name = "ux_extractionrawrecord_source_hash",
                columnNames = {"SourceId", "ContentHash"}))
public class ExtractionRawRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ExtractionRawRecordId")
    private Long id;

    @Column(name = "JobId", length = 64, nullable = false)
    private String jobId;

    @Column(name = "SourceId", length = 64, nullable = false)
    private String sourceId;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "Uri")
    private String uri;

    @Column(name = "ContentType")
    private String contentType;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "Body")
    private String body;

    @Column(name = "Truncated", nullable = false)
    private boolean truncated;

    @Column(name = "ContentHash", length = 64, nullable = false)
    private String contentHash;

    @Column(name = "HttpStatus")
    private Integer httpStatus;

    @Column(name = "Etag", length = 512)
    private String etag;

    @Column(name = "LastModified")
    private String lastModified;

    @Column(name = "FetchedAt")
    private Instant fetchedAt;

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

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public String getLastModified() {
        return lastModified;
    }

    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
    }
}
