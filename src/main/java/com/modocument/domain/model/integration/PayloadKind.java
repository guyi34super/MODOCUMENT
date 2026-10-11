package com.modocument.domain.model.integration;

/**
 * The admin's "what to send / receive" choice on a flow (ws25). Stored as a
 * comma-separated list of names in {@code IntegrationFlow.PayloadKinds}.
 */
public enum PayloadKind {

    /** Rows that passed validation (masked unless the flow turned masking off). */
    VALIDATED_RECORDS,

    /** Rejected rows with their validation errors. */
    REJECTED_ROWS,

    /** Job status, counts and error message. */
    JOB_STATUS,

    /** Released generated documents (PDF/DOCX). */
    RELEASED_DOCUMENTS,

    /** The job/document event feed. */
    EVENTS
}
