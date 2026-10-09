package com.modocument.infrastructure.alerting;

import com.modocument.application.privacy.PiiPatterns;
import com.modocument.domain.model.AlertSeverity;
import com.modocument.domain.model.AuditOutcome;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * One validated alert rule (ws18, SEC-032): which audit rows it watches,
 * how it triggers, how bursts are grouped and deduplicated, and how urgent
 * the resulting alert is. Built from configuration by
 * {@link AlertRuleCatalogue}; immutable.
 *
 * @param actions    audit actions matched; EMPTY means every action
 *                   ({@code "*"} in configuration)
 * @param outcomes   outcome filter; empty = any
 * @param actorRoles ActorRole filter; empty = any
 * @param threshold  WINDOWED only: events per group within {@code window}
 *                   that raise the alert (IMMEDIATE rules fire on one)
 * @param window     WINDOWED: the aggregation window; BOTH triggers: the
 *                   dedupe window — one alert per rule/bank/group per window
 */
public record AlertRule(
        String name,
        String description,
        Trigger trigger,
        Set<String> actions,
        Set<AuditOutcome> outcomes,
        Set<String> actorRoles,
        AlertSeverity severity,
        int threshold,
        Duration window,
        GroupBy groupBy) {

    /** Audit actions written BY the alert framework; never alert on them (no loops). */
    static final String INTERNAL_ACTION_PREFIX = "ALERT_";

    public enum Trigger {
        /** Evaluated post-commit on every audit row (AuditRecordedEvent). */
        IMMEDIATE,
        /** Evaluated by the minute sweep as an aggregate over the window. */
        WINDOWED
    }

    /** What makes two matching audit rows "the same burst". */
    public enum GroupBy {
        /** The acting user (ActorUserId, else the claimed ActorEmail). */
        ACTOR,
        /** The client ip. */
        IP,
        /** The audited entity (EntityType:EntityId). */
        ENTITY,
        /** The whole bank. */
        BANK
    }

    public boolean matchesAnyAction() {
        return actions.isEmpty();
    }

    /** Whether one audit row is in scope for this rule. */
    public boolean matches(AuditFacts facts) {
        if (facts.action() == null || facts.action().startsWith(INTERNAL_ACTION_PREFIX)) {
            return false;
        }
        if (!actions.isEmpty() && !actions.contains(facts.action())) {
            return false;
        }
        if (!outcomes.isEmpty() && !outcomes.contains(facts.outcome())) {
            return false;
        }
        return actorRoles.isEmpty()
                || (facts.actorRole() != null && actorRoles.contains(facts.actorRole()));
    }

    /** The grouping key of a row (raw — hashed before it is persisted). */
    public String groupKey(AuditFacts facts) {
        return switch (groupBy) {
            case ACTOR -> facts.actorUserId() != null
                    ? "user:" + facts.actorUserId()
                    : facts.actorEmail() != null && !facts.actorEmail().isBlank()
                            ? "email:" + facts.actorEmail().trim().toLowerCase(Locale.ROOT)
                            : "anonymous";
            case IP -> facts.ip() == null || facts.ip().isBlank() ? "unknown-ip" : facts.ip();
            case ENTITY -> facts.entityType() + ":" + facts.entityId();
            case BANK -> "bank";
        };
    }

    /**
     * The human label of a group for the alert summary — PII-scrubbed: user
     * ids and ips stay (they are the actionable security signal), an
     * unknown-account email is masked down to its first character
     * ({@code j***@example.com}, the same shape the logs carry).
     */
    public String groupLabel(AuditFacts facts) {
        return switch (groupBy) {
            case ACTOR -> actorLabel(facts);
            case IP -> "ip " + (facts.ip() == null || facts.ip().isBlank() ? "unknown" : facts.ip());
            case ENTITY -> PiiPatterns.scrub(facts.entityType() + ":" + facts.entityId());
            case BANK -> "bank " + facts.bankId();
        };
    }

    /** The scrubbed actor label ({@code user #42}, {@code account j***@example.com}). */
    public static String actorLabel(AuditFacts facts) {
        if (facts.actorUserId() != null) {
            return "user #" + facts.actorUserId();
        }
        if (facts.actorEmail() != null && !facts.actorEmail().isBlank()) {
            return "account " + PiiPatterns.scrub(facts.actorEmail().trim());
        }
        return "an anonymous caller";
    }
}
