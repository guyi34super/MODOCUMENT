package com.modocument.infrastructure.alerting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.audit.AuditActions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * <p>Aggregated rows (OBS-01): an {@code ACCESS_DENIED} row stands for
 * {@code newValues.count} refusals (the recorder writes at most an opening
 * row and one flushed row per actor-or-ip per minute).
 * {@link #aggregatedCounts} reads that one number for those rows — and
 * nothing else of their values — so a windowed rule counts refusals, not
 * minutes with refusals.
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

    /** Ids per IN-list when reading the counts of aggregated rows. */
    private static final int ID_CHUNK = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

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

    /**
     * OBS-01: the refusal count each aggregated {@code ACCESS_DENIED} row of
     * {@code rows} stands for ({@code newValues.count}, at least 1), by audit
     * event id. Rows of other actions are absent (they weigh 1). Only the
     * {@code count} number is taken from the JSON; no other value leaves
     * this method.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> aggregatedCounts(List<AuditFacts> rows) {
        List<UUID> ids = rows.stream()
                .filter(row -> AuditActions.ACCESS_DENIED.equals(row.action()))
                .map(AuditFacts::auditEventId)
                .toList();
        Map<UUID, Integer> counts = new HashMap<>();
        for (int from = 0; from < ids.size(); from += ID_CHUNK) {
            List<UUID> chunk = ids.subList(from, Math.min(ids.size(), from + ID_CHUNK));
            List<Object[]> values = entityManager.createQuery(
                            "select e.auditEventId, e.newValues from AuditEvent e"
                                    + " where e.auditEventId in :ids", Object[].class)
                    .setParameter("ids", chunk)
                    .getResultList();
            for (Object[] value : values) {
                counts.put((UUID) value[0], countOf(value[1]));
            }
        }
        return counts;
    }

    /** {@code newValues.count} of an aggregated row; 1 when absent or unreadable. */
    static int countOf(Object newValues) {
        if (newValues == null) {
            return 1;
        }
        try {
            JsonNode count = JSON.readTree(newValues.toString()).path("count");
            return count.canConvertToInt() && count.asInt() > 0 ? count.asInt() : 1;
        } catch (Exception e) {
            return 1;
        }
    }
}
