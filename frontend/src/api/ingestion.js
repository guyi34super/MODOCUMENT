import { api } from './client';

export const listJobs = () => api.get('/api/ingestion/jobs');

export const getJob = (id) => api.get(`/api/ingestion/jobs/${id}`);

export function createJobFromFile(file, templateId, autoRun = true) {
  const form = new FormData();
  form.append('file', file);
  form.append('templateId', String(templateId));
  form.append('autoRun', String(autoRun));
  return api.post('/api/ingestion/jobs', form);
}

export const resumeJob = (id) => api.post(`/api/ingestion/jobs/${id}/resume`);
export const remapJob = (id) => api.post(`/api/ingestion/jobs/${id}/remap`);
export const revalidateJob = (id) => api.post(`/api/ingestion/jobs/${id}/revalidate`);
export const cancelJob = (id) => api.post(`/api/ingestion/jobs/${id}/cancel`);

export const chatEdit = (id, instruction) =>
  api.post(`/api/ingestion/jobs/${id}/chat-edit`, { instruction });

export const updateMapping = (id, mapping) =>
  api.put(`/api/ingestion/jobs/${id}/mapping`, { mapping });

export const exportFilePath = (id, format) =>
  `/api/ingestion/jobs/${id}/export/file?format=${format}`;

export const exportToDatabase = (id, payload) =>
  api.post(`/api/ingestion/jobs/${id}/export/database`, payload);
