-- Document creation flow (docs/doccreation/data-templates-contract.md §1,
-- ws34): a data source kept in step automatically from a PDF template's
-- data-field mapping (region -> data field -> flow field) is Managed; sources
-- the user set up by hand (Managed = false) are never changed by it.
--
-- EXPAND/CONTRACT (docs/release/migration-policy.md): a column with a default.
-- SCHEMA PARITY: the DocBlueprintSource entity mirrors this file on H2.

ALTER TABLE DocBlueprintSource ADD COLUMN IF NOT EXISTS Managed boolean NOT NULL DEFAULT false;
