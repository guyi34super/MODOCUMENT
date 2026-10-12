package com.modocument.domain.document;

import org.springframework.data.annotation.Id;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-source incremental extraction state: web ETag/Last-Modified per URL
 * (conditional GETs short-circuit unchanged pages), the last API cursor, and
 * the last database watermark. Keyed by sourceId.
 */
public class ConnectorState {

    @Id
    private String sourceId;
    private Map<String, String> etags = new LinkedHashMap<>();
    private Map<String, String> lastModified = new LinkedHashMap<>();
    private String lastCursor;
    private String lastWatermark;
    private Instant updatedDate;

    public ConnectorState() {
    }

    public ConnectorState(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public Map<String, String> getEtags() {
        return etags;
    }

    public void setEtags(Map<String, String> etags) {
        this.etags = etags;
    }

    public Map<String, String> getLastModified() {
        return lastModified;
    }

    public void setLastModified(Map<String, String> lastModified) {
        this.lastModified = lastModified;
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
