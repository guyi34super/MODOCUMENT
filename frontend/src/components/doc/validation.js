// The builder's view of validation (field-validation contract §4): the
// server's result after Save, plus soft live hints while typing. The server
// result always wins; hints only cover what the browser can check cheaply.

import { PHOTO_KINDS, fieldLabel } from './fields';

export const NOT_VALIDATED_TEXT = 'Save and validate first';

const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

export const isBlank = (region, value, hasPhoto) => {
  if (PHOTO_KINDS.has(region.kind)) return !hasPhoto;
  if (region.kind === 'CHECKBOX') return !(value === 'true' || value === true || value === 'x');
  return value == null || String(value).trim() === '';
};

/** A JS RegExp for a server pattern, or null when the browser cannot compile it. */
function compile(pattern) {
  try {
    return new RegExp(pattern);
  } catch {
    return null;
  }
}

/**
 * The live hints for one field: required, max length, dropdown options and
 * the simple rules (REGEX, EMAIL, LENGTH) of `rules` for its key. Messages
 * never contain the value.
 */
export function liveHints(region, value, { hasPhoto = false, rules = [] } = {}) {
  const hints = [];
  const label = fieldLabel(region);
  const blank = isBlank(region, value, hasPhoto);
  const required = region.required || rules.some((r) => r.ruleType === 'REQUIRED');
  if (blank) {
    if (required) hints.push(`${label} is required.`);
    return hints;
  }
  if (PHOTO_KINDS.has(region.kind)) return hints;
  const text = String(value);
  const props = region.props || {};
  if (props.maxLength && text.length > props.maxLength) {
    hints.push(`${label} is longer than ${props.maxLength} characters.`);
  }
  if (region.kind === 'CHOICE' && Array.isArray(props.options) && !props.options.includes(text)) {
    hints.push(`Choose one of the options for ${label}.`);
  }
  for (const rule of rules) {
    const c = rule.config || {};
    if (rule.ruleType === 'REGEX' && c.Pattern) {
      const re = compile(c.Pattern);
      if (re && !re.test(text)) hints.push(rule.message || `${label} is not in the expected format.`);
    } else if (rule.ruleType === 'EMAIL' && !EMAIL_RE.test(text.trim())) {
      hints.push(rule.message || `${label} must be an e-mail address.`);
    } else if (rule.ruleType === 'LENGTH') {
      const length = [...text.trim()].length;
      if ((c.Min != null && length < Number(c.Min)) || (c.Max != null && length > Number(c.Max))) {
        hints.push(rule.message || `${label} has the wrong length.`);
      }
    }
  }
  return hints;
}

/** The server's errors and warnings by region key (empty until the document was validated). */
export function serverIssues(validation) {
  const byKey = new Map();
  if (!validation?.checked) return byKey;
  for (const [level, list] of [
    ['error', validation.errors || []],
    ['warning', validation.warnings || []],
  ]) {
    for (const item of list) {
      if (!byKey.has(item.regionKey)) byKey.set(item.regionKey, { errors: [], warnings: [] });
      byKey.get(item.regionKey)[level === 'error' ? 'errors' : 'warnings'].push(item.message);
    }
  }
  return byKey;
}

export const RULES_CHANGED_TEXT = 'The rules changed — save again';

/**
 * Whether the builder's Download step (and the Generate / Download PDF /
 * Submit shortcuts) is open (data-templates contract §5, §6): only for a
 * document validated with the current rules. `status` is the status as
 * shown (an edit already made it DRAFT). Submitted and released documents
 * stay downloadable; a generated document from before validation existed
 * (never checked) is allowed, as the server allows it.
 * Returns { locked, reason }.
 */
export function downloadGate({ status, validation }) {
  if (status === 'SUBMITTED' || status === 'RELEASED') return { locked: false, reason: '' };
  if (status !== 'VALIDATED' && status !== 'GENERATED') return { locked: true, reason: NOT_VALIDATED_TEXT };
  if (validation?.rulesChangedSinceValidation) return { locked: true, reason: RULES_CHANGED_TEXT };
  if (validation?.checked && (validation.errors || []).length > 0) return { locked: true, reason: NOT_VALIDATED_TEXT };
  if (status === 'VALIDATED' && validation != null && validation.checked === false) {
    return { locked: true, reason: NOT_VALIDATED_TEXT };
  }
  return { locked: false, reason: '' };
}
