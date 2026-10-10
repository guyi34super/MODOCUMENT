import { api } from './client';

// Editable settings with guardrails (phase 2, ws20 backend; SettingsPage UI).
// Contract: docs/phase2/contracts.md §3 (SE1).
//
// GET  → { layer: 'BANK'|'PLATFORM', revision, groups: [{ id, label, settings: [SettingView] }] }
// SettingView = { key, type: INT|DECIMAL|BOOLEAN|ENUM|DURATION, tier, scope, group, label,
//   description, unit, value, source: BANK|PLATFORM|DEFAULT, default, min, max, hardMin,
//   hardMax, allowed, requiresApproval, requiresRestart, locked, clamped, pendingChangeId,
//   version } — the platform layer adds bankMin, bankMax, hasBankBounds.
//
// PUT  { changes: [{ key, value, expectedVersion }], comment }
//   value: JSON-typed (number, boolean, or a string like "15m"); null resets to
//   the inherited value. Platform layer only: bankMin/bankMax (null clears),
//   and an ABSENT value leaves the value as is (a bounds-only change).
//   → 200 { applied: [SettingView], pendingChange: null }
//   → 202 { applied: [...], pendingChange: PendingChangeDto } (security tier)
// Refusals: { error, code, message, key } — 400 SETTING_OUT_OF_RANGE |
//   SETTING_LOCKED | SETTING_UNKNOWN; 409 SETTING_VERSION_CONFLICT |
//   SETTING_CHANGE_PENDING | USE_PLATFORM_SETTINGS. A PUT is judged as a
//   whole: one refusal means nothing changed.

/** Bank administrators (settings:read): the caller's bank, by tab. */
export const getBankSettings = () => api.get('/api/admin/settings');

/** settings:write: operational keys apply now, security keys go to approval. */
export const updateBankSettings = (changes, comment) =>
  api.put('/api/admin/settings', { changes, comment: comment || undefined });

/** Platform owner (platform:read): platform values, hard limits and bank bounds. */
export const getPlatformSettings = () => api.get('/api/platform/settings');

/** platform:settings: values and bank bounds (bounds always go to approval). */
export const updatePlatformSettings = (changes, comment) =>
  api.put('/api/platform/settings', { changes, comment: comment || undefined });

/** The two layers behind one interface, for SettingsPage's `mode`. */
export const settingsApi = (mode) =>
  mode === 'platform'
    ? { load: getPlatformSettings, save: updatePlatformSettings }
    : { load: getBankSettings, save: updateBankSettings };
