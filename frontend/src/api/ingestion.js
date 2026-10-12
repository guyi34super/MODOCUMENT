import { api } from './client';
import { DEFAULT_PAGE_SIZE, pagedPath } from './paging';

/** The full job list (legacy shape; the server caps it at the 200 newest). */
export const listJobs = () => api.get('/api/ingestion/jobs');

/**
 * v1.2.0 (contracts §5): one page of job summaries, no JSON blobs →
 * `{items:[IngestionJobSummary], page, size, total}`; IngestionJobSummary =
 * {ingestionJobId, fileName, templateId, templateName, status, currentStep,
 * parsedBy, parseFallbackReason, validationErrorCount, unmappedFieldCount,
 * createdDate, updatedDate}. The full job stays on GET /jobs/{id}. Read the
 * answer through toPage() (api/paging.js): an older server answers an array.
 */
export const listJobSummaries = ({ page = 0, size = DEFAULT_PAGE_SIZE } = {}) =>
  api.get(pagedPath('/api/ingestion/jobs', { view: 'summary', page, size }));

/**
 * What this build can parse — drives the file picker's accept list. Fixed
 * for the life of the server process, so asked once per session (v1.2.0);
 * a failure is not memoized.
 */
let supportedTypesPromise = null;

export function getSupportedTypes() {
  if (!supportedTypesPromise) {
    supportedTypesPromise = api.get('/api/ingestion/supported-types').catch((err) => {
      supportedTypesPromise = null;
      throw err;
    });
  }
  return supportedTypesPromise;
}

export const getJob = (id) => api.get(`/api/ingestion/jobs/${id}`);

const STEP_ORDER = { UPLOADED: 0, PARSED: 1, MAPPED: 2, VALIDATED: 3, EXPORTED: 4 };

/** True while the job may still need to run pipeline stages before {@code until}. */
export function needsIngestionWait(job, until = 'MAPPED') {
  if (!job) return true;
  if (job.status === 'FAILED' || job.status === 'CANCELLED') return false;
  if (job.status === 'PENDING' || job.status === 'RUNNING') return true;
  const step = STEP_ORDER[job.currentStep] ?? 0;
  const target = STEP_ORDER[until] ?? STEP_ORDER.MAPPED;
  return step < target;
}

const inflightWaits = new Map();

export const waitForJob = (id, until = 'MAPPED') => {
  const key = `${id}:${until}`;
  if (!inflightWaits.has(key)) {
    inflightWaits.set(
      key,
      api.get(`/api/ingestion/jobs/${id}?wait=true&until=${until}`).finally(() => {
        inflightWaits.delete(key);
      })
    );
  }
  return inflightWaits.get(key);
};

export function createJobFromFile(file, templateId, autoRun = true, runUntil = 'MAPPED', wait = false) {
  const form = new FormData();
  form.append('file', file);
  form.append('templateId', String(templateId));
  form.append('autoRun', String(autoRun));
  form.append('runUntil', runUntil);
  form.append('wait', String(wait));
  return api.post('/api/ingestion/jobs', form);
}

export const resumeJob = (id, until = 'VALIDATED') =>
  api.post(`/api/ingestion/jobs/${id}/resume?until=${until}`);
export const remapJob = (id) => api.post(`/api/ingestion/jobs/${id}/remap`);
export const revalidateJob = (id) => api.post(`/api/ingestion/jobs/${id}/revalidate`);
export const cancelJob = (id) => api.post(`/api/ingestion/jobs/${id}/cancel`);

export const chatEdit = (id, { instruction, row, column } = {}) =>
  api.post(`/api/ingestion/jobs/${id}/chat-edit`, {
    instruction,
    ...(row != null ? { row } : {}),
    ...(column ? { column } : {}),
  });

export const updateMapping = (id, mapping) =>
  api.put(`/api/ingestion/jobs/${id}/mapping`, { mapping });

export const updateRows = (id, rows) => api.put(`/api/ingestion/jobs/${id}/rows`, { rows });

export const setWizardStep = (id, step) =>
  api.put(`/api/ingestion/jobs/${id}/wizard-step`, { wizardStep: step.toUpperCase() });

export const exportFilePath = (id, format) =>
  `/api/ingestion/jobs/${id}/export/file?format=${format}`;

/**
 * ws18 (gate D2): above the role's row cap the server answers 403 with
 * `code: EXPORT_CAP_EXCEEDED` (+ `cap`, `rows`, `reasonMaxLength`); the
 * export is retried with the user's stated reason. The file export then goes
 * as a POST so the free-text reason never sits in a URL.
 */
export const EXPORT_CAP_EXCEEDED = 'EXPORT_CAP_EXCEEDED';

/** Path + fetch options for a file export carrying an over-cap reason. */
export const exportFileWithReason = (id, format, reason) => ({
  path: `/api/ingestion/jobs/${id}/export/file`,
  options: { method: 'POST', body: { format, reason } },
});

export const exportToDatabase = (id, payload) =>
  api.post(`/api/ingestion/jobs/${id}/export/database`, payload);

/**
 * v1.2.0: the database export names its target the way an integration
 * connection does — vendor, host, port, database, schema, sslmode — and the
 * server builds the JDBC URL itself (DbDialect, with timeouts); a raw JDBC
 * URL is no longer accepted. The body:
 *   {vendor, host, port, database, schema, sslmode, username, password,
 *    table, tableName, createTableIfMissing, reason?}
 * `tableName` repeats `table` for a server still reading the pre-v1.2.0
 * field name (unknown JSON properties are ignored). Credentials are used for
 * the one export, never stored. Empty optional values are left out.
 */
export function databaseExportRequest(form, reason) {
  const port = Number.parseInt(String(form.port ?? '').trim(), 10);
  const body = {
    vendor: form.vendor,
    host: (form.host || '').trim(),
    port: Number.isFinite(port) ? port : undefined,
    database: (form.database || '').trim(),
    schema: (form.schema || '').trim() || undefined,
    sslmode: form.sslmode || undefined,
    username: form.username || undefined,
    password: form.password || undefined,
    table: (form.table || '').trim(),
    tableName: (form.table || '').trim(),
    createTableIfMissing: Boolean(form.createTableIfMissing),
  };
  if (reason) body.reason = reason;
  return Object.fromEntries(Object.entries(body).filter(([, value]) => value !== undefined));
}
