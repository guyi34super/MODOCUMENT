-- Better field editing (docs/doccreation/field-validation-contract.md §1, ws33):
-- the extra properties of a template field (region) — placeholder, help
-- text, default value, dropdown options, date/number output formats and a
-- max length — as one JSON object per region (null = none).
--
-- EXPAND/CONTRACT (docs/release/migration-policy.md): a nullable column only.
-- ROW LEVEL SECURITY: DocTemplateRegion already carries BankId (V301).
-- SCHEMA PARITY: the DocTemplateRegion entity mirrors this file on H2.

ALTER TABLE DocTemplateRegion ADD COLUMN IF NOT EXISTS FieldPropsJson text;
