-- Document creation validation (docs/doccreation/data-templates-contract.md
-- §1, ws34): a data template can now be built from SEVERAL Document Flow
-- templates, so each field remembers the flow template it came from.
-- DocDataTemplate.SourceTemplateId keeps the main one (most fields), which
-- drives the managed data source.
--
-- EXPAND/CONTRACT (docs/release/migration-policy.md): a nullable column only.
-- ROW LEVEL SECURITY: DocDataField already carries BankId (V330).
-- SCHEMA PARITY: the DocDataField entity mirrors this file on H2.

ALTER TABLE DocDataField ADD COLUMN IF NOT EXISTS SourceTemplateId integer;
