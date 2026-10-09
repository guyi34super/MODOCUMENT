package com.modocument.infrastructure.siem;

import com.modocument.domain.entity.AuditEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read side of the bank-scoped SIEM pull feed (ws18, SEC-031, gate E6):
 * one keyset page of ONE bank's audit events, oldest first.
 *
 * <p>TWO tenant boundaries, on purpose: the explicit {@code e.bankId = :bankId}
 * predicate (the caller's bank, from its token — never a request parameter)
 * AND, on PostgreSQL, the RLS policy on AuditEvent filtering by the request's
 * TenantContext. Either alone keeps bank B's rows out of bank A's feed; the
 * explicit filter is also the only boundary on the H2 test schema.
 *
 * <p>Cursor = AuditEventId. Ids are UUIDv7 (48-bit millisecond timestamp in
 * the most significant bits), and both PostgreSQL ({@code uuid} compares
 * bytewise) and H2 (unsigned high/low longs) order them by time, so
 * {@code id > cursor ORDER BY id} is a stable, gap-free keyset walk — for
 * rows older than the settle lag (see {@code SiemProperties.Feed#settle}).
 */
@Service
public class SiemFeedReader {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Up to {@code limit} events of {@code bankId} after {@code since}
     * (exclusive; null = from the beginning), excluding events newer than
     * {@code settledBefore}.
     */
    @Transactional(readOnly = true)
    public List<AuditEvent> page(int bankId, UUID since, Instant settledBefore, int limit) {
        String jpql = "select e from AuditEvent e where e.bankId = :bankId"
                + " and e.occurredAt <= :settledBefore"
                + (since == null ? "" : " and e.auditEventId > :since")
                + " order by e.auditEventId asc";
        TypedQuery<AuditEvent> query = entityManager.createQuery(jpql, AuditEvent.class)
                .setParameter("bankId", bankId)
                .setParameter("settledBefore", settledBefore)
                .setMaxResults(limit);
        if (since != null) {
            query.setParameter("since", since);
        }
        return query.getResultList();
    }
}
