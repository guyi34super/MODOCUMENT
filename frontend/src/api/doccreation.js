import { api, downloadFile } from './client';

// Exact PDF templates (docs/doccreation/pdf-templates-contract.md §5).
// A template is the original PDF plus an overlay of fill-in regions and
// template edits. Every box the frontend sends or receives is in top-left
// page fractions (fx, fy, fw, fh); the API layer converts to PDF points and
// handles page rotation, so nothing here deals with either.

const BASE = '/api/doccreation';

function query(params) {
  const parts = Object.entries(params)
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ---------------------------------------------------------------- blueprints

/**
 * Uploads a PDF as a new template (201 BlueprintDto, status DRAFT).
 * `flow`: BLANK (the PDF is the template as-is), WITH_DATA (remove the
 * filled-in data first) or AUTO (the server decides; the default).
 */
export function uploadBlueprint(file, { name, companyId, flow = 'AUTO' } = {}) {
  const form = new FormData();
  form.append('file', file);
  if (name) form.append('name', name);
  if (companyId) form.append('companyId', companyId);
  form.append('flow', flow || 'AUTO');
  return api.post(`${BASE}/blueprints`, form);
}

/** BlueprintSummaryDto[]: { id, name, status, currentVersion, pageCount, flow, documentCount, companyId, createdDate }. */
export function listBlueprints() {
  return api.get(`${BASE}/blueprints`);
}

/** `version` (optional) reads an older version read-only; omitted = the current one. */
export function getBlueprint(id, version) {
  return api.get(`${BASE}/blueprints/${id}${query({ version })}`);
}

/**
 * The PNG of one page (0-based), for AuthedImage. `variant`: 'template'
 * (that version's template PDF) or 'original' (the uploaded PDF).
 */
export function blueprintPagePath(id, page, { variant = 'template', version, dpi } = {}) {
  return `${BASE}/blueprints/${id}/pages/${page}${query({ variant, version, dpi })}`;
}

/**
 * Saves the reviewed overlay: rebuilds the template PDF and stores it as a
 * new version (version 1 when confirming a draft). 409 when `baseVersion`
 * is stale, 400 for invalid geometry or duplicate keys.
 */
export function saveOverlay(id, { baseVersion, regions, edits, note, confirm = true }) {
  return api.put(`${BASE}/blueprints/${id}/overlay`, {
    baseVersion,
    regions,
    edits,
    note: note || undefined,
    confirm,
  });
}

/** Uploads a png/jpg for an ADD_IMAGE edit (a logo); resolves to { imageKey }. */
export function uploadEditImage(id, file) {
  const form = new FormData();
  form.append('file', file);
  return api.post(`${BASE}/blueprints/${id}/edit-images`, form);
}

export function listBlueprintVersions(id) {
  return api.get(`${BASE}/blueprints/${id}/versions`);
}

/** Downloads the original upload or a version's template PDF. */
export function downloadBlueprintFile(id, { variant = 'template', version, fallbackName } = {}) {
  return downloadFile(
    `${BASE}/blueprints/${id}/file${query({ variant, version })}`,
    fallbackName || `${variant}.pdf`
  );
}

export function deleteBlueprint(id) {
  return api.del(`${BASE}/blueprints/${id}`);
}

// ----------------------------------------------------------------- instances

/** 201 { id }; a DRAFT blueprint answers 409. */
export function createInstance(blueprintId, title) {
  return api.post(`${BASE}/instances`, { blueprintId, title });
}

export function listInstances(blueprintId) {
  return api.get(`${BASE}/instances${query({ blueprintId })}`);
}

export function getInstance(id) {
  return api.get(`${BASE}/instances/${id}`);
}

/** Merges `values` ({ key: value }) into the document; '' clears a key. Resolves to the InstanceDto. */
export function saveValues(id, values) {
  return api.put(`${BASE}/instances/${id}/values`, { values });
}

/**
 * Uploads photos to the document's tray. With `autoFill` the server places
 * them into IMAGE regions (file-name match, then reading order).
 * Resolves to { assigned: [{ imageId, regionKey }], unassigned: [imageId], rejected: [{ name, reason }] }.
 */
export function uploadImages(id, files, autoFill = false) {
  const form = new FormData();
  for (const file of files) form.append('files', file);
  form.append('autoFill', String(Boolean(autoFill)));
  return api.post(`${BASE}/instances/${id}/images`, form);
}

/** Places a photo into a region (swapping when it is filled), or back into the tray with null. */
export function placeImage(id, imageId, regionKey) {
  return api.put(`${BASE}/instances/${id}/images/${imageId}`, { regionKey: regionKey ?? null });
}

export function deleteImage(id, imageId) {
  return api.del(`${BASE}/instances/${id}/images/${imageId}`);
}

export function imageContentPath(id, imageId) {
  return `${BASE}/instances/${id}/images/${imageId}/content`;
}

/** A PNG preview of one page with the values filled in (not stored). */
export function instancePagePath(id, page, dpi) {
  return `${BASE}/instances/${id}/pages/${page}${query({ dpi })}`;
}

export function renameInstance(id, title) {
  return api.put(`${BASE}/instances/${id}`, { title });
}

export function deleteInstance(id) {
  return api.del(`${BASE}/instances/${id}`);
}

// ---------------------------------------------------------------- generation

/** Resolves to { pdfReady, addedPages, continuedKeys, exact, generatedDate }. */
export function generateInstance(id) {
  return api.post(`${BASE}/instances/${id}/generate`);
}

/** The document as a PDF (the server regenerates first when the output is stale). */
export function downloadInstance(id, fallbackName) {
  return downloadFile(`${BASE}/instances/${id}/download`, fallbackName || 'document.pdf');
}

// ------------------------------------------------------------ release (ws11)

// Separation of duties (gate B8): the owner submits a GENERATED document;
// only a DIFFERENT user holding doc:release can release or reject it — a
// self-release is a hard 409 server-side regardless of role.

/** Owner: sends the generated document for release review (→ SUBMITTED). */
export function submitInstance(id) {
  return api.post(`${BASE}/instances/${id}/submit`);
}

/** Approver (doc:release): the bank's queue. */
export function listPendingReleases() {
  return api.get(`${BASE}/instances/pending-release`);
}

/** Approver: releases — the backend hard-blocks the author/submitter (409). */
export function releaseInstance(id, comment) {
  return api.post(`${BASE}/instances/${id}/release`, { comment });
}

/** Approver: rejects — the document returns to GENERATED for rework. */
export function rejectInstance(id, comment) {
  return api.post(`${BASE}/instances/${id}/reject`, { comment });
}

/** Approver: the submitted document exactly as last generated. */
export function downloadReviewFile(id, format, fallbackName) {
  return downloadFile(
    `${BASE}/instances/${id}/review-file?format=${format}`,
    fallbackName || `document.${format}`
  );
}
