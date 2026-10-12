import { api, downloadFile } from './client';

// Audit log viewer (v1.2.0, docs/optimization/contracts.md §3) — AUDIT_READ
// (audit:read): ADMIN, APPROVER (read-only) and PLATFORM_ADMIN.
//
//   GET /api/admin/audit?actor&action(repeatable)&entityType&entityId&outcome
//       &from&to&cursor&limit  → {events:[AuditEventView], nextCursor|null}
//       newest first, keyset on (OccurredAt, AuditEventId); `cursor` is opaque.
//   AuditEventView = {id, occurredAt, action, outcome, actorUserId, actorEmail,
//       actorRole, entityType, entityId, ip, requestId, oldValues, newValues}
//       (values already masked for the caller's role).
//   GET /api/admin/audit/export?…same filters… → text/csv (≤ 50 000 rows)
//   GET /api/admin/audit/actions → {actions:[{name, category}]}
//   GET /api/platform/audit (platform bank only) → same shape as the bank list
//   GET /api/platform/audit/counts?from&to → {banks:[{bankId, code, total, denied, failed}]}
//
// Viewing and exporting are themselves audited server-side
// (AUDIT_LOG_VIEWED, AUDIT_LOG_EXPORTED).

export const AUDIT_OUTCOMES = ['SUCCESS', 'DENIED', 'FAILED'];
export const AUDIT_PAGE_SIZE = 50;

const LIST_PATHS = { bank: '/api/admin/audit', platform: '/api/platform/audit' };

/**
 * The query string for a filter set: {actor, actions:[…], entityType,
 * entityId, outcome, from, to} (+ cursor, limit). `from`/`to` are ISO-8601
 * instants; empty values are left out; `action` repeats per action.
 */
export function auditQuery(filters = {}, { cursor, limit } = {}) {
  const query = new URLSearchParams();
  const add = (key, value) => {
    if (value === undefined || value === null) return;
    const text = String(value).trim();
    if (text) query.append(key, text);
  };
  add('actor', filters.actor);
  for (const action of filters.actions || []) add('action', action);
  add('entityType', filters.entityType);
  add('entityId', filters.entityId);
  add('outcome', filters.outcome);
  add('from', filters.from);
  add('to', filters.to);
  add('cursor', cursor);
  add('limit', limit);
  return query.toString();
}

const withQuery = (path, query) => (query ? `${path}?${query}` : path);

/** One keyset page of events; `scope` 'bank' (default) or 'platform'. */
export const listAuditEvents = (filters, { cursor, limit = AUDIT_PAGE_SIZE, scope = 'bank' } = {}) =>
  api.get(withQuery(LIST_PATHS[scope] || LIST_PATHS.bank, auditQuery(filters, { cursor, limit })));

/** The action catalogue for the filter picker (fixed per release: asked once per session). */
let actionsPromise = null;

export function listAuditActions() {
  if (!actionsPromise) {
    actionsPromise = api.get('/api/admin/audit/actions').catch((err) => {
      actionsPromise = null;
      throw err;
    });
  }
  return actionsPromise;
}

/** Test hook. */
export function resetAuditActionsCache() {
  actionsPromise = null;
}

/** Where the CSV export of a filter set lives (no cursor/limit: the server caps it). */
export const auditExportPath = (filters) =>
  withQuery('/api/admin/audit/export', auditQuery(filters));

/** Downloads the CSV (Bearer-authenticated, so not a plain link). */
export const exportAuditCsv = (filters) => downloadFile(auditExportPath(filters), 'audit-log.csv');

/** Platform console: per-bank event counts over a period (counts only, no rows). */
export const getPlatformAuditCounts = ({ from, to } = {}) =>
  api.get(withQuery('/api/platform/audit/counts', auditQuery({ from, to })));
