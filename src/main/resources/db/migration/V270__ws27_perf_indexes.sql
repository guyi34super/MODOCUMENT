-- ws27 (v1.2.0 optimization round, SE1): performance indexes for the hot
-- read paths and the new housekeeping job (docs/optimization/contracts.md §1).
--
-- NON-TRANSACTIONAL: V270__ws27_perf_indexes.sql.conf sets
-- executeInTransaction=false, so every CREATE INDEX CONCURRENTLY below runs
-- on its own. CONCURRENTLY builds an index WITHOUT the SHARE lock a plain
-- CREATE INDEX holds for the whole build: inserts/updates/deletes keep
-- flowing on AuditEvent (append-only, never purged, the largest table), the
-- integration outbox and IngestionJob while a new task migrates. It costs
-- two table scans and waits for transactions already running on the table.
-- Because the build is not atomic, a failed or cancelled build leaves an
-- INVALID index of the same name behind, which IF NOT EXISTS would then
-- skip: in that case DROP INDEX CONCURRENTLY the invalid index and restart
-- the task (Flyway re-runs the failed V270). Nothing else in this file can
-- fail half-way: there is only CREATE INDEX here.
--
-- What each index serves:
--  * ix_auditevent_occurredat — the minute alert sweep reads EVERY bank's
--    rows of the window ("OccurredAt >= now - window", AuditWindowReader).
--    The wildcard rule admin-action-burst names no action, so no existing
--    index (all start with BankId or Action) bounded it: it scanned the
--    whole append-only table every minute.
--  * ix_auditevent_bankid_action_occurredat — bank-scoped audit reads by
--    action and date (the audit viewer, GET /api/admin/audit, and the
--    dashboard's per-bank action counts).
--  * ix_integrationrun_bankid_finishedat — the admin dashboard's
--    "runs ended in the last 24 h" counts and the housekeeping purge of run
--    history older than integration.historyRetention.
--  * ix_outbounddelivery_pending_next (partial, PENDING only) — the
--    integration tick's cross-bank "who has a delivery owed" scan and the
--    due scan; PENDING rows are a tiny fraction of a growing outbox.
--    INCLUDE (BankId) makes the tick's DISTINCT BankId scan index-only.
--  * ix_integrationrun_active_status (partial, unfinished runs only) — the
--    tick's "banks with an unfinished run" scan and the run-timeout sweep.
--  * ix_ingestionjob_template_owner_step_updated — learned-mapping lookup
--    (IngestionJobJpaRepository#findRecentMappingJson: template + owner +
--    step, newest first) on every mapping step.
--  * ix_pendingchange_bankid_requestedat — the pending-change list, newest
--    first per bank.
--  * Housekeeping (ws27 HousekeepingJob): ix_refreshtoken_expiresat,
--    ix_refreshtoken_revokedat (partial), ix_revokedtoken_expiresat,
--    ix_integrationevent_bankid_occurredat and ix_outbounddelivery_eventid
--    (partial — "is this event still referenced by an undelivered
--    delivery?"). RefreshToken carries NO BankId (V15, by design: the row is
--    reached through its user), so it gets no (BankId) index; the bank-wide
--    revocation goes through ix_refreshtoken_userid.
--
-- EXPAND/CONTRACT (docs/release/migration-policy.md): additive only; the
-- previous release ignores the new indexes.
--
-- SCHEMA PARITY: the H2 test schema comes from the JPA entities; AuditEvent
-- declares its two new indexes in @Table (as V161 did). The others are
-- PostgreSQL-side performance only (partial and INCLUDE indexes have no JPA
-- form).

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_auditevent_occurredat
    ON AuditEvent (OccurredAt);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_auditevent_bankid_action_occurredat
    ON AuditEvent (BankId, Action, OccurredAt);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_integrationrun_bankid_finishedat
    ON IntegrationRun (BankId, FinishedAt);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_outbounddelivery_pending_next
    ON OutboundDelivery (NextAttemptAt) INCLUDE (BankId)
    WHERE Status = 'PENDING';

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_integrationrun_active_status
    ON IntegrationRun (Status) INCLUDE (BankId)
    WHERE Status IN ('QUEUED', 'RUNNING', 'AWAITING_AGENT', 'RUNNING_AGENT');

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_ingestionjob_template_owner_step_updated
    ON IngestionJob (TemplateId, OwnerUserId, CurrentStep, UpdatedDate);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_pendingchange_bankid_requestedat
    ON PendingChange (BankId, RequestedAt);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_refreshtoken_expiresat
    ON RefreshToken (ExpiresAt);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_refreshtoken_revokedat
    ON RefreshToken (RevokedAt)
    WHERE RevokedAt IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_revokedtoken_expiresat
    ON RevokedToken (ExpiresAt);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_integrationevent_bankid_occurredat
    ON IntegrationEvent (BankId, OccurredAt);

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_outbounddelivery_eventid
    ON OutboundDelivery (EventId)
    WHERE EventId IS NOT NULL;
