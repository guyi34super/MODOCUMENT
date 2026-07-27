-- Durable wizard-position checkpoint: reopening a job resumes the upload UI
-- at the pane the user last reached. NULL (pre-existing rows) falls back to
-- inference from CurrentStep in the frontend.
ALTER TABLE IngestionJob ADD COLUMN WizardStep varchar(20);
