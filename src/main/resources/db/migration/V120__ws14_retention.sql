-- ws14 (retention + data subject rights, gate items C5/C12, PRIV-013/016):
-- purge markers for the 30-day customer-data retention job.
--
--  * IngestionJob.PurgedAt — set by RetentionPurgeJob when the job's
--    customer-data checkpoint columns (ParsedTableJson, MappedRowsJson,
--    ValidationResultJson) are nulled after the retention period. The job
--    row itself is kept as an empty report skeleton (id, file name, steps,
--    dates), so history listings keep working while the customer rows are
--    gone. A non-null PurgedAt also stops the purge from rewriting the same
--    row on every run.
--  * DocInstance.ContentPurgedAt — set when the instance's customer content
--    (DocContentBlock rows, DocImageAsset rows + files, generated
--    docx/pdf exports) is purged 30 days after the last update. The
--    instance shell (title, status, dates) survives; blueprints/sections
--    are configuration, not customer data, and are never purged.
--
-- SCHEMA PARITY: the test profile builds its H2 schema from the JPA entities
-- (ddl-auto: create, Flyway disabled), so the matching purgedAt /
-- contentPurgedAt fields on IngestionJob and DocInstance must be kept in
-- sync with this file by hand.

ALTER TABLE IngestionJob ADD COLUMN PurgedAt timestamp NULL;
ALTER TABLE DocInstance ADD COLUMN ContentPurgedAt timestamp NULL;

-- The purge scans per bank by age; both scans are driven by these pairs.
CREATE INDEX IF NOT EXISTS ix_ingestionjob_bankid_updateddate
    ON IngestionJob (BankId, UpdatedDate);
CREATE INDEX IF NOT EXISTS ix_uploadedfile_bankid_uploadedat
    ON UploadedFile (BankId, UploadedAt);
