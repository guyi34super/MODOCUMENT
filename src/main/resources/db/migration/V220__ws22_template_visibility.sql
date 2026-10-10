-- ws22 (phase 2, admin dashboard & bank-shared templates —
-- docs/phase2/contracts.md §3/§4, docs/phase2/template-visibility.md).
--
-- Template.Visibility — PRIVATE (the default: only the owner sees and uses
-- the template, exactly the pre-phase-2 behaviour) or BANK (every user of
-- the owner's bank can read the template and use it for uploads,
-- extraction and validation; only holders of template:publish — bank
-- ADMINs — can change it). Visibility never crosses banks: the read
-- predicate always pins Template.BankId to the caller's bank, and the RLS
-- policy on Template (afterMigrate__rls_enforce.sql, unchanged) still
-- hides every other bank's rows on PostgreSQL.
--
-- Dashboard indexes (GET /api/admin/dashboard counts per bank):
--  * ix_template_bankid_visibility — templates {private, shared} per bank,
--    and the "BANK in my bank" half of the template listing;
--  * ix_ingestionjob_bankid_createddate — ingestion today / last 7 days;
--  * ix_appusers_bankid_role — users by role (AppUsers is not under RLS;
--    every count names the bank).
--
-- EXPAND/CONTRACT (docs/release/migration-policy.md): additive only. The
-- column is NOT NULL with a constant default (metadata-only on PG11+), so
-- the previous release — whose Template entity has no Visibility — keeps
-- inserting templates that land PRIVATE, i.e. exactly what it means. Its
-- owner-only reads simply never see shared templates, so a rollback of the
-- application needs no schema rollback. The CHECK is satisfied by every
-- existing row (they all take the default).
--
-- SCHEMA PARITY: the test profile builds its H2 schema from the JPA
-- entities (ddl-auto: create, Flyway disabled), so Template mirrors the
-- column, its default and ck_template_visibility by hand. The indexes are
-- PostgreSQL-side performance only.

ALTER TABLE Template ADD COLUMN Visibility varchar(10) NOT NULL DEFAULT 'PRIVATE';
ALTER TABLE Template
    ADD CONSTRAINT ck_template_visibility CHECK (Visibility IN ('PRIVATE', 'BANK'));

CREATE INDEX IF NOT EXISTS ix_template_bankid_visibility
    ON Template (BankId, Visibility);

CREATE INDEX IF NOT EXISTS ix_ingestionjob_bankid_createddate
    ON IngestionJob (BankId, CreatedDate);

CREATE INDEX IF NOT EXISTS ix_appusers_bankid_role
    ON AppUsers (BankId, Role);
