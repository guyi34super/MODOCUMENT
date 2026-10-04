import { api, downloadFile } from './client';

// ws14: retention purge + data-subject requests. Every call is ADMIN-only
// (user:admin) and the backend scopes everything to the caller's own bank.

/** Policy + the bank's last RETENTION_PURGE_COMPLETED run (the job log). */
export const retentionStatus = () => api.get('/api/admin/retention/status');

/** Manual trigger of the daily purge — only enforces the configured policy. */
export const runRetention = () => api.post('/api/admin/retention/run');

/** Per-store hit summary for a subject identifier (ids + counts, no values). */
export const dsrSearch = (identifier) => api.post('/api/admin/dsr/search', { identifier });

/** ZIP download (JSON + readable text). POST: the identifier never hits a URL. */
export const dsrExport = (identifier) =>
  downloadFile('/api/admin/dsr/export', 'dsr-export.zip', {
    method: 'POST',
    body: { identifier },
  });

/** Irreversible: removes the subject's rows from every store. */
export const dsrErase = (identifier) =>
  api.post('/api/admin/dsr/erase', { identifier, confirm: true });
