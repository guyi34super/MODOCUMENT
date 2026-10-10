package com.modocument.application.dto.integration;

import java.time.Instant;
import java.util.List;

/**
 * A run (ws25, contracts §7.3). The detail ({@code GET …/runs/{id}}) adds
 * {@code events} and {@code deliveries}; the list leaves them null.
 */
public record RunView(long id, Long flowId, String flowName, String direction, String operation,
                      String status, String trigger, Instant scheduledFor, Instant startedAt,
                      Instant finishedAt, int attempt, int recordsIn, int recordsOut,
                      Integer ingestionJobId, String error, List<RunEventView> events,
                      List<DeliveryView> deliveries) {

    public record RunEventView(Instant at, String level, String message) {
    }

    public record DeliveryView(long id, String status, String payloadKind, String payloadRef,
                               int attempts, String lastError, Instant nextAttemptAt,
                               Instant deliveredAt, String remoteReference) {
    }
}
