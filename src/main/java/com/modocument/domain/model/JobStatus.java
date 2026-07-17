package com.modocument.domain.model;

/** Execution state of an ingestion job. Persisted by NAME. */
public enum JobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
