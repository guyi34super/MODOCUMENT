import { useId } from 'react';
import { Button, Field, inputClass } from '../ui';
import { ALIGNS, KEY_PATTERN, KIND_LABELS, LOW_CONFIDENCE, REGION_KINDS, slugify, uniqueKey } from './geometry';

const SINGLE_LINE = new Set(['TEXT', 'DATE', 'NUMBER']);
const NO_TEXT = new Set(['CHECKBOX', 'IMAGE', 'SIGNATURE']);

/** The maxLines a kind implies (contract §3): 1 for single-line, 0 for no-text kinds. */
export function maxLinesFor(kind, current) {
  if (SINGLE_LINE.has(kind)) return 1;
  if (NO_TEXT.has(kind)) return 0;
  return Math.max(2, current || 0);
}

/** True when `key` was derived from `label` (so renaming the label may re-derive it). */
function keyFollowsLabel(key, label) {
  if (!key) return true;
  const slug = slugify(label);
  return key === slug || new RegExp(`^${slug}_\\d+$`).test(key);
}

/** Problems with a key: format and uniqueness among `regions`. */
export function keyProblem(key, regions, self) {
  if (!KEY_PATTERN.test(key || '')) return 'Use 1–64 lower-case letters, digits or _';
  if (regions.some((r) => r !== self && r.key === key)) return 'Another field already uses this key';
  return '';
}

/**
 * The properties of one fill-in region: label (the key follows it until the
 * key is edited by hand, always kept unique), key, kind, group, font size,
 * alignment, max lines and required. Every change goes to `onChange(patch)`.
 */
export default function RegionPropertiesPanel({ region, regions, onChange, onDelete, readOnly = false, draft = false }) {
  const errorId = useId();
  const taken = new Set(regions.filter((r) => r !== region).map((r) => r.key));
  const problem = keyProblem(region.key, regions, region);
  const lowConfidence = region.confidence != null && region.confidence < LOW_CONFIDENCE;

  const setLabel = (label) => {
    const patch = { label };
    if (keyFollowsLabel(region.key, region.label)) patch.key = uniqueKey(label, taken);
    onChange(patch);
  };

  const setKind = (kind) => onChange({ kind, maxLines: maxLinesFor(kind, region.maxLines) });

  return (
    <div className="space-y-3" data-testid="region-properties">
      {draft && region.sampleValue && (
        <p className={`rounded px-2 py-1 text-xs ${lowConfidence ? 'bg-amber-100 text-amber-900' : 'bg-pill text-ink'}`}>
          Removed value: “{region.sampleValue}”
          {lowConfidence && ' — low confidence, please check'}
        </p>
      )}
      <Field label="Label">
        <input
          className={inputClass}
          value={region.label || ''}
          maxLength={200}
          readOnly={readOnly}
          onChange={(e) => setLabel(e.target.value)}
        />
      </Field>
      <Field label="Key" hint="Lower-case letters, digits and _; photo auto-fill matches it">
        <input
          className={`${inputClass} font-mono`}
          value={region.key || ''}
          maxLength={64}
          readOnly={readOnly}
          aria-invalid={problem ? true : undefined}
          aria-describedby={problem ? errorId : undefined}
          onChange={(e) => onChange({ key: e.target.value.toLowerCase() })}
        />
      </Field>
      {problem && (
        <p id={errorId} className="-mt-2 text-xs text-danger-ink">
          {problem}
        </p>
      )}
      <div className="grid grid-cols-2 gap-3">
        <Field label="Kind">
          <select
            className={inputClass}
            value={region.kind}
            disabled={readOnly}
            onChange={(e) => setKind(e.target.value)}
          >
            {REGION_KINDS.map((kind) => (
              <option key={kind} value={kind}>
                {KIND_LABELS[kind]}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Group">
          <input
            className={inputClass}
            value={region.group || ''}
            maxLength={120}
            readOnly={readOnly}
            placeholder="e.g. Applicant"
            onChange={(e) => onChange({ group: e.target.value || null })}
          />
        </Field>
        <Field label="Font size (pt)" hint="0 = fit automatically">
          <input
            type="number"
            min={0}
            max={72}
            step={0.5}
            className={inputClass}
            value={region.fontSize ?? 0}
            readOnly={readOnly}
            onChange={(e) => onChange({ fontSize: Math.max(0, Number(e.target.value) || 0) })}
          />
        </Field>
        <Field label="Align">
          <select
            className={inputClass}
            value={region.align || 'LEFT'}
            disabled={readOnly}
            onChange={(e) => onChange({ align: e.target.value })}
          >
            {ALIGNS.map((align) => (
              <option key={align} value={align}>
                {align.charAt(0) + align.slice(1).toLowerCase()}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Max lines">
          <input
            type="number"
            min={region.kind === 'MULTILINE' ? 1 : 0}
            max={200}
            className={inputClass}
            value={region.maxLines ?? 1}
            readOnly={readOnly || region.kind !== 'MULTILINE'}
            onChange={(e) => onChange({ maxLines: Math.max(1, Math.floor(Number(e.target.value) || 1)) })}
          />
        </Field>
        <label className="flex items-center gap-2 self-end pb-2 text-sm">
          <input
            type="checkbox"
            checked={Boolean(region.required)}
            disabled={readOnly}
            onChange={(e) => onChange({ required: e.target.checked })}
          />
          Required
        </label>
      </div>
      {!readOnly && onDelete && (
        <Button variant="danger" size="sm" onClick={onDelete}>
          Delete field
        </Button>
      )}
    </div>
  );
}
