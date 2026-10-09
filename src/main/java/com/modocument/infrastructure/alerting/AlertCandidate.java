package com.modocument.infrastructure.alerting;

import java.util.UUID;

/**
 * An alert a rule wants raised, before dedupe and persistence.
 *
 * @param groupKey     raw grouping key (actor/ip/entity) — only its SHA-256
 *                     hash is ever persisted (AlertLog.DedupeKey)
 * @param summary      human-readable, already built from scrubbed labels;
 *                     {@link AlertService} scrubs and truncates it again
 * @param auditEventId the triggering row (immediate) or the newest row of the
 *                     burst (windowed)
 */
public record AlertCandidate(
        AlertRule rule,
        int bankId,
        String groupKey,
        String summary,
        UUID auditEventId,
        int eventCount) {
}
