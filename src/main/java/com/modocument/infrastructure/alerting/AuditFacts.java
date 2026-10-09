package com.modocument.infrastructure.alerting;

import com.modocument.domain.entity.AuditEvent;
import com.modocument.domain.model.AuditOutcome;

import java.time.Instant;
import java.util.UUID;

/**
 * The slice of one AuditEvent row that alert rules look at — no value
 * payloads (old/new values never travel into alerting, so they can never
 * leak into an alert summary). Built from the post-commit
 * {@code AuditRecordedEvent} on the immediate path and by a JPQL constructor
 * projection on the windowed sweep (constructor order is load-bearing).
 */
public record AuditFacts(
        UUID auditEventId,
        Instant occurredAt,
        int bankId,
        String action,
        AuditOutcome outcome,
        Integer actorUserId,
        String actorEmail,
        String actorRole,
        String ip,
        String entityType,
        String entityId) {

    public static AuditFacts of(AuditEvent event) {
        return new AuditFacts(event.getAuditEventId(), event.getOccurredAt(), event.getBankId(),
                event.getAction(), event.getOutcome(), event.getActorUserId(),
                event.getActorEmail(), event.getActorRole(), event.getIp(),
                event.getEntityType(), event.getEntityId());
    }
}
