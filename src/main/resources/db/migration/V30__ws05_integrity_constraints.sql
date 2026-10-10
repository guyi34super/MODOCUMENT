-- ws05 (server-side validation + database constraints, gate C6): the database
-- now states the integrity rules the application code has so far enforced
-- alone, so a bug, a race between two requests, or a direct SQL path can no
-- longer slip bad rows in.
--
--  * UNIQUE TemplateFields(TemplateId, FieldName) — the app blocks duplicate
--    field names (CreateTemplateWithFieldsUseCase); this is the DB truth for
--    the race two concurrent creates can win together. The API answers 409
--    (DataIntegrityViolation -> conflict in ApiExceptionHandler).
--  * UNIQUE Template(OwnerUserId, Name) — one template name per owner. Every
--    user belongs to exactly one bank (AppUsers.BankId), so this already
--    implies uniqueness per (BankId, OwnerUserId, Name).
--  * CHECK constraints on enum-like varchar columns, verified against the
--    enums persisted BY NAME (Role, AuditOutcome, JobStatus, JobStep,
--    WizardStep, ExtractionJobStatus, SourceType) — no historical spellings
--    exist: these columns were born with EnumType.STRING mappings.
--  * CHECK non-negative sizes/counters (UploadedFile.SizeBytes,
--    ExtractionJobCounter.CounterValue).
--  * NOT NULL ExtractionJob.SourceId — always populated (the create use case
--    requires a source and stamps it). The other extraction varchar
--    JobId/SourceId columns keep their V8 design: no FKs on purpose (jobs
--    outlive deleted sources), and ExtractionAuditEvent.SourceId /
--    ExtractionProcessedRecord.RawRecordId stay nullable because the code
--    legitimately writes them as null.
--
-- CLEAN DATA GUARDS: pre-launch there is no data worth preserving (same
-- stance as V6), but the two UNIQUE constraints are still guarded so the
-- migration cannot fail on a dirty environment: duplicate TemplateFields
-- rows are dropped (keeping the oldest, the one app reads resolve first) and
-- duplicate template names are deterministically suffixed with their id.
--
-- SCHEMA PARITY: the test profile builds its H2 schema from the JPA entities
-- (ddl-auto: create, Flyway disabled), so the same rules are mirrored there:
-- @Table(uniqueConstraints) on Template and TemplateFields, nullable=false on
-- ExtractionJobEntity.sourceId, and @Check on UploadedFile (SizeBytes),
-- AppUser (Role) and AuditEvent (Outcome). Keep them in sync by hand.

-- ------------------------------------------------------------- uniques ----

DELETE FROM TemplateFields a
    USING TemplateFields b
    WHERE a.TemplateFieldsId > b.TemplateFieldsId
      AND a.TemplateId = b.TemplateId
      AND a.FieldName = b.FieldName;
ALTER TABLE TemplateFields
    ADD CONSTRAINT uq_templatefields_templateid_fieldname UNIQUE (TemplateId, FieldName);

UPDATE Template t
    SET Name = t.Name || ' (' || t.TemplateId || ')'
    WHERE EXISTS (SELECT 1 FROM Template o
                      WHERE o.OwnerUserId = t.OwnerUserId
                        AND o.Name = t.Name
                        AND o.TemplateId < t.TemplateId);
ALTER TABLE Template
    ADD CONSTRAINT uq_template_owneruserid_name UNIQUE (OwnerUserId, Name);

-- -------------------------------------------------------------- checks ----
-- NULL passes a CHECK (… IN …) by SQL semantics, so nullable step/status
-- columns stay writable as NULL; only out-of-vocabulary values are refused.

ALTER TABLE AppUsers
    ADD CONSTRAINT ck_appusers_role
    CHECK (Role IN ('ADMIN', 'APPROVER', 'READ_WRITE', 'READ_ONLY'));

ALTER TABLE AuditEvent
    ADD CONSTRAINT ck_auditevent_outcome
    CHECK (Outcome IN ('SUCCESS', 'DENIED', 'FAILED'));

ALTER TABLE IngestionJob
    ADD CONSTRAINT ck_ingestionjob_status
    CHECK (Status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'));
ALTER TABLE IngestionJob
    ADD CONSTRAINT ck_ingestionjob_currentstep
    CHECK (CurrentStep IN ('UPLOADED', 'PARSED', 'MAPPED', 'VALIDATED', 'EXPORTED'));
ALTER TABLE IngestionJob
    ADD CONSTRAINT ck_ingestionjob_wizardstep
    CHECK (WizardStep IN ('MAPPING', 'VALIDATION', 'EXPORT'));

ALTER TABLE ExtractionJob
    ADD CONSTRAINT ck_extractionjob_status
    CHECK (Status IN ('PENDING', 'RUNNING', 'PAUSED', 'COMPLETED',
                      'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED'));

ALTER TABLE ExtractionSource
    ADD CONSTRAINT ck_extractionsource_type
    CHECK (Type IN ('WEBSITE', 'REST_API', 'DATABASE'));

ALTER TABLE UploadedFile
    ADD CONSTRAINT ck_uploadedfile_sizebytes CHECK (SizeBytes >= 0);

ALTER TABLE ExtractionJobCounter
    ADD CONSTRAINT ck_extractionjobcounter_value CHECK (CounterValue >= 0);

-- ------------------------------------------------------------ not null ----

ALTER TABLE ExtractionJob ALTER COLUMN SourceId SET NOT NULL;
