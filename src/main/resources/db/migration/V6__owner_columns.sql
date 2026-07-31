-- Per-user ownership. Until now no domain row recorded who created it and no
-- query was scoped to a user, so any authenticated user could read, modify, or
-- export any other user's data. Each root entity (Template, ImportHistory,
-- IngestionJob) now carries the owning AppUser; children (TemplateFields,
-- ValidationRules, ImportDetails) are scoped through their parent.
--
-- Pre-launch: there is no data worth preserving, so existing owned rows are
-- removed rather than backfilled, which lets the owner column be NOT NULL with
-- a real foreign key from the start. Deletion is child-first to satisfy the
-- V1 foreign keys.
--
-- SCHEMA PARITY: the test profile builds its H2 schema from the JPA entities
-- (ddl-auto: create, Flyway disabled), so the matching `ownerUserId` fields on
-- Template/ImportHistory/IngestionJob must be kept in sync with this file by
-- hand — there is no test tying the two together.

DELETE FROM ImportDetails;
DELETE FROM ValidationRules;
DELETE FROM TemplateFields;
DELETE FROM IngestionJob;
DELETE FROM ImportHistory;
DELETE FROM Template;

ALTER TABLE Template
    ADD COLUMN OwnerUserId integer NOT NULL REFERENCES AppUsers (AppUserId);
ALTER TABLE ImportHistory
    ADD COLUMN OwnerUserId integer NOT NULL REFERENCES AppUsers (AppUserId);
ALTER TABLE IngestionJob
    ADD COLUMN OwnerUserId integer NOT NULL REFERENCES AppUsers (AppUserId);

CREATE INDEX IF NOT EXISTS ix_template_owneruserid ON Template (OwnerUserId);
CREATE INDEX IF NOT EXISTS ix_importhistory_owneruserid ON ImportHistory (OwnerUserId);
CREATE INDEX IF NOT EXISTS ix_ingestionjob_owneruserid ON IngestionJob (OwnerUserId);
