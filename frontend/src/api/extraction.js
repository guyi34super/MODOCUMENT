import { api } from './client';

export const SOURCE_TYPES = ['WEBSITE', 'REST_API', 'DATABASE'];

export const listSources = () => api.get('/api/extraction/sources');
export const createSource = (payload) => api.post('/api/extraction/sources', payload);
export const updateSource = (id, payload) => api.put(`/api/extraction/sources/${id}`, payload);
export const deleteSource = (id) => api.del(`/api/extraction/sources/${id}`);

export const listJobs = () => api.get('/api/extraction/jobs');
export const getJob = (id) => api.get(`/api/extraction/jobs/${id}`);
export const waitForJob = (id) => api.get(`/api/extraction/jobs/${id}?wait=true`);
export const createJob = (payload) => api.post('/api/extraction/jobs', payload);
export const startJob = (id, wait = false) =>
  api.post(`/api/extraction/jobs/${id}/start${wait ? '?wait=true' : ''}`);
export const pauseJob = (id) => api.post(`/api/extraction/jobs/${id}/pause`);
export const resumeJob = (id, wait = false) =>
  api.post(`/api/extraction/jobs/${id}/resume${wait ? '?wait=true' : ''}`);
export const cancelJob = (id) => api.post(`/api/extraction/jobs/${id}/cancel`);

export const getRecords = (id, { kind = 'processed', afterId, limit = 50 } = {}) => {
  const params = new URLSearchParams({ kind, limit: String(limit) });
  if (afterId) params.set('afterId', afterId);
  return api.get(`/api/extraction/jobs/${id}/records?${params}`);
};

export const getAudit = (id, { afterId, limit = 100 } = {}) => {
  const params = new URLSearchParams({ limit: String(limit) });
  if (afterId) params.set('afterId', afterId);
  return api.get(`/api/extraction/jobs/${id}/audit?${params}`);
};

export const bridgeToIngestion = (id, templateId) =>
  api.post(`/api/extraction/jobs/${id}/bridge`, { templateId });
