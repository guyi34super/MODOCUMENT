// The rule set of a data template as the Validation page edits it: the same
// form as Document Flow → Set Field Rules (Field / Required or Optional /
// Format rule type), plus the extra Document Creation rule types.

import { FORMAT_RULE_TYPE_OPTIONS } from '../../api/validationRules';

const flowLabel = (value) => FORMAT_RULE_TYPE_OPTIONS.find((o) => o.value === value)?.label;

/** "Format rule type" options: the flow's where they fit, then the Document Creation extras. */
export const DATA_RULE_OPTIONS = [
  { value: 'NONE', label: flowLabel('NONE') },
  { value: 'EMAIL', label: flowLabel('EMAIL') },
  { value: 'REGEX', label: flowLabel('REGEX') },
  { value: 'RANGE', label: flowLabel('RANGE') },
  { value: 'DATE_FORMAT', label: 'Date format — accepted formats' },
  { value: 'EXPIRY_DATE', label: flowLabel('EXPIRY_DATE') },
  { value: 'LENGTH', label: 'Length — min and max characters' },
  { value: 'ALLOWED_VALUES', label: 'Allowed values — one of a list' },
  { value: 'DATE_RANGE', label: 'Date range — earliest and latest' },
  { value: 'REQUIRED_IF', label: 'Required if — another field is filled' },
  { value: 'COMPARE', label: 'Compare with another field' },
];

const NAMES_OTHER_FIELD = new Set(['REQUIRED_IF', 'COMPARE']);

/** True when `ruleTypes` (the DTO's [{ type, kinds }]) let `type` go on a field of `kind`. */
export function ruleFits(ruleTypes, type, kind) {
  const entry = ruleTypes.find((t) => t.type === type);
  if (!entry) return false;
  return !entry.kinds?.length || entry.kinds.includes(kind);
}

/** The format rule types on offer for a field of `kind` (None always). */
export function ruleOptionsFor(ruleTypes, kind) {
  return DATA_RULE_OPTIONS.filter((o) => o.value === 'NONE' || (kind && ruleFits(ruleTypes, o.value, kind)));
}

export const isRequired = (rules, fieldName) => rules.some((r) => r.fieldName === fieldName && r.ruleType === 'REQUIRED');

/**
 * The table rows: every rule other than REQUIRED (Required is a column),
 * plus the REQUIRED rule of a field that has nothing else, in field order.
 */
export function ruleRows(rules, fields) {
  const order = new Map(fields.map((f, i) => [f.name, i]));
  const withOther = new Set(rules.filter((r) => r.ruleType !== 'REQUIRED').map((r) => r.fieldName));
  return rules
    .map((rule, index) => ({ rule, index }))
    .filter(({ rule }) => rule.ruleType !== 'REQUIRED' || !withOther.has(rule.fieldName))
    .sort((a, b) => (order.get(a.rule.fieldName) ?? 1e9) - (order.get(b.rule.fieldName) ?? 1e9) || a.index - b.index)
    .map(({ rule }) => rule);
}

/**
 * The rule set after "Save rule" / "Update rule".
 *
 * `form`: { fieldName, requirement: 'required'|'optional', ruleType (a
 * DATA_RULE_OPTIONS value), config, message, severity }.
 * `editing`: the rule loaded by Update, or null.
 *
 * - Required/Optional adds or removes the field's REQUIRED rule.
 * - The format rule replaces the rule being updated (None removes it); a
 *   new one replaces a rule of the same type on that field, else is added.
 * - With no format rule, the message and Warning only go to REQUIRED.
 */
export function applyRuleForm(rules, form, editing) {
  const { fieldName, requirement, ruleType } = form;
  const message = form.message?.trim() || null;
  const severity = form.severity || 'ERROR';
  let list = [...rules];

  if (ruleType !== 'NONE') {
    const fresh = { fieldName, ruleType, config: form.config || {}, message, severity, origin: 'MANUAL' };
    const at =
      editing && editing.ruleType !== 'REQUIRED'
        ? list.indexOf(editing)
        : list.findIndex((r) => r.fieldName === fieldName && r.ruleType === ruleType);
    if (at >= 0) {
      const before = list[at];
      // An unchanged type on the same field keeps where it came from.
      const keep = before.ruleType === ruleType && before.fieldName === fieldName;
      list[at] = keep ? { ...before, ...fresh, origin: before.origin || 'MANUAL' } : fresh;
    } else {
      list.push(fresh);
    }
  } else if (editing && editing.ruleType !== 'REQUIRED') {
    list = list.filter((r) => r !== editing);
  }

  const required = list.find((r) => r.fieldName === fieldName && r.ruleType === 'REQUIRED');
  if (requirement === 'required') {
    if (!required) {
      const first = list.findIndex((r) => r.fieldName === fieldName);
      const rule = {
        fieldName,
        ruleType: 'REQUIRED',
        config: {},
        message: ruleType === 'NONE' ? message : null,
        severity: ruleType === 'NONE' ? severity : 'ERROR',
        origin: 'MANUAL',
      };
      list.splice(first < 0 ? list.length : first, 0, rule);
    } else if (ruleType === 'NONE') {
      list = list.map((r) => (r === required ? { ...r, message, severity } : r));
    }
  } else if (required) {
    list = list.filter((r) => r !== required);
  }
  return list;
}

/** Rules that go when a field is deleted: its own, and REQUIRED_IF / COMPARE rules naming it. */
export function rulesGoingWith(rules, name) {
  return rules.filter((r) => r.fieldName === name || (NAMES_OTHER_FIELD.has(r.ruleType) && r.config?.Field === name));
}

/**
 * The saved rules carried over a field rename (`renames`: Map old → new):
 * rules of fields that are gone, and REQUIRED_IF / COMPARE naming one, drop out.
 */
export function renameRules(rules, renames, names) {
  const to = (name) => renames.get(name) ?? name;
  return rules
    .map((r) => {
      const next = { ...r, fieldName: to(r.fieldName) };
      if (NAMES_OTHER_FIELD.has(r.ruleType) && r.config?.Field) next.config = { ...r.config, Field: to(r.config.Field) };
      return next;
    })
    .filter((r) => names.has(r.fieldName) && (!NAMES_OTHER_FIELD.has(r.ruleType) || names.has(r.config?.Field)));
}

/** Where a rule came from, as the Source column says it. */
export function sourceText(rule) {
  if (rule.origin !== 'IMPORTED') return 'Manual';
  const template = rule.sourceTemplateName || (rule.sourceTemplateId != null ? `Template ${rule.sourceTemplateId}` : 'a flow template');
  return rule.sourceFieldName ? `From ${template}.${rule.sourceFieldName}` : `From ${template}`;
}
