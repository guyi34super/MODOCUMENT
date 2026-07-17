package com.modocument.application.port;

import com.modocument.domain.document.ExtractionAuditEvent;

import java.util.List;

/** Append-only audit trail for extraction activity. */
public interface ExtractionAuditLog {

    void add(ExtractionAuditEvent event);

    /** Cursor-style batch read of a job's audit events in id order. */
    List<ExtractionAuditEvent> findByJob(String jobId, String afterId, int limit);
}
