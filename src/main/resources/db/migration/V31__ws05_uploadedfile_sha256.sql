-- ws05 (duplicate upload blocking, gate C6 / SEC-030): every stored upload
-- records the SHA-256 of its content. Re-uploading the SAME bytes under the
-- SAME original name by the SAME owner is a duplicate and answers 409 before
-- anything is written; the same bytes under a DIFFERENT name stay allowed on
-- purpose (a copy intentionally saved under a new name is a new upload).
--
-- Nullable: rows written before this migration have no hash (pre-launch there
-- is nothing worth backfilling, and V20 already treats pre-existing stored
-- names as not-found). Every new upload sets it, and the duplicate check only
-- compares against rows that have one.
--
-- SCHEMA PARITY: the test profile builds its H2 schema from the JPA entities
-- (ddl-auto: create, Flyway disabled), so the matching sha256Hex field on
-- UploadedFile must be kept in sync with this file by hand.

ALTER TABLE UploadedFile ADD COLUMN Sha256Hex varchar(64);

-- The duplicate probe is (owner, hash, original name); index the two
-- high-cardinality columns.
CREATE INDEX IF NOT EXISTS ix_uploadedfile_owner_sha256
    ON UploadedFile (OwnerUserId, Sha256Hex);
