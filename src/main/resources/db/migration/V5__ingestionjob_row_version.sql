-- Optimistic-lock column. Concurrent writers (resume + a cell-edit save)
-- each persist the full checkpoint set, so without a version the slower
-- writer silently reverts the other's changes. Existing rows start at 0.
ALTER TABLE IngestionJob ADD COLUMN RowVersion bigint NOT NULL DEFAULT 0;
