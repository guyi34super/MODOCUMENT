-- Document creation flow (docs/doccreation/data-templates-contract.md §1,
-- ws34): a PDF template (blueprint) chooses its validation (a data
-- template, V330) after upload and maps its fields onto the data fields.
--
--  * DataTemplateId  — the chosen data template (null = none yet).
--  * DataMappingJson — region key -> data field name (several regions may
--    show the same field).
--
-- EXPAND/CONTRACT (docs/release/migration-policy.md): nullable columns only.
-- SCHEMA PARITY: the DocBlueprint entity mirrors this file on H2.

ALTER TABLE DocBlueprint ADD COLUMN IF NOT EXISTS DataTemplateId integer
    REFERENCES DocDataTemplate (DocDataTemplateId);
ALTER TABLE DocBlueprint ADD COLUMN IF NOT EXISTS DataMappingJson text;
CREATE INDEX IF NOT EXISTS ix_docblueprint_datatemplate ON DocBlueprint (DataTemplateId);
