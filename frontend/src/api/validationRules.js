import { api } from './client';

export const RULE_TYPES = [
  'REQUIRED',
  'REGEX',
  'RANGE',
  'TYPE',
  'DATE_FORMAT',
  'EXPIRY_DATE',
  'CONDITIONAL_REQUIREMENT',
  'CUSTOM',
];

export const getRulesByTemplate = (templateId) =>
  api.get(`/ValidationRules/GetByTemplate/${templateId}`);

export const upsertRule = (templateId, fieldName, ruleType, config) =>
  api.put('/ValidationRules/Upsert', { templateId, fieldName, ruleType, config });

export const deleteRule = (templateId, fieldName, ruleType) =>
  api.del(`/ValidationRules/${templateId}/${encodeURIComponent(fieldName)}/${ruleType}`);

export const validateRows = (templateId, rows) =>
  api.post('/Validation/validate', { templateId, rows });
