package com.modocument.infrastructure.alerting;

import com.modocument.domain.entity.AlertLog;
import com.modocument.domain.model.AlertSeverity;

import java.time.Instant;
import java.util.UUID;

/**
 * What a channel delivers: the persisted alert's identity and its
 * PII-scrubbed summary — never audit value payloads. {@link #text()} is the
 * one-line rendering used as the e-mail subject, the webhook's {@code text}
 * (which Slack/Teams-style receivers display as-is) and the log message.
 */
public record AlertMessage(
        int alertId,
        int bankId,
        String rule,
        AlertSeverity severity,
        String summary,
        UUID auditEventId,
        int eventCount,
        Instant createdAt) {

    public static AlertMessage of(AlertLog row) {
        return new AlertMessage(row.getAlertLogId(), row.getBankId(), row.getRuleName(),
                row.getSeverity(), row.getSummary(), row.getAuditEventId(), row.getEventCount(),
                row.getCreatedAt());
    }

    public String text() {
        return "[MoDocument " + severity + "] " + rule + " (bank " + bankId + ", alert #"
                + alertId + "): " + summary;
    }
}
