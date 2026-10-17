// Names and one-line summaries of field rules (field-validation contract §3).

export const RULE_LABELS = {
  REQUIRED: 'Required',
  REGEX: 'Pattern',
  EMAIL: 'E-mail address',
  LENGTH: 'Length',
  RANGE: 'Number range',
  ALLOWED_VALUES: 'Allowed values',
  DATE_FORMAT: 'Date format',
  DATE_RANGE: 'Date range',
  EXPIRY_DATE: 'Future date',
  REQUIRED_IF: 'Required if',
  COMPARE: 'Compare with a field',
};

/** Every rule type with the kinds it fits, used until the server's `ruleTypes` arrive. */
export const DEFAULT_RULE_TYPES = [
  { type: 'REQUIRED', kinds: ['TEXT', 'MULTILINE', 'DATE', 'NUMBER', 'CHOICE', 'CHECKBOX', 'IMAGE', 'SIGNATURE'], configKeys: [] },
  { type: 'REGEX', kinds: ['TEXT', 'MULTILINE', 'NUMBER', 'CHOICE'], configKeys: ['Pattern'] },
  { type: 'EMAIL', kinds: ['TEXT', 'MULTILINE'], configKeys: [] },
  { type: 'LENGTH', kinds: ['TEXT', 'MULTILINE', 'NUMBER', 'CHOICE'], configKeys: ['Min', 'Max'] },
  { type: 'RANGE', kinds: ['TEXT', 'NUMBER'], configKeys: ['Min', 'Max'] },
  { type: 'ALLOWED_VALUES', kinds: ['TEXT', 'MULTILINE', 'NUMBER', 'CHOICE', 'DATE'], configKeys: ['Values', 'IgnoreCase'] },
  { type: 'DATE_FORMAT', kinds: ['TEXT', 'DATE'], configKeys: ['Formats'] },
  { type: 'DATE_RANGE', kinds: ['TEXT', 'DATE'], configKeys: ['Min', 'Max'] },
  { type: 'EXPIRY_DATE', kinds: ['TEXT', 'DATE'], configKeys: [] },
  { type: 'REQUIRED_IF', kinds: ['TEXT', 'MULTILINE', 'DATE', 'NUMBER', 'CHOICE', 'CHECKBOX'], configKeys: ['Field', 'Equals'] },
  { type: 'COMPARE', kinds: ['TEXT', 'DATE', 'NUMBER', 'CHOICE'], configKeys: ['Field', 'Op'] },
];

export const OPERATORS = [
  ['<', 'before / less than'],
  ['<=', 'on or before / at most'],
  ['=', 'equal to'],
  ['!=', 'different from'],
  ['>=', 'on or after / at least'],
  ['>', 'after / greater than'],
];

/** Dates in DATE_RANGE: ISO, today, or relative ±N days/weeks/months/years. */
export const DATE_VALUE_RE = /^(today|[+-]\d{1,4}[dwmy]|\d{4}-\d{2}-\d{2})$/;

const has = (v) => v !== undefined && v !== null && v !== '';

function between(min, max, unit) {
  if (has(min) && has(max)) return `between ${min} and ${max}${unit}`;
  if (has(min)) return `at least ${min}${unit}`;
  if (has(max)) return `at most ${max}${unit}`;
  return 'any';
}

/** A short description of what a rule checks; `nameOf(key)` names another field. */
export function ruleSummary(rule, nameOf = (k) => k) {
  const c = rule.config || {};
  switch (rule.ruleType) {
    case 'REQUIRED':
      return 'Must be filled in';
    case 'REGEX':
      return `Matches ${c.Pattern || '(no pattern)'}`;
    case 'EMAIL':
      return 'An e-mail address';
    case 'LENGTH':
      return `Length ${between(c.Min, c.Max, ' characters')}`;
    case 'RANGE':
      return `A number ${between(c.Min, c.Max, '')}`;
    case 'ALLOWED_VALUES': {
      const values = c.Values || [];
      const shown = values.slice(0, 5).join(', ');
      return `One of: ${shown}${values.length > 5 ? ` … (${values.length})` : ''}${c.IgnoreCase ? ' (any case)' : ''}`;
    }
    case 'DATE_FORMAT':
      return `A date like ${(c.Formats && c.Formats.length ? c.Formats : ['yyyy-MM-dd']).join(' or ')}`;
    case 'DATE_RANGE': {
      const parts = [];
      if (has(c.Min)) parts.push(`on or after ${c.Min}`);
      if (has(c.Max)) parts.push(`on or before ${c.Max}`);
      return parts.length ? `A date ${parts.join(' and ')}` : 'Any date';
    }
    case 'EXPIRY_DATE':
      return 'A date after today';
    case 'REQUIRED_IF':
      return has(c.Equals)
        ? `Required when ${nameOf(c.Field)} is “${c.Equals}”`
        : `Required when ${nameOf(c.Field)} is filled`;
    case 'COMPARE': {
      const op = OPERATORS.find(([o]) => o === c.Op);
      return `${op ? op[1] : c.Op} ${nameOf(c.Field)}`.replace(/^./, (ch) => ch.toUpperCase());
    }
    default:
      return rule.ruleType;
  }
}

/** Where a rule came from, for its badge. */
export function originText(rule) {
  if (rule.origin === 'IMPORTED') {
    const template = rule.sourceTemplateName || (rule.sourceTemplateId != null ? `template ${rule.sourceTemplateId}` : 'a template');
    return rule.sourceFieldName ? `Imported from ${template}.${rule.sourceFieldName}` : `Imported from ${template}`;
  }
  if (rule.origin === 'REGION_FLAG') return 'From the Required flag';
  if (rule.origin === 'DATA_TEMPLATE') {
    const name = rule.dataTemplateName || rule.sourceTemplateName;
    return name ? `From the data template ${name}` : 'From the data template';
  }
  return 'Manual';
}
