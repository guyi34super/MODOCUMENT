import { api } from './client';

// Field rules of a PDF template (blueprint), docs/doccreation/field-validation-contract.md §3.
// Every route is under /api/doccreation/blueprints/{id}/rules.

const rulesPath = (blueprintId) => `/api/doccreation/blueprints/${blueprintId}/rules`;

/**
 * RulesDto: { revision, rules: [{ id, regionKey, ruleType, config, message, severity, origin,
 * sourceTemplateId, sourceTemplateName, sourceFieldName, sortOrder, orphan }],
 * fields: [{ key, label, kind, page }], ruleTypes: [{ type, kinds, configKeys }] }.
 */
export function getRules(blueprintId) {
  return api.get(rulesPath(blueprintId));
}

/**
 * Replaces the whole rule set (RuleInput: { regionKey, ruleType, config, message?, severity?,
 * origin?, sourceTemplateId?, sourceFieldName? }; the order per field is the list order).
 * A stale `baseRevision` answers 409. Resolves to the new RulesDto.
 */
export function saveRules(blueprintId, { baseRevision, rules }) {
  return api.put(rulesPath(blueprintId), { baseRevision, rules });
}

/**
 * Tests `values` ({ key: value }) against the saved rules, or against `rules` (RuleInput[])
 * when given — nothing is stored. Resolves to a ValidationResultDto.
 */
export function testRules(blueprintId, { values, rules } = {}) {
  const body = { values: values || {} };
  if (rules) body.rules = rules;
  return api.post(`${rulesPath(blueprintId)}/test`, body);
}

/**
 * What an import would bring: from the blueprint's data sources (no `templateId`) or from
 * one flow template. Resolves to an ImportPreviewDto.
 */
export function importPreview(blueprintId, templateId) {
  const query = templateId != null && templateId !== '' ? `?templateId=${encodeURIComponent(templateId)}` : '';
  return api.get(`${rulesPath(blueprintId)}/import-preview${query}`);
}

/**
 * Imports flow-template rules: `body` = { baseRevision, templateId?, mapping: { regionKey: fieldName },
 * mode: 'MERGE' | 'REPLACE' }. Resolves to the new RulesDto.
 */
export function importRules(blueprintId, body) {
  return api.post(`${rulesPath(blueprintId)}/import`, body);
}

/** RuleInput of a rule as received (what PUT /rules takes back). */
export function toRuleInput(rule) {
  const input = {
    regionKey: rule.regionKey,
    ruleType: rule.ruleType,
    config: rule.config || {},
    message: rule.message || null,
    severity: rule.severity || 'ERROR',
  };
  if (rule.origin) input.origin = rule.origin;
  if (rule.sourceTemplateId != null) input.sourceTemplateId = rule.sourceTemplateId;
  if (rule.sourceFieldName) input.sourceFieldName = rule.sourceFieldName;
  return input;
}
