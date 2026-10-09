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

    @Transactional(readOnly = true)
    public List<AuditFacts> matching(AlertRule rule, Instant since) {
        StringBuilder jpql = new StringBuilder(
                "select new com.modocument.infrastructure.alerting.AuditFacts("
                        + "e.auditEventId, e.occurredAt, e.bankId, e.action, e.outcome,"
                        + " e.actorUserId, e.actorEmail, e.actorRole, e.ip, e.entityType, e.entityId)"
                        + " from AuditEvent e where e.occurredAt >= :since");
        if (!rule.matchesAnyAction()) {
            jpql.append(" and e.action in :actions");
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
