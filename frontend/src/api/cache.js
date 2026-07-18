import { api } from './client';

// Session-scoped caches so navigating between pages never re-fetches
// rarely-changing lists. Mutations must call the matching invalidate.
let dataTypesPromise = null;
let templatesPromise = null;

export function getTemplateDataTypes() {
  if (!dataTypesPromise) {
    dataTypesPromise = api.get('/TemplateDataTypes/GetAll').catch((err) => {
      dataTypesPromise = null;
      throw err;
    });
  }
  return dataTypesPromise;
}

export function getTemplates() {
  if (!templatesPromise) {
    templatesPromise = api.get('/Templates/GetAll').catch((err) => {
      templatesPromise = null;
      throw err;
    });
  }
  return templatesPromise;
}

export function invalidateTemplates() {
  templatesPromise = null;
}

export function clearCaches() {
  dataTypesPromise = null;
  templatesPromise = null;
}
