package com.modocument.domain.model.integration;

/**
 * Domain events an EVENT-triggered outbound flow can subscribe to (ws25). The
 * producing use case writes an {@code OutboundDelivery} row in the SAME
 * transaction as the state change (transactional outbox).
 */
public enum FlowEvent {

    /** An ingestion job reached VALIDATED. */
    INGESTION_VALIDATED,

    /** An ingestion job reached FAILED. */
    INGESTION_FAILED,

    /** A generated document was released (four-eyes approved). */
    DOC_RELEASED,

    /** An extraction job finished. */
    EXTRACTION_COMPLETED
}
