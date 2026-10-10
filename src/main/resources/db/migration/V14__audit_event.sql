-- C7 append-only audit trail: one row per security-relevant action — WHO
-- (ActorUserId/ActorEmail/ActorRole + SessionJti), WHAT (Action, EntityType/
-- EntityId, OldValues/NewValues JSON snapshots), WHEN (OccurredAt), WHERE
-- FROM (RequestId, Ip — the E9 end-to-end correlation keys, shared with every
-- log line of the same request) and the Outcome (SUCCESS/DENIED/FAILED).
-- Rows are written only through the AuditRecorder port.
--
-- APPEND-ONLY TWICE OVER, in this same migration:
--   1. UPDATE/DELETE/TRUNCATE are REVOKED from the runtime role
--      modocument_app (V12 granted them via default privileges when this
--      table was created) — the application cannot tamper even if compromised
--      at the SQL level;
--   2. BEFORE UPDATE/DELETE (row) and BEFORE TRUNCATE (statement) triggers
--      RAISE EXCEPTION — protecting the trail even from the owner/migration
--      role's accidents. AuditAppendOnlyPgIT proves both layers.
-- Retention/archival (a legitimate, controlled purge) is another
-- workstream's concern and would go through the owner role dropping the
-- trigger inside its own migration — never through the app.
--
-- ROW LEVEL SECURITY: the afterMigrate__rls_enforce.sql callback applies the
-- standard tenant policy to this table automatically (it carries a BankId
-- column). That is compatible by construction: every INSERT happens either
-- inside the request's tenant context (app.tenant = the row's BankId, WITH
-- CHECK passes) or under TenantContext.runAsSystem / the recorder's explicit
-- bank binding (app.tenant = '*' or the event's bank); audit reads for a
-- future dashboard are bank-scoped by the same policy — exactly what we
-- want. No extra policy work needed here.
--
-- NO foreign keys on purpose: the trail must outlive (and never block) the
-- rows it describes — an actor or entity may be deleted later, and an
-- unknown-email login attempt has no AppUser row at all.
--
-- SCHEMA PARITY: the test profile builds its H2 schema from the AuditEvent
-- JPA entity (ddl-auto: create, Flyway disabled), so the entity must be kept
-- in sync with this file by hand (OldValues/NewValues are jsonb here, a JSON
-- column on H2 via @JdbcTypeCode(SqlTypes.JSON) — the recorder only ever
-- writes valid JSON).

CREATE TABLE AuditEvent (
    AuditEventId uuid PRIMARY KEY,              -- UUIDv7, generated app-side
    OccurredAt   timestamptz NOT NULL,
    BankId       integer NOT NULL,              -- tenant; RLS substrate
    ActorUserId  integer,                       -- null for system/anonymous
    ActorEmail   varchar(320),
    ActorRole    varchar(20),
    SessionJti   uuid,                          -- access-token jti, when authenticated
    RequestId    varchar(64),                   -- X-Request-Id (E9 correlation)
    Ip           varchar(64),
    Action       varchar(60) NOT NULL,          -- e.g. LOGIN_FAILED, TEMPLATE_UPDATED
    EntityType   varchar(40),
    EntityId     varchar(64),
    OldValues    jsonb,                         -- snapshot before the change
    NewValues    jsonb,                         -- snapshot after the change
    Outcome      varchar(10) NOT NULL           -- SUCCESS / DENIED / FAILED
);

CREATE INDEX IF NOT EXISTS ix_auditevent_bankid_occurredat
    ON AuditEvent (BankId, OccurredAt);
CREATE INDEX IF NOT EXISTS ix_auditevent_entitytype_entityid
    ON AuditEvent (EntityType, EntityId);

-- Layer 1: the runtime role may only ever INSERT and SELECT.
REVOKE UPDATE, DELETE, TRUNCATE ON AuditEvent FROM modocument_app;

-- Layer 2: nobody mutates history, not even the owner by accident.
CREATE OR REPLACE FUNCTION auditevent_block_mutation() RETURNS trigger
LANGUAGE plpgsql AS $append_only$
BEGIN
    RAISE EXCEPTION 'AuditEvent is append-only: % is not allowed (C7 audit trail)', TG_OP;
END;
$append_only$;

CREATE TRIGGER trg_auditevent_append_only
    BEFORE UPDATE OR DELETE ON AuditEvent
    FOR EACH ROW EXECUTE FUNCTION auditevent_block_mutation();

CREATE TRIGGER trg_auditevent_no_truncate
    BEFORE TRUNCATE ON AuditEvent
    FOR EACH STATEMENT EXECUTE FUNCTION auditevent_block_mutation();
