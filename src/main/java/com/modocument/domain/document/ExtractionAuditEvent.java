package com.modocument.domain.document;

import com.modocument.domain.model.AuditAction;
import org.springframework.data.annotation.Id;

import java.time.Instant;

/**
 * Audit trail entry: every fetch attempt, compliance decision (robots/domain
 * block, rate-limit wait), retry, LLM call, and bridge/export is recorded so
 * an extraction run is fully accountable after the fact.
 */
public class ExtractionAuditEvent {

    @Id
    private String id;
    private String jobId;
    private String sourceId;
    private AuditAction action;
    private String uri;
    private String outcome;
    private String detail;
    private Instant timestamp;

    public static ExtractionAuditEvent of(String jobId, String sourceId, AuditAction action,
                                          String uri, String outcome, String detail) {
        ExtractionAuditEvent event = new ExtractionAuditEvent();
        event.setJobId(jobId);
        event.setSourceId(sourceId);
        event.setAction(action);
        event.setUri(uri);
        event.setOutcome(outcome);
        event.setDetail(detail);
        event.setTimestamp(Instant.now());
        return event;
    }

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
