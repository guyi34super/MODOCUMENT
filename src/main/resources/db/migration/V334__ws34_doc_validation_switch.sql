-- Validation on/off in document creation (ws34; see section 8 of
-- docs/doccreation/data-templates-contract.md): a PDF template sets whether new
-- documents are validated (the default), and each document keeps its own
-- choice (set when it is created, changeable while it is edited). With
-- validation off, generate/download are not gated on validation.
--
-- Existing rows keep validation on (the behaviour so far).
-- EXPAND/CONTRACT (docs/release/migration-policy.md): columns with defaults.
-- ROW LEVEL SECURITY: both tables already carry BankId.
-- SCHEMA PARITY: the DocBlueprint and DocInstance entities mirror this file.

ALTER TABLE DocBlueprint ADD COLUMN IF NOT EXISTS ValidationEnabled boolean NOT NULL DEFAULT true;
ALTER TABLE DocInstance ADD COLUMN IF NOT EXISTS ValidationEnabled boolean NOT NULL DEFAULT true;
