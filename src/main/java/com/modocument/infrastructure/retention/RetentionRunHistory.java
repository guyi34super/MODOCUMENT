package com.modocument.infrastructure.retention;

import com.modocument.domain.entity.AuditEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Read-side of the retention evidence: the newest
 * RETENTION_PURGE_COMPLETED audit event of one bank, powering
 * GET /api/admin/retention/status (the admin panel's "last run" view).
 * Reads the append-only AuditEvent table directly — there is deliberately
 * no general audit repository (rows are only ever written through
 * {@code AuditRecorder}), and this is a narrow, action-pinned SELECT that
 * RLS additionally scopes on PostgreSQL.
 */
@Service
public class RetentionRunHistory {

    @PersistenceContext
    private EntityManager entityManager;

    /** The last completed purge recorded for this bank, if any. */
    public Optional<AuditEvent> latestFor(int bankId) {
        List<AuditEvent> rows = entityManager.createQuery(
                        "select e from AuditEvent e"
                                + " where e.bankId = :bankId and e.action = :action"
                                + " order by e.occurredAt desc",
                        AuditEvent.class)
                .setParameter("bankId", bankId)
                .setParameter("action", "RETENTION_PURGE_COMPLETED")
                .setMaxResults(1)
                .getResultList();
        return rows.stream().findFirst();
    }
}
