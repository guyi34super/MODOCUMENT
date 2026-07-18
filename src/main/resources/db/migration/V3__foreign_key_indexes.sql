-- PostgreSQL does not index foreign-key columns automatically; these back the
-- app's per-template lookups and job/history joins.

CREATE INDEX IF NOT EXISTS ix_templatefields_templateid ON TemplateFields (TemplateId);
CREATE INDEX IF NOT EXISTS ix_templatefields_templatedatatypeid ON TemplateFields (TemplateDataTypeId);
CREATE INDEX IF NOT EXISTS ix_validationrules_templateid ON ValidationRules (TemplateId);
CREATE INDEX IF NOT EXISTS ix_importhistory_templateid ON ImportHistory (TemplateId);
CREATE INDEX IF NOT EXISTS ix_importdetails_importhistoryid ON ImportDetails (ImportHistoryId);
CREATE INDEX IF NOT EXISTS ix_importdetails_processstepid ON ImportDetails (ProcessStepId);
CREATE INDEX IF NOT EXISTS ix_ingestionjob_templateid ON IngestionJob (TemplateId);
CREATE INDEX IF NOT EXISTS ix_ingestionjob_importhistoryid ON IngestionJob (ImportHistoryId);
