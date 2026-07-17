package com.modocument.domain.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One normalized flat record extracted from a raw payload — the row shape the
 * template pipeline consumes. Deduplicated per job by dedupKey (hash of the
 * normalized field map).
 */
@Document(collection = "processedRecords")
public class ProcessedRecord {

    @Id
    private String id;
    @Indexed
    private String jobId;
    private String rawRecordId;
    private Map<String, String> fields = new LinkedHashMap<>();
    private String dedupKey;
    private Instant processedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
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

    public Map<String, String> getFields() {
        return fields;
    }

    public void setFields(Map<String, String> fields) {
        this.fields = fields;
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
