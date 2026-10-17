import { api } from './client';

// Data templates and the PDF template ↔ data template mapping
// (docs/doccreation/data-templates-contract.md §2 and §3).

const BASE = '/api/doccreation/data-templates';
const blueprintPath = (blueprintId) => `/api/doccreation/blueprints/${blueprintId}`;

/** The data field kinds (DocDataField.Kind). CHOICE shows as "Dropdown". */
export const DATA_FIELD_KINDS = ['TEXT', 'MULTILINE', 'DATE', 'NUMBER', 'CHOICE', 'CHECKBOX', 'IMAGE'];

/**
 * Which data field kinds a PDF field (region) kind accepts (contract §3,
 * "Compatible kinds"); the server refuses any other pairing with a 400.
 */
export const COMPATIBLE_KINDS = {
  TEXT: ['TEXT', 'CHOICE', 'NUMBER', 'DATE'],
  MULTILINE: ['TEXT', 'MULTILINE'],
  DATE: ['DATE', 'TEXT'],
  NUMBER: ['NUMBER', 'TEXT'],
  CHOICE: ['CHOICE', 'TEXT'],
  CHECKBOX: ['CHECKBOX'],
  IMAGE: ['IMAGE'],
  SIGNATURE: ['IMAGE'],
};

/** True when a PDF field of `regionKind` may take a data field of `fieldKind`. */
export function isCompatible(regionKind, fieldKind) {
  return (COMPATIBLE_KINDS[regionKind] || []).includes(fieldKind);
}

/**
 * [DataTemplateSummaryDto]: { id, name, description, fieldCount, ruleCount,
 * sourceTemplateId, sourceTemplateName, usedBy, updatedAt }.
 */
export function listDataTemplates() {
  return api.get(BASE);
}

/** `{ name, description? }` → 201 with the DataTemplateDto. */
export function createDataTemplate({ name, description }) {
  const body = { name };
  if (description) body.description = description;
  return api.post(BASE, body);
}

/**
 * DataTemplateDto: { id, name, description, revision, sourceTemplateId, sourceTemplateName,
 * fields: [{ name, label, kind, piiKind, props, sourceFieldName, sourceTemplateId, sourceTemplateName, sortOrder }],
 * rules: [{ id, fieldName, ruleType, config, message, severity, origin, sourceTemplateId,
 * sourceTemplateName, sourceFieldName, sortOrder }], usedBy: [{ blueprintId, name }], ruleTypes }.
 */
export function getDataTemplate(id) {
  return api.get(`${BASE}/${id}`);
}

/** Renames / describes a data template; resolves to the DataTemplateDto. */
export function updateDataTemplate(id, { name, description }) {
  return api.put(`${BASE}/${id}`, { name, description: description || null });
}

/** 204; 409 while a PDF template uses it (the message says how many). */
export function deleteDataTemplate(id) {
  return api.del(`${BASE}/${id}`);
}

/**
 * Replaces the field set (FieldInput: { name, label, kind, piiKind?, props?, sourceFieldName? });
 * rules of removed fields go too. A stale `baseRevision` answers 409.
 */
export function saveDataTemplateFields(id, { baseRevision, fields }) {
  return api.put(`${BASE}/${id}/fields`, { baseRevision, fields });
}

/** Replaces the rule set (RuleInput keyed by `fieldName`). A stale `baseRevision` answers 409. */
export function saveDataTemplateRules(id, { baseRevision, rules }) {
  return api.put(`${BASE}/${id}/rules`, { baseRevision, rules });
}

/**
 * Tests `values` ({ fieldName: value }) against the saved rules, or `rules` when given.
 * Nothing is stored; resolves to a ValidationResultDto (its `regionKey`s are field names).
 */
export function testDataTemplateRules(id, { values, rules } = {}) {
  const body = { values: values || {} };
  if (rules) body.rules = rules;
  return api.post(`${BASE}/${id}/rules/test`, body);
}

/**
 * What copying a flow template brings: { templateId, templateName, fields: [{ name, dataType,
 * kind, piiKind, rules: [{ ruleType, config, message }], skipped: [{ flowRuleType, reason }] }] }.
 */
export function copyPreview(templateId) {
  return api.get(`${BASE}/copy-preview?templateId=${encodeURIComponent(templateId)}`);
}

/** `{ templateId, name, fields: [flowFieldName] }` → 201 with the new DataTemplateDto. */
export function copyFromFlowTemplate({ templateId, name, fields }) {
  return api.post(`${BASE}/from-flow-template`, { templateId: Number(templateId), name, fields });
}

/** A source of the multi-template copy: `{ templateId, fields: [flowFieldName] }` (fields omitted = all). */
function toSource({ templateId, fields }) {
  const source = { templateId: Number(templateId) };
  if (fields) source.fields = fields;
  return source;
}

/**
 * Builds one new data template from the chosen fields of several flow templates (contract §2,
 * follow-up): `{ name, sources: [{ templateId, fields }] }` → 201 with the DataTemplateDto.
 * A clashing field name gets `_` + the slug of its flow template's name (then `_2`, `_3`…).
 */
export function buildFromFlowTemplates({ name, sources }) {
  return api.post(`${BASE}/from-flow-templates`, { name, sources: sources.map(toSource) });
}

/**
 * Adds the chosen flow fields (and their rules) to an existing data template:
 * `{ baseRevision, sources: [{ templateId, fields }] }` → the DataTemplateDto. A stale revision answers 409.
 */
export function addFromFlowTemplates(id, { baseRevision, sources }) {
  return api.post(`${BASE}/${id}/add-from-flow-templates`, { baseRevision, sources: sources.map(toSource) });
}

/** RuleInput of a data template rule as received (what PUT /rules takes back). */
export function toDataRuleInput(rule) {
  const input = {
    fieldName: rule.fieldName,
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

/** FieldInput of a data field as received (what PUT /fields takes back). */
export function toFieldInput(field) {
  const input = { name: field.name, label: field.label, kind: field.kind };
  if (field.piiKind) input.piiKind = field.piiKind;
  if (field.props) input.props = field.props;
  if (field.sourceFieldName) input.sourceFieldName = field.sourceFieldName;
  return input;
}

// ------------------------------------------- a PDF template's data template (§3)

/**
 * DataMappingDto: { dataTemplateId, dataTemplateName, revision, mappedCount, mappableCount,
 * rows: [{ regionKey, label, kind, page, fx, fy, fw, fh, mappedField,
 * suggestion: { field, score, sameName } | null }], fields: [{ name, label, kind }] }.
 */
export function getDataMapping(blueprintId) {
  return api.get(`${blueprintPath(blueprintId)}/data-mapping`);
}

/** Chooses (or with null clears) the data template; the mapping is pre-filled. Resolves to DataMappingDto. */
export function setBlueprintDataTemplate(blueprintId, dataTemplateId) {
  return api.put(`${blueprintPath(blueprintId)}/data-template`, {
    dataTemplateId: dataTemplateId == null || dataTemplateId === '' ? null : Number(dataTemplateId),
  });
}

/** Saves `{ regionKey: fieldName }`; resolves to DataMappingDto. */
export function saveDataMapping(blueprintId, mapping) {
  return api.put(`${blueprintPath(blueprintId)}/data-mapping`, { mapping });
}
