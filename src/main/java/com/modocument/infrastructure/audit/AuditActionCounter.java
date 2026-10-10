package com.modocument.infrastructure.audit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Counts of one audit action for one bank since an instant (the admin
 * dashboard's {@code integrations.apiCalls24h}). Like RetentionRunHistory it
 * reads the append-only AuditEvent table directly with a narrow,
 * action-pinned SELECT: no general audit repository exists, rows are only
 * ever written through {@code AuditRecorder}. The bank is named explicitly
 * on top of RLS, and the {@code (Action, OccurredAt)} index serves it.
 */
@Service
public class AuditActionCounter {

    @PersistenceContext
    private EntityManager entityManager;

    public long countSince(int bankId, String action, Instant since) {
        return entityManager.createQuery(
                        "select count(e) from AuditEvent e"
                                + " where e.bankId = :bankId and e.action = :action"
                                + " and e.occurredAt >= :since",
                        Long.class)
                .setParameter("bankId", bankId)
                .setParameter("action", action)
                .setParameter("since", since)
                .getSingleResult();
    }
}
