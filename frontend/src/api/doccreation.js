import { api, downloadFile } from './client';

// ---------------------------------------------------------------- blueprints

export function uploadBlueprint(file, name, companyId) {
  const form = new FormData();
  form.append('file', file);
  if (name) form.append('name', name);
  if (companyId) form.append('companyId', companyId);
  return api.post('/api/doccreation/blueprints', form);
}

export function listBlueprints() {
  return api.get('/api/doccreation/blueprints');
}

export function getBlueprint(id) {
  return api.get(`/api/doccreation/blueprints/${id}`);
}

export function downloadBlueprintFile(id, fallbackName = 'blueprint.docx') {
  return downloadFile(`/api/doccreation/blueprints/${id}/file`, fallbackName);
}

export function updateSectionKeywords(blueprintId, sectionId, keywords) {
  return api.put(`/api/doccreation/blueprints/${blueprintId}/sections/${sectionId}/keywords`, {
    keywords,
  });
}

export function deleteBlueprint(id) {
  return api.del(`/api/doccreation/blueprints/${id}`);
}

// ----------------------------------------------------------------- instances

export function createInstance(blueprintId, title) {
  return api.post('/api/doccreation/instances', { blueprintId, title });
}

export function listInstances(blueprintId) {
  const query = blueprintId ? `?blueprintId=${blueprintId}` : '';
  return api.get(`/api/doccreation/instances${query}`);
}

export function getInstance(id) {
  return api.get(`/api/doccreation/instances/${id}`);
}

export function renameInstance(id, title) {
  return api.put(`/api/doccreation/instances/${id}`, { title });
}

export function deleteInstance(id) {
  return api.del(`/api/doccreation/instances/${id}`);
}

// -------------------------------------------------------------------- blocks

export function addBlock(instanceId, sectionId, block) {
  return api.post(`/api/doccreation/instances/${instanceId}/sections/${sectionId}/blocks`, block);
}

export function updateBlock(blockId, changes) {
  return api.put(`/api/doccreation/blocks/${blockId}`, changes);
}

export function reorderBlocks(instanceId, sectionId, blockIds) {
  return api.put(`/api/doccreation/instances/${instanceId}/sections/${sectionId}/blocks/order`, {
    blockIds,
  });
}

export function deleteBlock(blockId) {
  return api.del(`/api/doccreation/blocks/${blockId}`);
}

// -------------------------------------------------------------------- assets

export function uploadAssets(instanceId, files, autoAssign = true) {
  const form = new FormData();
  for (const file of files) form.append('files', file);
  form.append('autoAssign', String(autoAssign));
  return api.post(`/api/doccreation/instances/${instanceId}/assets`, form);
}

export function assignAsset(assetId, sectionId) {
  return api.put(`/api/doccreation/assets/${assetId}/assignment`, { sectionId });
}

export function deleteAsset(assetId) {
  return api.del(`/api/doccreation/assets/${assetId}`);
}

export function assetContentPath(assetId) {
  return `/api/doccreation/assets/${assetId}/content`;
}

// ------------------------------------------------------------ release (ws11)

// Separation of duties (gate B8): the owner submits a GENERATED document;
// only a DIFFERENT user holding doc:release can release or reject it — a
// self-release is a hard 409 server-side regardless of role. Releasing an
// AI-composed document is the recorded human review (C10), so the pending
// list carries the content blocks and the aiComposed flag.

/** Owner: sends the generated document for release review (→ SUBMITTED). */
export function submitInstance(id) {
  return api.post(`/api/doccreation/instances/${id}/submit`);
}

/** Approver (doc:release): the bank's queue, content blocks included. */
export function listPendingReleases() {
  return api.get('/api/doccreation/instances/pending-release');
}

/** Approver: releases — the backend hard-blocks the author/submitter (409). */
export function releaseInstance(id, comment) {
  return api.post(`/api/doccreation/instances/${id}/release`, { comment });
}

/** Approver: rejects — the document returns to GENERATED for rework. */
export function rejectInstance(id, comment) {
  return api.post(`/api/doccreation/instances/${id}/reject`, { comment });
}

/** Approver: the submitted document exactly as last generated. */
export function downloadReviewFile(id, format, fallbackName) {
  return downloadFile(
    `/api/doccreation/instances/${id}/review-file?format=${format}`,
    fallbackName || `document.${format}`
  );
}

// ---------------------------------------------------------------- generation

export function generateInstance(id) {
  return api.post(`/api/doccreation/instances/${id}/generate`);
}

export function downloadInstance(id, format, fallbackName) {
  return downloadFile(
    `/api/doccreation/instances/${id}/download?format=${format}`,
    fallbackName || `document.${format}`
  );
}
