package com.modocument.application.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * One audit event as the viewer shows it (ws28, contracts §3). The value
 * snapshots are JSON (objects, for the UI's diff view) after the caller's
 * PII masking; {@code actorEmail} is the row's claimed email, else the
 * acting user's current email in the same bank.
 */
public record AuditEventView(UUID id,
                             Instant occurredAt,
                             String action,
                             String outcome,
                             Integer actorUserId,
                             String actorEmail,
                             String actorRole,
                             String entityType,
                             String entityId,
                             String ip,
                             String requestId,
                             JsonNode oldValues,
                             JsonNode newValues) {
}
