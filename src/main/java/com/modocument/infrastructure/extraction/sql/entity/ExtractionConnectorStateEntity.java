package com.modocument.infrastructure.extraction.sql.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * SQL row backing a source's incremental crawl state (etags, cursors,
 * watermarks). Keyed by the source id, like the Mongo document. Must stay in
 * sync with V8__extraction_sql_store.sql.
 */
@Entity
@Table(name = "ExtractionConnectorState")
public class ExtractionConnectorStateEntity {

    @Id
    @Column(name = "SourceId", length = 64)
    private String sourceId;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "EtagsJson")
    private String etagsJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "LastModifiedJson")
    private String lastModifiedJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "LastCursor")
    private String lastCursor;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "LastWatermark")
    private String lastWatermark;

    @Column(name = "UpdatedDate")
    private Instant updatedDate;

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getEtagsJson() {
        return etagsJson;
    }

    public void setEtagsJson(String etagsJson) {
        this.etagsJson = etagsJson;
    }

    public String getLastModifiedJson() {
        return lastModifiedJson;
    }

    public void setLastModifiedJson(String lastModifiedJson) {
        this.lastModifiedJson = lastModifiedJson;
    }

    public String getLastCursor() {
        return lastCursor;
    }

    public void setLastCursor(String lastCursor) {
        this.lastCursor = lastCursor;
    }

    public String getLastWatermark() {
        return lastWatermark;
    }

    public void setLastWatermark(String lastWatermark) {
        this.lastWatermark = lastWatermark;
    }

    public Instant getUpdatedDate() {
        return updatedDate;
    }

    public void setUpdatedDate(Instant updatedDate) {
        this.updatedDate = updatedDate;
    }
}
