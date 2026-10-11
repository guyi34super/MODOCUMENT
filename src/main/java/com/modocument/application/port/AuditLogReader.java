package com.modocument.application.port;

import com.modocument.domain.entity.AuditEvent;
import com.modocument.domain.model.AuditOutcome;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read side of the audit viewer (ws28): keyset pages of ONE bank's audit
 * events, newest first, and the platform's counts-only cross-bank figures.
 * Rows are only ever WRITTEN through {@link AuditRecorder}; this port never
 * writes.
 *
 * <p>Tenancy: every query names the bank explicitly (the caller's bank, from
 * its token — never a request parameter), and on PostgreSQL the AuditEvent
 * RLS policy filters by the request's tenant as well. The cross-bank
 * {@link #countsByBank} must be called under {@code TenantContext.runAsSystem}
 * by its one registered caller.
 */
public interface AuditLogReader {

    /**
     * The filters of one query. Null fields do not filter. {@code actorUserId}
     * and {@code actorEmail} are OR-ed (an actor is named by either); the
     * cursor is the last row of the previous page ({@code (occurredAt, id)},
     * exclusive).
     */
    record Query(int bankId, Integer actorUserId, String actorEmail, Collection<String> actions,
                 String entityType, String entityId, AuditOutcome outcome, Instant from,
                 Instant to, Instant cursorAt, UUID cursorId) {

        public Query after(Instant at, UUID id) {
            return new Query(bankId, actorUserId, actorEmail, actions, entityType, entityId,
                    outcome, from, to, at, id);
        }
    }

    /** Per-bank totals of one time range (counts only). */
    record BankCount(int bankId, long total, long denied, long failed) {
    }

    /** Up to {@code limit} events, newest first ({@code OccurredAt DESC, AuditEventId DESC}). */
    List<AuditEvent> page(Query query, int limit);

    /** How many events the query matches (ignores the cursor). */
    long count(Query query);

    /** Every bank's totals in {@code [from, to)}: SYSTEM context only. */
    List<BankCount> countsByBank(Instant from, Instant to);

    /** Email addresses of the given users of ONE bank (AppUsers is not under RLS). */
    Map<Integer, String> userEmails(int bankId, Collection<Integer> userIds);

    /** The id of the bank's user with this email, if any. */
    Optional<Integer> userIdByEmail(int bankId, String email);
}
