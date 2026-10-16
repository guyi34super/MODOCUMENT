// Shared helpers for the document builder (pages/DocBuilderPage.jsx and its parts).

/** Region kinds that take a placed photo instead of a typed value. */
export const PHOTO_KINDS = new Set(['IMAGE', 'SIGNATURE']);

/** The name a field is shown by: its label, or its key when it has none. */
export const fieldLabel = (region) => region.label?.trim() || region.key;
