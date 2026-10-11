package com.modocument.infrastructure.alerting;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Read side of the WINDOWED rules: the audit rows a rule matches within its
 * window, as value-free {@link AuditFacts} projections (old/new values are
 * never loaded). Reads the append-only AuditEvent table directly — the trail
 * is the single event source; there is deliberately no second pipeline.
 *
 * <p>Bounded (ws27): every filter a rule declares is pushed into SQL — the
 * window on BOTH ends ({@code OccurredAt} between {@code since} and
 * {@code until}, served by {@code ix_auditevent_occurredat}, V270), the
 * actions, the outcomes, the actor roles, and the exclusion of the alert
 * framework's own {@code ALERT_*} rows — so a wildcard rule such as
 * {@code admin-action-burst} (any action, role ADMIN) reads an index range
 * of the window instead of scanning the append-only table, and only rows
 * {@link AlertRule#matches} would keep come back.
 *
 * <p>Tenancy: the minute sweep calls this as SYSTEM (cross-bank, by design —
 * a brute-force burst is detected wherever it lands); every resulting alert
 * is then recorded under its own bank. Registered in the
 * {@code TenantContext} call-site registry ({@code AlertSweepJob#runOnce}).
 */
@Component
public class AuditWindowReader {

    /** Safety cap per rule per sweep; hitting it is logged by the sweep. */
    static final int MAX_ROWS = 10_000;

    @PersistenceContext
    private EntityManager entityManager;

    /** The window up to now (the sweep's own clock reading is the upper bound). */
    @Transactional(readOnly = true)
    public List<AuditFacts> matching(AlertRule rule, Instant since) {
        return matching(rule, since, Instant.now());
    }

    /** Rows of the window {@code [since, until]} the rule matches, newest first. */
    @Transactional(readOnly = true)
    public List<AuditFacts> matching(AlertRule rule, Instant since, Instant until) {
        StringBuilder jpql = new StringBuilder(
                "select new com.modocument.infrastructure.alerting.AuditFacts("
                        + "e.auditEventId, e.occurredAt, e.bankId, e.action, e.outcome,"
                        + " e.actorUserId, e.actorEmail, e.actorRole, e.ip, e.entityType, e.entityId)"
                        + " from AuditEvent e where e.occurredAt >= :since and e.occurredAt <= :until");
        if (!rule.matchesAnyAction()) {
            jpql.append(" and e.action in :actions");
        } else {
            // The alert framework's own rows never feed a rule (AlertRule#matches).
            jpql.append(" and e.action not like '"
                    + AlertRule.INTERNAL_ACTION_PREFIX.replace("_", "!_") + "%' escape '!'");
        }
        if (!rule.outcomes().isEmpty()) {
            jpql.append(" and e.outcome in :outcomes");
        }
        if (!rule.actorRoles().isEmpty()) {
            jpql.append(" and e.actorRole in :roles");
        }
        jpql.append(" order by e.occurredAt desc");
        TypedQuery<AuditFacts> query = entityManager.createQuery(jpql.toString(), AuditFacts.class)
                .setParameter("since", since)
                .setParameter("until", until)
                .setMaxResults(MAX_ROWS);
        if (!rule.matchesAnyAction()) {
            query.setParameter("actions", rule.actions());
        }
        if (!rule.outcomes().isEmpty()) {
            query.setParameter("outcomes", rule.outcomes());
        }
        if (!rule.actorRoles().isEmpty()) {
            query.setParameter("roles", rule.actorRoles());
        }
        return query.getResultList();
    }
}
