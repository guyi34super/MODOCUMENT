-- ws18 (SEC-031 SIEM feed, SEC-032 windowed alert rules): read paths over
-- the append-only AuditEvent trail, which is the single event source for
-- both alerting and the SIEM export.
--
--  * ix_auditevent_bankid_auditeventid — the bank-scoped pull feed
--    (GET /api/siem/events?since=<cursor>) pages with
--    "WHERE BankId = ? AND AuditEventId > ? ORDER BY AuditEventId": the
--    cursor is the UUIDv7 id, which is time-ordered, so this one index
--    serves both the tenant filter and the keyset pagination.
--  * ix_auditevent_action_occurredat — the minute sweep of the windowed
--    alert rules counts "WHERE Action IN (...) AND OccurredAt >= now - window".
--
-- Index creation is not an UPDATE/DELETE, so the V14 append-only triggers
-- and REVOKEs are unaffected.
--
-- SCHEMA PARITY: the AuditEvent entity declares the same two indexes in
-- its @Table so the H2 test schema (ddl-auto: create) matches.

CREATE INDEX IF NOT EXISTS ix_auditevent_bankid_auditeventid
    ON AuditEvent (BankId, AuditEventId);
CREATE INDEX IF NOT EXISTS ix_auditevent_action_occurredat
    ON AuditEvent (Action, OccurredAt);
