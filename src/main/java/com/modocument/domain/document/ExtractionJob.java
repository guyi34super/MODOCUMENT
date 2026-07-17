package com.modocument.domain.document;

import com.modocument.domain.model.ExtractionJobStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A distributed extraction run. Progress is derived from its tasks (the task
 * is the checkpoint unit): resuming re-enqueues unfinished tasks, and dedup
 * keys make repeated work a no-op, so a crash never loses or repeats records.
 */
@Document(collection = "extractionJobs")
public class ExtractionJob {

    /** Counter keys used in {@link #counters}. */
    public static final String TASKS_COMPLETED = "tasksCompleted";
    public static final String TASKS_SKIPPED = "tasksSkipped";
    public static final String TASKS_DEAD = "tasksDead";
    public static final String RECORDS_RAW = "recordsRaw";
    public static final String RECORDS_PROCESSED = "recordsProcessed";
    public static final String RECORDS_DEDUPED = "recordsDeduped";

    @Id
    private String id;
    private String sourceId;
    /** Template the results are meant for (optional until bridging). */
    private Integer templateId;
    private String dataSpec;
    private ExtractionJobStatus status = ExtractionJobStatus.PENDING;
    /** Re-extract only content changed since the saved connector state. */
    private boolean incremental;
    private Map<String, Long> counters = new ConcurrentHashMap<>();
    private String errorSummary;
    private Instant createdDate;
    private Instant updatedDate;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public Map<String, Long> getCounters() {
        return counters;
    }

    public void setCounters(Map<String, Long> counters) {
        this.counters = counters;
    }

    public long counter(String key) {
        Long value = counters.get(key);
        return value == null ? 0 : value;
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
