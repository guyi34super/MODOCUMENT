-- Exact PDF templates (docs/doccreation/pdf-templates-contract.md §4, ws31).
-- A template is now the ORIGINAL PDF (data removed in the WITH_DATA flow)
-- plus an overlay of fill-in regions; output is PDF only. The Word path
-- (.docx reflow, sections, content blocks) is retired:
--
--  * DocBlueprint.Kind — PDF_OVERLAY for every new template; the existing
--    rows default to DOCX_LEGACY. Legacy templates are hidden from the lists,
--    answer 410 when opened, and are removed (with their documents and
--    stored files) by the audited admin purge
--    POST /api/admin/doccreation/purge-legacy.
--  * OriginalFileKey — the FileStore key of the uploaded PDF, kept unchanged.
--  * PageCount / UploadFlow (BLANK | WITH_DATA) / PagesJson (the engine's
--    PageInfo list: cropBox size in points and /Rotate per page).
--  * DocBlueprintVersion.TemplatePdfKey — the template PDF of that version
--    (template edits and data removal produce a new file per version).
--
-- EXPAND/CONTRACT (docs/release/migration-policy.md): added columns only,
-- Kind with a default. DocBlueprintSection / DocContentBlock and the
-- DocInstance FieldValuesJson / LastGeneratedDocx columns are dropped in a
-- later release.
--
-- SCHEMA PARITY: the doccreation entities mirror this file on the H2 test
-- schema (ddl-auto: create).

ALTER TABLE DocBlueprint ADD COLUMN IF NOT EXISTS Kind varchar(16) NOT NULL DEFAULT 'DOCX_LEGACY';
ALTER TABLE DocBlueprint ADD COLUMN IF NOT EXISTS OriginalFileKey varchar(512);
ALTER TABLE DocBlueprint ADD COLUMN IF NOT EXISTS PageCount integer;
ALTER TABLE DocBlueprint ADD COLUMN IF NOT EXISTS UploadFlow varchar(16);
ALTER TABLE DocBlueprint ADD COLUMN IF NOT EXISTS PagesJson text;

ALTER TABLE DocBlueprintVersion ADD COLUMN IF NOT EXISTS TemplatePdfKey varchar(512);

-- The purge and the lists select a bank's templates by kind.
CREATE INDEX IF NOT EXISTS ix_docblueprint_bankid_kind ON DocBlueprint (BankId, Kind);
