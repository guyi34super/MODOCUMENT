package com.modocument.infrastructure.audit;

import com.modocument.application.port.AuditLogReader;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.AuditEvent;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@link AuditLogReader} over the append-only AuditEvent table (ws28). Like
 * the SIEM feed it names the bank explicitly on top of RLS. The keyset
 * {@code (OccurredAt, AuditEventId) < (:at, :id)} with
 * {@code ORDER BY OccurredAt DESC, AuditEventId DESC} is served by the
 * {@code (BankId, OccurredAt)} index (SE1's V270 adds the action-led one);
 * UUIDv7 ids order the same way on PostgreSQL and H2, so ties on the same
 * instant page deterministically.
 */
@Service
public class JpaAuditLogReader implements AuditLogReader {

    @PersistenceContext
    private EntityManager entityManager;

    private final AppUserJpaRepository users;

    public JpaAuditLogReader(AppUserJpaRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEvent> page(Query query, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder jpql = new StringBuilder("select e from AuditEvent e");
        where(jpql, params, query, true);
        jpql.append(" order by e.occurredAt desc, e.auditEventId desc");
        TypedQuery<AuditEvent> typed = entityManager.createQuery(jpql.toString(), AuditEvent.class)
                .setMaxResults(limit);
        params.forEach(typed::setParameter);
        return typed.getResultList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(Query query) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder jpql = new StringBuilder("select count(e) from AuditEvent e");
        where(jpql, params, query, false);
        TypedQuery<Long> typed = entityManager.createQuery(jpql.toString(), Long.class);
        params.forEach(typed::setParameter);
        return typed.getSingleResult();
    }

    private static void where(StringBuilder jpql, Map<String, Object> params, Query query,
                              boolean withCursor) {
        jpql.append(" where e.bankId = :bankId");
        params.put("bankId", query.bankId());
        if (query.from() != null) {
            jpql.append(" and e.occurredAt >= :from");
            params.put("from", query.from());
        }
        if (query.to() != null) {
            jpql.append(" and e.occurredAt < :to");
            params.put("to", query.to());
        }
        if (query.actions() != null && !query.actions().isEmpty()) {
            jpql.append(" and e.action in :actions");
            params.put("actions", query.actions());
        }
        if (query.outcome() != null) {
            jpql.append(" and e.outcome = :outcome");
            params.put("outcome", query.outcome());
        }
        if (query.entityType() != null) {
            jpql.append(" and e.entityType = :entityType");
            params.put("entityType", query.entityType());
        }
        if (query.entityId() != null) {
            jpql.append(" and e.entityId = :entityId");
            params.put("entityId", query.entityId());
        }
        if (query.actorUserId() != null && query.actorEmail() != null) {
            jpql.append(" and (e.actorUserId = :actorUserId or lower(e.actorEmail) = :actorEmail)");
            params.put("actorUserId", query.actorUserId());
            params.put("actorEmail", query.actorEmail().toLowerCase(Locale.ROOT));
        } else if (query.actorUserId() != null) {
            jpql.append(" and e.actorUserId = :actorUserId");
            params.put("actorUserId", query.actorUserId());
        } else if (query.actorEmail() != null) {
            jpql.append(" and lower(e.actorEmail) = :actorEmail");
            params.put("actorEmail", query.actorEmail().toLowerCase(Locale.ROOT));
        }
        if (withCursor && query.cursorAt() != null && query.cursorId() != null) {
            jpql.append(" and (e.occurredAt < :cursorAt"
                    + " or (e.occurredAt = :cursorAt and e.auditEventId < :cursorId))");
            params.put("cursorAt", query.cursorAt());
            params.put("cursorId", query.cursorId());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<BankCount> countsByBank(Instant from, Instant to) {
        List<Object[]> rows = entityManager.createQuery(
                        "select e.bankId, count(e),"
                                + " sum(case when e.outcome = :denied then 1 else 0 end),"
                                + " sum(case when e.outcome = :failed then 1 else 0 end)"
                                + " from AuditEvent e"
                                + " where e.occurredAt >= :from and e.occurredAt < :to"
                                + " group by e.bankId", Object[].class)
                .setParameter("denied", AuditOutcome.DENIED)
                .setParameter("failed", AuditOutcome.FAILED)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        List<BankCount> counts = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            counts.add(new BankCount(((Number) row[0]).intValue(), number(row[1]), number(row[2]),
                    number(row[3])));
        }
        return counts;
    }

    @Override
    public Map<Integer, String> userEmails(int bankId, Collection<Integer> userIds) {
        Map<Integer, String> emails = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return emails;
        }
        for (AppUser user : users.findAllById(userIds)) {
            if (user.getBankId() == bankId) {
                emails.put(user.getAppUserId(), user.getEmail());
            }
        }
        return emails;
    }

    @Override
    public Optional<Integer> userIdByEmail(int bankId, String email) {
        return users.findByEmailIgnoreCase(email)
                .filter(user -> user.getBankId() == bankId)
                .map(AppUser::getAppUserId);
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
