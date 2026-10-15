import { api } from './client';

// Flow records in document creation (docs/doccreation/flow-records-contract.md §4).
// A record is one validated row of an ingestion job, indexed per template.
// Values arrive masked per the caller's role; the UI shows them as returned.

const BASE = '/api/doccreation/records';
/** The server's page-size cap for record searches. */
export const MAX_RECORD_PAGE_SIZE = 100;

/**
 * One page of a template's visible records:
 * { items: [{ recordId, templateId, templateName, jobId, rowIndex, values, createdAt }], page, size, total }.
 * `q` matches every word (case-insensitive); with `field` only that field's value is searched.
 */
export function searchRecords({ templateId, q, field, page = 0, size = 20 } = {}) {
  const query = new URLSearchParams();
  if (templateId != null && templateId !== '') query.set('templateId', String(templateId));
  if (q && q.trim()) query.set('q', q.trim());
  if (field) query.set('field', field);
  query.set('page', String(Math.max(0, page)));
  query.set('size', String(Math.min(MAX_RECORD_PAGE_SIZE, Math.max(1, size))));
  return api.get(`${BASE}?${query.toString()}`);
}

/** One record in the same shape as a search item (read-audited server-side). */
export function getRecord(id) {
  return api.get(`${BASE}/${encodeURIComponent(id)}`);
}

/** A short human label for a record: its first non-empty values. */
export function recordLabel(record, max = 2) {
  if (!record) return '';
  const parts = Object.values(record.values || {})
    .map((v) => (v == null ? '' : String(v).trim()))
    .filter(Boolean)
    .slice(0, max);
  return parts.length ? parts.join(' · ') : `Record ${record.recordId}`;
}
