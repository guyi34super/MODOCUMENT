ALTER TABLE IngestionJob
    ADD COLUMN IF NOT EXISTS SuggestedMappingJson TEXT;
