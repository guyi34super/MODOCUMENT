package com.modocument.domain.model.integration;

/** Which way an {@code IntegrationFlow} moves data (ws25). */
public enum FlowDirection {

    /** Bank system → MoDocument (pull records/files into ingestion). */
    INBOUND,

    /**
     * MoDocument → bank system. Data leaves the tenant, so enabling or
     * widening an outbound flow is maker/checker
     * ({@code INTEGRATION_FLOW_ENABLE}).
     */
    OUTBOUND
}
