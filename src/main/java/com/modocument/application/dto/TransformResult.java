package com.modocument.application.dto;

/**
 * Result of the transformation: the extracted JSON string, and (ws23) who
 * produced it — {@code parsedBy} AI or CODE, the strategy, the code
 * extractor's confidence and why code stood in for the AI (all null on the
 * plain AI path's legacy callers).
 */
public class TransformResult {

    private String jsonPayload = "";
    private String parsedBy;
    private String parseStrategy;
    private Double parseConfidence;
    private String parseFallbackReason;

    public TransformResult() {
    }

    public TransformResult(String jsonPayload) {
        this.jsonPayload = jsonPayload;
    }

    public String getJsonPayload() {
        return jsonPayload;
    }

    public void setJsonPayload(String jsonPayload) {
        this.jsonPayload = jsonPayload;
    }

    /** The same payload with its provenance. */
    public TransformResult withProvenance(ParseProvenance provenance) {
        if (provenance != null) {
            this.parsedBy = provenance.parsedBy().name();
            this.parseStrategy = provenance.strategy();
            this.parseConfidence = provenance.confidence();
            this.parseFallbackReason = provenance.fallbackReason() == null
                    ? null : provenance.fallbackReason().name();
        }
        return this;
    }

    public String getParsedBy() {
        return parsedBy;
    }

    public String getParseStrategy() {
        return parseStrategy;
    }

    public Double getParseConfidence() {
        return parseConfidence;
    }

    public String getParseFallbackReason() {
        return parseFallbackReason;
    }
}
