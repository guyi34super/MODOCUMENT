package com.modocument.domain.model;

/**
 * Lifecycle of a distributed extraction job. Unlike the synchronous
 * IngestionJob, job state is derived from its tasks: the job completes when no
 * task is pending or leased, COMPLETED_WITH_ERRORS when some tasks died.
 */
public enum ExtractionJobStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    CANCELLED
}
