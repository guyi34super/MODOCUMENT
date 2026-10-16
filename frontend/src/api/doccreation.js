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

/**
 * My documents (InstanceSummaryDto[]). Filters: `blueprintId`, `status` (DRAFT, VALIDATED,
 * GENERATED, SUBMITTED, RELEASED). A bare number is read as the blueprint id.
 */
export function listInstances(filters = {}) {
  const { blueprintId, status } = typeof filters === 'object' && filters !== null ? filters : { blueprintId: filters };
  return api.get(`${BASE}/instances${query({ blueprintId, status })}`);
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

/**
 * Validates the document (built-in checks plus the blueprint's field rules): VALIDATED when
 * there are no errors, otherwise it stays (or goes back to) DRAFT. Resolves to the InstanceDto
 * with `validation` filled in (field-validation contract §4.2).
 */
export function validateInstance(id) {
  return api.post(`${BASE}/instances/${id}/validate`);
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

// ------------------------------------------- flow records (ws32 contract §4)

/**
 * The blueprint's saved data sources: [{ id, templateId, templateName, role: 'PRIMARY'|'LINKED',
 * linkFieldPrimary, linkFieldThis, mapping: { regionKey: fieldName }, fields: [{ name, dataType }],
 * suggestions: [{ regionKey, fieldName, score, sameName }] }].
 */
export function listBlueprintSources(blueprintId) {
  return api.get(`${BASE}/blueprints/${blueprintId}/sources`);
}

/**
 * Adds a flow template as a source (201 with the auto-mapped source). 409 for a second
 * PRIMARY or a duplicate template; 400 when a LINKED source has no (or unknown) link fields.
 */
export function addBlueprintSource(blueprintId, { templateId, role, linkFieldPrimary, linkFieldThis }) {
  const body = { templateId: Number(templateId), role };
  if (role === 'LINKED') {
    body.linkFieldPrimary = linkFieldPrimary;
    body.linkFieldThis = linkFieldThis;
  }
  return api.post(`${BASE}/blueprints/${blueprintId}/sources`, body);
}

/** Saves a source's mapping (region key → template field) and link fields. */
export function saveBlueprintSource(blueprintId, sourceId, { mapping, linkFieldPrimary, linkFieldThis }) {
  const body = { mapping };
  if (linkFieldPrimary !== undefined) body.linkFieldPrimary = linkFieldPrimary;
  if (linkFieldThis !== undefined) body.linkFieldThis = linkFieldThis;
  return api.put(`${BASE}/blueprints/${blueprintId}/sources/${sourceId}`, body);
}

/** Removes a source; removing the PRIMARY while LINKED sources exist answers 409. */
export function deleteBlueprintSource(blueprintId, sourceId) {
  return api.del(`${BASE}/blueprints/${blueprintId}/sources/${sourceId}`);
}

/**
 * What a record would fill: { recordId, templateId, mappingSource: 'SAVED'|'AUTO',
 * linked: [{ templateId, templateName, matchedBy, recordId, alternatives: [id] }],
 * fields: [{ regionKey, label, value, currentValue, sourceTemplateId, sourceField, willOverwrite, sameName }] }.
 */
export function getFillPreview(instanceId, recordId) {
  return api.get(`${BASE}/instances/${instanceId}/fill-preview${query({ recordId })}`);
}

/**
 * Fills the document from a record. `body`: { recordId, linkedRecordIds?: { templateId: recordId },
 * fields?: [regionKey], overwrite: 'EMPTY_ONLY'|'ALL' }. Resolves to the InstanceDto.
 */
export function fillFromRecord(instanceId, body) {
  return api.post(`${BASE}/instances/${instanceId}/fill-from-record`, body);
}

/**
 * Moves the document to the blueprint's current version. Resolves to the InstanceDto plus
 * `droppedKeys` (values whose region no longer exists); 409 when SUBMITTED or RELEASED.
 */
export function upgradeInstance(id) {
  return api.post(`${BASE}/instances/${id}/upgrade`);
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
