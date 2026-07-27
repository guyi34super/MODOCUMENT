import { api } from './client';

export const FIELD_REQUIREMENT_OPTIONS = [
  { value: 'required', label: 'Required' },
  { value: 'optional', label: 'Optional' },
];

/** Standard email pattern used by the Email format rule (stored as REGEX in the API). */
export const EMAIL_PATTERN = '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$';

/** Format validation rules (REQUIRED is managed separately). */
export const FORMAT_RULE_TYPE_OPTIONS = [
  { value: 'NONE', label: 'None — no format check' },
  { value: 'EMAIL', label: 'Email address — valid email format' },
  { value: 'REGEX', label: 'Format pattern — match text rules' },
  { value: 'RANGE', label: 'Number range — min and max' },
  { value: 'DATE_FORMAT', label: 'Date format — YYYY-MM-DD' },
  { value: 'EXPIRY_DATE', label: 'Expiry date — must be future' },
];

/** @deprecated Use FORMAT_RULE_TYPE_OPTIONS */
export const RULE_TYPE_OPTIONS = [
  { value: 'REQUIRED', label: 'Required — must not be empty' },
  ...FORMAT_RULE_TYPE_OPTIONS.filter((o) => o.value !== 'NONE'),
];

/** @deprecated Use FORMAT_RULE_TYPE_OPTIONS */
export const RULE_TYPES = RULE_TYPE_OPTIONS.map((o) => o.value);

const RULE_TYPE_NAMES = {
  0: 'CONDITIONAL_REQUIREMENT',
  1: 'DATE_FORMAT',
  2: 'EXPIRY_DATE',
  3: 'REQUIRED',
  4: 'REGEX',
  5: 'RANGE',
  6: 'TYPE',
  7: 'CUSTOM',
};

const REQUIRED_RULE_LABEL = 'Required — must not be empty';

export const ruleTypeName = (value) =>
  typeof value === 'number' ? (RULE_TYPE_NAMES[value] ?? value) : value;

const VALIDATION_ERROR_RULE_LABELS = {
  REQUIRED: 'Required',
  REGEX: 'Format pattern',
  RANGE: 'Number range',
  DATE_FORMAT: 'Date format',
  EXPIRY_DATE: 'Expiry date',
  EMAIL: 'Email',
  CONDITIONAL_REQUIREMENT: 'Conditional requirement',
  TYPE: 'Type',
  CUSTOM: 'Custom',
};

/** Friendly label for a validation error's ruleType (API enum name). */
export function validationErrorRuleLabel(ruleType) {
  if (!ruleType) return '—';
  const name = ruleTypeName(ruleType);
  return VALIDATION_ERROR_RULE_LABELS[name] ?? name;
}

export function parseRuleConfig(rule) {
  if (rule.config && typeof rule.config === 'object') return rule.config;
  if (rule.configJson) {
    try {
      return JSON.parse(rule.configJson);
    } catch {
      return {};
    }
  }
  return {};
}

export function isFieldRequired(rules, fieldName) {
  if (!rules || !fieldName) return false;
  return rules.some(
    (r) => r.fieldName === fieldName && ruleTypeName(r.ruleType) === 'REQUIRED'
  );
}

export function isEmailPattern(pattern) {
  return String(pattern ?? '') === EMAIL_PATTERN;
}

/** Maps UI-only format types to the RuleType persisted by the API. */
export function apiRuleType(ruleType) {
  return ruleType === 'EMAIL' ? 'REGEX' : ruleType;
}

/** Friendly label for a rule row in the table. */
export function formatRuleTypeLabel(rule) {
  const name = ruleTypeName(rule.ruleType);
  if (name === 'REQUIRED') return REQUIRED_RULE_LABEL;
  if (name === 'REGEX' && isEmailPattern(parseRuleConfig(rule).Pattern ?? parseRuleConfig(rule).pattern)) {
    const emailOption = FORMAT_RULE_TYPE_OPTIONS.find((o) => o.value === 'EMAIL');
    return emailOption ? emailOption.label : name;
  }
  const option = FORMAT_RULE_TYPE_OPTIONS.find((o) => o.value === name);
  return option ? option.label : name;
}

/** Full snapshot of form state when the user clicks Update on a table row. */
export function createEditSnapshot(rule, rules) {
  const form = loadRuleIntoFormState(rule, rules);
  const dbType = ruleTypeName(rule.ruleType);
  return {
    ...form,
    fieldName: rule.fieldName,
    ruleType: dbType === 'REQUIRED' ? 'REQUIRED' : form.ruleType,
  };
}

/** Whether the Required/Optional dropdown changed from the edit snapshot. */
export function shouldSyncRequired(current, initial) {
  return current !== initial;
}

/** Populate the form when the user clicks Update on a table row. */
export function loadRuleIntoFormState(rule, rules) {
  const type = ruleTypeName(rule.ruleType);
  const config = parseRuleConfig(rule);

  if (type === 'REQUIRED') {
    return {
      fieldRequirement: 'required',
      ruleType: 'NONE',
      pattern: '',
      rangeMin: '',
      rangeMax: '',
    };
  }

  let pattern = '';
  let rangeMin = '';
  let rangeMax = '';

  if (type === 'REGEX') {
    pattern = String(config.Pattern ?? config.pattern ?? '');
    if (isEmailPattern(pattern)) {
      return {
        fieldRequirement: isFieldRequired(rules, rule.fieldName) ? 'required' : 'optional',
        ruleType: 'EMAIL',
        pattern: EMAIL_PATTERN,
        rangeMin: '',
        rangeMax: '',
      };
    }
  } else if (type === 'RANGE') {
    const min = config.Min ?? config.min;
    const max = config.Max ?? config.max;
    rangeMin = min != null ? String(min) : '';
    rangeMax = max != null ? String(max) : '';
  }

  return {
    fieldRequirement: isFieldRequired(rules, rule.fieldName) ? 'required' : 'optional',
    ruleType: type,
    pattern,
    rangeMin,
    rangeMax,
  };
}

/** Human-readable settings for the rules table. */
export function formatRuleConfig(rule) {
  const type = ruleTypeName(rule.ruleType);
  const config = parseRuleConfig(rule);

  if (type === 'REQUIRED') {
    return '—';
  }
  if (type === 'REGEX') {
    const pattern = config.Pattern ?? config.pattern;
    if (isEmailPattern(pattern)) return 'e.g. jane@acme.com';
    return pattern ? String(pattern) : '—';
  }
  if (type === 'RANGE') {
    const min = config.Min ?? config.min;
    const max = config.Max ?? config.max;
    if (min != null && max != null) return `Between ${min} and ${max}`;
  }
  return '—';
}

/** Build the config payload sent to the API from form state. */
export function buildRuleConfig(ruleType, { pattern, rangeMin, rangeMax }) {
  if (ruleType === 'EMAIL') {
    return { Pattern: EMAIL_PATTERN };
  }
  if (ruleType === 'REGEX') {
    return { Pattern: pattern };
  }
  if (ruleType === 'RANGE') {
    return { Min: Number(rangeMin), Max: Number(rangeMax) };
  }
  return {};
}

export const getRulesByTemplate = (templateId) =>
  api.get(`/ValidationRules/GetByTemplate/${templateId}`);

export const upsertRule = (templateId, fieldName, ruleType, config) =>
  api.put('/ValidationRules/Upsert', { templateId, fieldName, ruleType, config });

export const deleteRule = (templateId, fieldName, ruleType) =>
  api.del(`/ValidationRules/${templateId}/${encodeURIComponent(fieldName)}/${ruleType}`);

export const validateRows = (templateId, rows) =>
  api.post('/Validation/validate', { templateId, rows });
