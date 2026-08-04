import { api } from './client';
import { invalidateTemplates } from './cache';

export const getTemplate = (id) => api.get(`/Templates/GetById/${id}`);

export async function createWithFields(payload) {
  const created = await api.post('/Templates/CreateWithFields', payload);
  invalidateTemplates();
  return created;
}

export async function updateTemplate(template) {
  const updated = await api.put('/Templates/Update', template);
  invalidateTemplates();
  return updated;
}

export async function deleteTemplate(id) {
  await api.del(`/Templates/${id}`);
  invalidateTemplates();
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
