package com.modocument.domain.model;

/**
 * Furthest wizard pane the user reached in the upload UI. This is view state,
 * distinct from {@link JobStep} (pipeline data state): a user can sit on the
 * export pane while the job is still VALIDATED. Persisted by name so reopening
 * a job resumes the wizard at the same pane.
 */
public enum WizardStep {
    MAPPING,
    VALIDATION,
    EXPORT
}
