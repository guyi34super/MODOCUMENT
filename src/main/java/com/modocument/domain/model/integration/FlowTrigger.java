package com.modocument.domain.model.integration;

/** What starts a run of an {@code IntegrationFlow} (ws25). */
public enum FlowTrigger {

    /** Only the "Run now" button / API ({@code INTEGRATION_RUN}). */
    MANUAL,

    /** A cron schedule, never more often than {@code integration.minScheduleInterval}. */
    SCHEDULE,

    /** One or more {@link FlowEvent}s (outbound flows only). */
    EVENT
}
