-- ws21 (phase 2, platform owner model — docs/phase2/contracts.md §1,
-- docs/platform/platform-owner.md): the platform owner (MO Intelligence)
-- lives in ONE dedicated bank, so RLS, tokens and maker/checker work for it
-- unchanged; this migration makes that bank a first-class, database-
-- enforced concept.
--
--  * Bank.Kind — CUSTOMER (every existing and future tenant, the default) or
--    PLATFORM. Exactly one PLATFORM bank may exist (ux_bank_single_platform,
--    a unique partial index), and a bank's kind never changes once created
--    (trg_bank_kind_immutable) — otherwise a customer bank full of ADMINs
--    could be flipped into the platform bank behind the role trigger's back.
--  * Bank.Status / SuspendedAt — ACTIVE or SUSPENDED (+ when). Suspension
--    itself (BANK_SUSPEND, token kill, login/refresh refusal) lands in
--    wave 2; the columns land now so the read API can report them.
--  * AppUsers.Role CHECK widened to admit PLATFORM_ADMIN (API_CLIENT is never
--    an AppUsers row, so it stays out).
--  * trg_appusers_platform_role_* — PLATFORM_ADMIN only inside the PLATFORM
--    bank, and ONLY PLATFORM_ADMIN inside it. The application enforces the
--    same rule (RoleAssignmentPolicy); this is the backstop for a buggy path
--    or a direct SQL write. The function is SECURITY DEFINER because it must
--    read Bank past row level security (a tenant only sees its own Bank
--    row): it runs as its owner, the migration user, which owns Bank and so
--    bypasses the ENABLE (not FORCE) policies. search_path is pinned to
--    pg_catalog, pg_temp and Bank is schema-qualified from TG_TABLE_SCHEMA,
--    so no object planted on the caller's search_path can hijack it.
--
-- EXPAND/CONTRACT (docs/release/migration-policy.md): additive only. The
-- new columns are NOT NULL with constant defaults (metadata-only on PG11+),
-- so the previous release — whose Bank entity knows none of them — keeps
-- inserting banks that land as CUSTOMER/ACTIVE. Dropping and re-adding
-- ck_appusers_role only WIDENS the vocabulary; the previous release never
-- writes PLATFORM_ADMIN, so a rollback of the application needs no schema
-- rollback. The triggers refuse nothing the previous release can write.
--
-- SCHEMA PARITY: the test profile builds its H2 schema from the JPA
-- entities (ddl-auto: create, Flyway disabled), so Bank (Kind, Status,
-- SuspendedAt + both CHECKs) and AppUser (ck_appusers_role) mirror this file
-- by hand. The partial unique index and both triggers are PostgreSQL-only;
-- PlatformRoleTriggerPgIT proves them.

-- ---------------------------------------------------------- Bank columns ----

ALTER TABLE Bank ADD COLUMN Kind varchar(20) NOT NULL DEFAULT 'CUSTOMER';
ALTER TABLE Bank
    ADD CONSTRAINT ck_bank_kind CHECK (Kind IN ('CUSTOMER', 'PLATFORM'));

ALTER TABLE Bank ADD COLUMN Status varchar(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE Bank
    ADD CONSTRAINT ck_bank_status CHECK (Status IN ('ACTIVE', 'SUSPENDED'));

ALTER TABLE Bank ADD COLUMN SuspendedAt timestamptz;

-- At most one row can have Kind = 'PLATFORM': every such row indexes the same
-- key, so a second one is a unique violation.
CREATE UNIQUE INDEX IF NOT EXISTS ux_bank_single_platform
    ON Bank (Kind) WHERE Kind = 'PLATFORM';

CREATE OR REPLACE FUNCTION bank_kind_immutable() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $kind_immutable$
BEGIN
    RAISE EXCEPTION 'Bank.Kind is immutable (BankId %: % -> %)',
        OLD.BankId, OLD.Kind, NEW.Kind
        USING ERRCODE = 'check_violation';
END;
$kind_immutable$;

CREATE TRIGGER trg_bank_kind_immutable
    BEFORE UPDATE OF Kind ON Bank
    FOR EACH ROW
    WHEN (OLD.Kind IS DISTINCT FROM NEW.Kind)
    EXECUTE FUNCTION bank_kind_immutable();

-- ------------------------------------------------- AppUsers role vocabulary ----

ALTER TABLE AppUsers DROP CONSTRAINT ck_appusers_role;
ALTER TABLE AppUsers
    ADD CONSTRAINT ck_appusers_role
    CHECK (Role IN ('ADMIN', 'APPROVER', 'READ_WRITE', 'READ_ONLY', 'PLATFORM_ADMIN'));

-- ------------------------------------------------ platform role placement ----

CREATE OR REPLACE FUNCTION appusers_enforce_platform_role() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $platform_role$
DECLARE
    bank_kind text;
BEGIN
    -- Past RLS (SECURITY DEFINER, see the header). A missing bank reads as
    -- NULL: the foreign key refuses the row anyway, and NULL is never
    -- 'PLATFORM', so the check below fails closed for PLATFORM_ADMIN.
    EXECUTE format('SELECT Kind FROM %I.bank WHERE BankId = $1', TG_TABLE_SCHEMA)
        INTO bank_kind
        USING NEW.BankId;

    IF NEW.Role = 'PLATFORM_ADMIN' AND bank_kind IS DISTINCT FROM 'PLATFORM' THEN
        RAISE EXCEPTION 'PLATFORM_ADMIN is only allowed in the platform bank (BankId %)',
            NEW.BankId
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.Role IS DISTINCT FROM 'PLATFORM_ADMIN' AND bank_kind = 'PLATFORM' THEN
        RAISE EXCEPTION 'Only PLATFORM_ADMIN may belong to the platform bank (BankId %, role %)',
            NEW.BankId, NEW.Role
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$platform_role$;

-- Trigger functions are never called directly; nobody needs EXECUTE.
REVOKE ALL ON FUNCTION appusers_enforce_platform_role() FROM PUBLIC;
REVOKE ALL ON FUNCTION bank_kind_immutable() FROM PUBLIC;

CREATE TRIGGER trg_appusers_platform_role_insert
    BEFORE INSERT ON AppUsers
    FOR EACH ROW
    EXECUTE FUNCTION appusers_enforce_platform_role();

-- UPDATE only when Role or BankId really changes: Hibernate writes every
-- column on each save (LastLoginAt on every login), and an unchanged
-- placement was already checked when it was written.
CREATE TRIGGER trg_appusers_platform_role_update
    BEFORE UPDATE OF Role, BankId ON AppUsers
    FOR EACH ROW
    WHEN (OLD.Role IS DISTINCT FROM NEW.Role OR OLD.BankId IS DISTINCT FROM NEW.BankId)
    EXECUTE FUNCTION appusers_enforce_platform_role();
