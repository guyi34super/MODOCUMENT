package com.modocument.application.port.integration;

import com.modocument.domain.model.integration.FlowEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The integration event stream (ws25, SE4 implements). Producers call
 * {@link #publish} INSIDE the transaction of the state change; the
 * implementation appends an event row and the matching OutboundDelivery
 * outbox rows for subscribed EVENT flows. SE2's {@code GET
 * /api/integration/v1/events} pages it with {@link #page}.
 */
public interface IntegrationEventLog {

    void publish(int bankId, FlowEvent type, String entityType, String entityId,
                 Map<String, Object> data);

    /** Events of {@code bankId} with id &gt; {@code afterId}, oldest first. */
    List<Event> page(int bankId, long afterId, int limit);

    record Event(long id, FlowEvent type, Instant occurredAt, String entityType,
                 String entityId, Map<String, Object> data) {
    }
}
