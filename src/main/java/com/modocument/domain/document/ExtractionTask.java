package com.modocument.domain.document;

import com.modocument.domain.model.ExtractionTaskStatus;
import com.modocument.domain.model.ExtractionTaskType;
import org.springframework.data.annotation.Id;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One claimable unit of extraction work — the checkpoint unit of a job.
 * Workers claim tasks with a time-boxed lease; expired leases are reclaimed,
 * failures retry with exponential backoff until attempts exhaust to DEAD.
 * The (jobId, dedupKey) pair is unique so the same page/API page/DB batch is
 * never enqueued twice for a job.
 */
public class ExtractionTask {

    @Id
    private String id;
    private String jobId;
    private String sourceId;
    private ExtractionTaskType type;
    private Map<String, String> payload = new LinkedHashMap<>();
    private String dedupKey;
    private ExtractionTaskStatus status = ExtractionTaskStatus.PENDING;
    private int attempts;
    private int maxAttempts;
    /** Earliest time the task may be (re)claimed; backoff pushes it forward. */
    private Instant nextAttemptAt = Instant.EPOCH;
    private String leaseOwner;
    private Instant leaseExpiresAt;
    /**
     * When set, only workers on this node may claim the task. Used for DB
     * batches whose per-run credentials live only in that node's memory.
     */
    private String nodeAffinity;
    private String lastError;
    private Instant createdDate;
    private Instant updatedDate;

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

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public ExtractionTaskType getType() {
        return type;
    }

    public void setType(ExtractionTaskType type) {
        this.type = type;
    }

    public Map<String, String> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, String> payload) {
        this.payload = payload;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public ExtractionTaskStatus getStatus() {
        return status;
    }

    public void setStatus(ExtractionTaskStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public String getNodeAffinity() {
        return nodeAffinity;
    }

    public void setNodeAffinity(String nodeAffinity) {
        this.nodeAffinity = nodeAffinity;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
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
