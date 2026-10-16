// The backend's caps for template fields (field-validation contract §2 and
// the overlay checks), so the editor refuses what the server would refuse.

/** Field keys: lower-case letters, digits and _, 1–64 characters. */
export const KEY_RE = /^[a-z0-9_]{1,64}$/;

export const LIMITS = {
  label: 200,
  group: 120,
  key: 64,
  placeholder: 200,
  helpText: 500,
  defaultValue: 2000,
  /** Font size in pt; 0 means "fit automatically". */
  fontSize: { min: 0, max: 144 },
  /** Max lines of a MULTILINE field (other kinds are fixed). */
  maxLines: { min: 1, max: 500 },
  options: { count: 200, length: 200 },
  maxLength: { min: 1, max: 20000 },
  decimals: { min: 0, max: 6 },
  /** Number prefix / suffix. */
  affix: 5,
  /** A rule's own message. */
  ruleMessage: 300,
  /** Values of an ALLOWED_VALUES rule. */
  ruleValues: { count: 200, length: 200 },
  /** Formats of a DATE_FORMAT rule. */
  ruleFormats: 20,
  /** A REGEX rule's pattern. */
  pattern: 2000,
};

/** The date output patterns a DATE field may choose (FieldProps.DATE_FORMATS). */
export const DATE_FORMATS = [
  'yyyy-MM-dd',
  'dd/MM/yyyy',
  'MM/dd/yyyy',
  'dd-MM-yyyy',
  'dd.MM.yyyy',
  'd MMMM yyyy',
  'MMMM d, yyyy',
  'dd MMM yyyy',
];

/** Kinds whose values are text, so `maxLength` applies. */
export const TEXT_KINDS = new Set(['TEXT', 'MULTILINE', 'CHOICE']);

/** `value` as a number kept within [min, max] (NaN → min). */
export function clampNumber(value, { min, max }) {
  const n = Number(value);
  if (!Number.isFinite(n)) return min;
  return Math.min(max, Math.max(min, n));
}
