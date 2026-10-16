-- Validate before saving (docs/doccreation/field-validation-contract.md §4,
-- ws33): a document is a DRAFT until its values pass the template's checks
-- (POST /instances/{id}/validate), then VALIDATED; only a VALIDATED (or
-- GENERATED) document can be generated, downloaded and submitted. Any real
-- change sends it back to DRAFT.
--
--  * ValidatedAt / ValidatedByUserId — the last successful validation.
--  * ValidatedHash — sha256 of the validated content (values, blueprint
--    version, placed photos); generate/download require it to match.
--  * ValidatedRulesRevision — DocBlueprint.RulesRevision at validation.
--  * ValidationJson — the last result: rule ids, codes, messages and
--    severities, never values.
--
-- The status vocabulary widens with VALIDATED (drop + re-add of the same
-- named CHECK in this file, as V210 did for ck_appusers_role).
--
-- EXPAND/CONTRACT (docs/release/migration-policy.md): nullable columns and a
-- widened CHECK. Downgrade note (CHANGELOG): the previous release does not
-- know VALIDATED documents; generate them (or edit them back to DRAFT)
-- before rolling back.
--
-- ROW LEVEL SECURITY: DocInstance already carries BankId.
-- SCHEMA PARITY: the DocInstance entity mirrors this file on H2.

ALTER TABLE DocInstance ADD COLUMN IF NOT EXISTS ValidatedAt timestamp;
ALTER TABLE DocInstance ADD COLUMN IF NOT EXISTS ValidatedByUserId integer;
ALTER TABLE DocInstance ADD COLUMN IF NOT EXISTS ValidatedHash varchar(64);
ALTER TABLE DocInstance ADD COLUMN IF NOT EXISTS ValidatedRulesRevision integer;
ALTER TABLE DocInstance ADD COLUMN IF NOT EXISTS ValidationJson text;

ALTER TABLE DocInstance DROP CONSTRAINT ck_docinstance_status;
ALTER TABLE DocInstance
    ADD CONSTRAINT ck_docinstance_status
    CHECK (Status IN ('DRAFT', 'VALIDATED', 'GENERATED', 'SUBMITTED', 'RELEASED'));
