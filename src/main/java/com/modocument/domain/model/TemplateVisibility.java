package com.modocument.domain.model;

/**
 * Who may see and use a template (ws22, V220; docs/phase2/contracts.md §4).
 * Persisted by NAME in {@code Template.Visibility}.
 *
 * <ul>
 *   <li>{@link #PRIVATE} — the default: only the owner reads, uses and
 *       changes it (the pre-phase-2 behaviour).</li>
 *   <li>{@link #BANK} — shared with the owner's bank: every user of that
 *       bank reads it and uses it (uploads, extraction, validation); only a
 *       holder of {@link Permission#TEMPLATE_PUBLISH} changes it. Never
 *       visible to another bank.</li>
 * </ul>
 */
public enum TemplateVisibility {
    PRIVATE,
    BANK
}
