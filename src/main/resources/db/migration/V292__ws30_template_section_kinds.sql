-- Template section kinds (FORM vs NARRATIVE) and optional field schema JSON (V292).
-- DocInstance field values for form-style fills.

ALTER TABLE DocBlueprintSection ADD COLUMN IF NOT EXISTS SectionKind varchar(16) NOT NULL DEFAULT 'NARRATIVE';
ALTER TABLE DocBlueprintSection ADD COLUMN IF NOT EXISTS FieldSchema text;

ALTER TABLE DocInstance ADD COLUMN IF NOT EXISTS FieldValuesJson text;
