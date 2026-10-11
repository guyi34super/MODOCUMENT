import { api } from './client';
import { invalidateTemplates } from './cache';

export const getTemplate = (id) => api.get(`/Templates/GetById/${id}`);

export async function createWithFields(payload) {
  const created = await api.post('/Templates/CreateWithFields', payload);
  invalidateTemplates();
  return created;
}

// ws10 (SEC-023): in production, update/delete answer 202 with a pending
// change that a second user must approve (callers detect it with
// isPendingChange from ./changes); with the maker/checker template gate
// configured off they apply directly, as before.

export async function updateTemplate(template) {
  const updated = await api.put('/Templates/Update', template);
  invalidateTemplates();
  return updated;
}

export async function deleteTemplate(id) {
  const result = await api.del(`/Templates/${id}`);
  invalidateTemplates();
  return result;
}

// ws22 bank-shared templates: GetAll/GetById return { templateId, name,
// description, visibility: 'PRIVATE'|'BANK', ownedByMe, editable, ownerName }.

/**
 * template:publish (bank ADMIN) only: share a template with the whole bank
 * ('BANK') or make it its owner's alone again ('PRIVATE'). Direct (no
 * approval); 409 while colleagues still have unfinished uploads on it.
 */
export async function setTemplateVisibility(id, visibility) {
  const updated = await api.put(`/Templates/${id}/Visibility`, { visibility });
  invalidateTemplates();
  return updated;
}

/** Without a templateId the server returns every template's fields; pass one. */
export const getAllFields = (templateId) =>
  api.get(
    templateId == null
      ? '/TemplateFields/GetAll'
      : `/TemplateFields/GetAll?templateId=${encodeURIComponent(templateId)}`
  );

export const createField = (field) => api.post('/TemplateFields/Create', field);

export const updateField = (id, field) => api.put(`/TemplateFields/Update/${id}`, field);

export const deleteField = (id) => api.del(`/TemplateFields/${id}`);
