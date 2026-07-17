package com.modocument.domain.model;

/**
 * State of one unit of extraction work. PENDING tasks (whose nextAttemptAt has
 * passed) are claimable; LEASED tasks whose lease expired are reclaimed by the
 * retry sweeper; FAILED tasks retry with backoff until attempts exhaust to
 * DEAD. SKIPPED marks compliant no-ops (robots-blocked, duplicate, cancelled).
 */
public enum ExtractionTaskStatus {
    PENDING,
    LEASED,
    COMPLETED,
    SKIPPED,
    FAILED,
    DEAD
}
