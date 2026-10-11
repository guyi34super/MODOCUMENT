package com.modocument.domain.model.integration;

/** Lifecycle of an {@code OutboundDelivery} outbox row (ws25). */
public enum DeliveryStatus {

    /** Waiting for its first attempt or a retry ({@code NextAttemptAt}). */
    PENDING,

    /** Delivered (2xx / committed / uploaded). */
    DELIVERED,

    /** Gave up after {@code integration.webhookRetryAttempts}; replayable from the dashboard. */
    DEAD
}
