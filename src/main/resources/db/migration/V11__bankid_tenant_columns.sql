-- BankId on every tenant-owned table. Roots (Template, ImportHistory,
-- IngestionJob, ExtractionSource, ExtractionJob, DocBlueprint, DocInstance)
-- are backfilled into the default bank (all pre-existing data belongs to it);
-- children inherit their parent's bank through a join. App-level filtering
-- stays OwnerUserId-based for now — these columns are the substrate for the
-- row-level-security policies that land in a later migration range.
--
-- ImportHistory.CompanyId and DocBlueprint.CompanyId are deprecated by BankId
-- but deliberately NOT dropped here (later cleanup once nothing reads them).
--
-- SCHEMA PARITY: the test profile builds its H2 schema from the JPA entities
-- (ddl-auto: create, Flyway disabled), so the matching bankId fields on these
-- entities must be kept in sync with this file by hand.

-- ---------------------------------------------------------------- roots ----

ALTER TABLE Template ADD COLUMN BankId integer;
UPDATE Template SET BankId = (SELECT BankId FROM Bank WHERE Code = 'DEFAULT');
ALTER TABLE Template ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE Template
    ADD CONSTRAINT fk_template_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_template_bankid_owneruserid ON Template (BankId, OwnerUserId);

ALTER TABLE ImportHistory ADD COLUMN BankId integer;
UPDATE ImportHistory SET BankId = (SELECT BankId FROM Bank WHERE Code = 'DEFAULT');
ALTER TABLE ImportHistory ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE ImportHistory
    ADD CONSTRAINT fk_importhistory_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_importhistory_bankid_owneruserid ON ImportHistory (BankId, OwnerUserId);

ALTER TABLE IngestionJob ADD COLUMN BankId integer;
UPDATE IngestionJob SET BankId = (SELECT BankId FROM Bank WHERE Code = 'DEFAULT');
ALTER TABLE IngestionJob ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE IngestionJob
    ADD CONSTRAINT fk_ingestionjob_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_ingestionjob_bankid_owneruserid ON IngestionJob (BankId, OwnerUserId);

ALTER TABLE ExtractionSource ADD COLUMN BankId integer;
UPDATE ExtractionSource SET BankId = (SELECT BankId FROM Bank WHERE Code = 'DEFAULT');
ALTER TABLE ExtractionSource ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE ExtractionSource
    ADD CONSTRAINT fk_extractionsource_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_extractionsource_bankid_owneruserid ON ExtractionSource (BankId, OwnerUserId);

ALTER TABLE ExtractionJob ADD COLUMN BankId integer;
UPDATE ExtractionJob SET BankId = (SELECT BankId FROM Bank WHERE Code = 'DEFAULT');
ALTER TABLE ExtractionJob ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE ExtractionJob
    ADD CONSTRAINT fk_extractionjob_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_extractionjob_bankid_owneruserid ON ExtractionJob (BankId, OwnerUserId);

ALTER TABLE DocBlueprint ADD COLUMN BankId integer;
UPDATE DocBlueprint SET BankId = (SELECT BankId FROM Bank WHERE Code = 'DEFAULT');
ALTER TABLE DocBlueprint ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE DocBlueprint
    ADD CONSTRAINT fk_docblueprint_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_docblueprint_bankid_owneruserid ON DocBlueprint (BankId, OwnerUserId);

ALTER TABLE DocInstance ADD COLUMN BankId integer;
UPDATE DocInstance SET BankId = (SELECT BankId FROM Bank WHERE Code = 'DEFAULT');
ALTER TABLE DocInstance ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE DocInstance
    ADD CONSTRAINT fk_docinstance_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_docinstance_bankid_owneruserid ON DocInstance (BankId, OwnerUserId);

-- ---------------------------------------------- children via parent join ----

ALTER TABLE TemplateFields ADD COLUMN BankId integer;
UPDATE TemplateFields tf
    SET BankId = (SELECT t.BankId FROM Template t WHERE t.TemplateId = tf.TemplateId);
ALTER TABLE TemplateFields ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE TemplateFields
    ADD CONSTRAINT fk_templatefields_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_templatefields_bankid ON TemplateFields (BankId);

ALTER TABLE ValidationRules ADD COLUMN BankId integer;
UPDATE ValidationRules vr
    SET BankId = (SELECT t.BankId FROM Template t WHERE t.TemplateId = vr.TemplateId);
ALTER TABLE ValidationRules ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE ValidationRules
    ADD CONSTRAINT fk_validationrules_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_validationrules_bankid ON ValidationRules (BankId);

ALTER TABLE ImportDetails ADD COLUMN BankId integer;
UPDATE ImportDetails d
    SET BankId = (SELECT h.BankId FROM ImportHistory h WHERE h.ImportHistoryId = d.ImportHistoryId);
ALTER TABLE ImportDetails ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE ImportDetails
    ADD CONSTRAINT fk_importdetails_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_importdetails_bankid ON ImportDetails (BankId);

ALTER TABLE DocBlueprintSection ADD COLUMN BankId integer;
UPDATE DocBlueprintSection s
    SET BankId = (SELECT b.BankId FROM DocBlueprint b WHERE b.DocBlueprintId = s.BlueprintId);
ALTER TABLE DocBlueprintSection ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE DocBlueprintSection
    ADD CONSTRAINT fk_docblueprintsection_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_docblueprintsection_bankid ON DocBlueprintSection (BankId);

ALTER TABLE DocImageAsset ADD COLUMN BankId integer;
UPDATE DocImageAsset a
    SET BankId = (SELECT i.BankId FROM DocInstance i WHERE i.DocInstanceId = a.InstanceId);
ALTER TABLE DocImageAsset ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE DocImageAsset
    ADD CONSTRAINT fk_docimageasset_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_docimageasset_bankid ON DocImageAsset (BankId);

ALTER TABLE DocContentBlock ADD COLUMN BankId integer;
UPDATE DocContentBlock cb
    SET BankId = (SELECT i.BankId FROM DocInstance i WHERE i.DocInstanceId = cb.InstanceId);
ALTER TABLE DocContentBlock ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE DocContentBlock
    ADD CONSTRAINT fk_doccontentblock_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_doccontentblock_bankid ON DocContentBlock (BankId);

ALTER TABLE ExtractionJobCounter ADD COLUMN BankId integer;
UPDATE ExtractionJobCounter c
    SET BankId = (SELECT j.BankId FROM ExtractionJob j WHERE j.ExtractionJobId = c.JobId);
ALTER TABLE ExtractionJobCounter ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE ExtractionJobCounter
    ADD CONSTRAINT fk_extractionjobcounter_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_extractionjobcounter_bankid ON ExtractionJobCounter (BankId);

-- The remaining extraction children reference their job/source through a
-- varchar id (Mongo-store parity, no FK path), so the join is best-effort by
-- cast and anything unmatched lands in the default bank.

ALTER TABLE ExtractionRawRecord ADD COLUMN BankId integer;
UPDATE ExtractionRawRecord r
    SET BankId = COALESCE(
        (SELECT j.BankId FROM ExtractionJob j WHERE CAST(j.ExtractionJobId AS varchar) = r.JobId),
        (SELECT BankId FROM Bank WHERE Code = 'DEFAULT'));
ALTER TABLE ExtractionRawRecord ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE ExtractionRawRecord
    ADD CONSTRAINT fk_extractionrawrecord_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_extractionrawrecord_bankid ON ExtractionRawRecord (BankId);

ALTER TABLE ExtractionProcessedRecord ADD COLUMN BankId integer;
UPDATE ExtractionProcessedRecord p
    SET BankId = COALESCE(
        (SELECT j.BankId FROM ExtractionJob j WHERE CAST(j.ExtractionJobId AS varchar) = p.JobId),
        (SELECT BankId FROM Bank WHERE Code = 'DEFAULT'));
ALTER TABLE ExtractionProcessedRecord ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE ExtractionProcessedRecord
    ADD CONSTRAINT fk_extractionprocessedrecord_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_extractionprocessedrecord_bankid ON ExtractionProcessedRecord (BankId);

ALTER TABLE ExtractionAuditEvent ADD COLUMN BankId integer;
UPDATE ExtractionAuditEvent e
    SET BankId = COALESCE(
        (SELECT j.BankId FROM ExtractionJob j WHERE CAST(j.ExtractionJobId AS varchar) = e.JobId),
        (SELECT BankId FROM Bank WHERE Code = 'DEFAULT'));
ALTER TABLE ExtractionAuditEvent ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE ExtractionAuditEvent
    ADD CONSTRAINT fk_extractionauditevent_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_extractionauditevent_bankid ON ExtractionAuditEvent (BankId);

ALTER TABLE ExtractionConnectorState ADD COLUMN BankId integer;
UPDATE ExtractionConnectorState s
    SET BankId = COALESCE(
        (SELECT src.BankId FROM ExtractionSource src
            WHERE CAST(src.ExtractionSourceId AS varchar) = s.SourceId),
        (SELECT BankId FROM Bank WHERE Code = 'DEFAULT'));
ALTER TABLE ExtractionConnectorState ALTER COLUMN BankId SET NOT NULL;
ALTER TABLE ExtractionConnectorState
    ADD CONSTRAINT fk_extractionconnectorstate_bankid FOREIGN KEY (BankId) REFERENCES Bank (BankId);
CREATE INDEX IF NOT EXISTS ix_extractionconnectorstate_bankid ON ExtractionConnectorState (BankId);
