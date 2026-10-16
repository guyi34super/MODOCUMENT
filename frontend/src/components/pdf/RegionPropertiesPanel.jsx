import { useId } from 'react';
import { Button, Field, Tag, inputClass } from '../ui';
import { ALIGNS, KEY_PATTERN, KIND_LABELS, LOW_CONFIDENCE, REGION_KINDS, slugify, uniqueKey } from './geometry';
import { DATE_FORMATS, LIMITS, TEXT_KINDS, clampNumber } from './limits';

const SINGLE_LINE = new Set(['TEXT', 'DATE', 'NUMBER', 'CHOICE']);
const NO_TEXT = new Set(['CHECKBOX', 'IMAGE', 'SIGNATURE']);
const PHOTO = new Set(['IMAGE', 'SIGNATURE']);

/** The maxLines a kind implies (contract §3): 1 for single-line, 0 for no-text kinds. */
export function maxLinesFor(kind, current) {
  if (SINGLE_LINE.has(kind)) return 1;
  if (NO_TEXT.has(kind)) return 0;
  return Math.min(LIMITS.maxLines.max, Math.max(2, current || 0));
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

/** Problems with a region's options (the server refuses a CHOICE without them). */
export function optionsProblem(region) {
  if (region.kind !== 'CHOICE') return '';
  const options = region.props?.options || [];
  if (options.length === 0) return 'A dropdown needs at least one option';
  if (options.some((o) => !String(o).trim())) return 'Fill in or remove the empty option';
  const seen = new Set();
  for (const o of options) {
    const k = String(o).trim();
    if (seen.has(k)) return `“${k}” is listed twice`;
    seen.add(k);
  }
  return '';
}

/** Any problem that blocks saving this field (key or options). */
export function fieldProblem(region, regions) {
  return keyProblem(region.key, regions, region) || optionsProblem(region);
}

/** Field properties without the empty ones; null when nothing is left (contract §2). */
export function cleanProps(props) {
  if (!props) return null;
  const out = {};
  for (const [name, value] of Object.entries(props)) {
    if (value == null || value === '') continue;
    if (Array.isArray(value) && value.length === 0) continue;
    if (name === 'numberFormat') {
      const nf = Object.fromEntries(
        Object.entries(value).filter(([, v]) => v != null && v !== '' && v !== false)
      );
      if (Object.keys(nf).length) out.numberFormat = nf;
      continue;
    }
    out[name] = value;
  }
  return Object.keys(out).length ? out : null;
}

function Section({ title, children }) {
  const id = useId();
  return (
    <div role="group" aria-labelledby={id} className="space-y-3 border-t border-line pt-3 first:border-t-0 first:pt-0">
      <p id={id} className="text-xs font-semibold uppercase tracking-wide text-muted">
        {title}
      </p>
      {children}
    </div>
  );
}

/** The CHOICE options: add, remove, reorder, with a duplicate check. */
function OptionsEditor({ options, onChange, readOnly }) {
  const problemId = useId();
  const set = (list) => onChange(list.slice(0, LIMITS.options.count));
  const counts = new Map();
  for (const o of options) counts.set(o.trim(), (counts.get(o.trim()) || 0) + 1);
  return (
    <fieldset className="space-y-1">
      <legend className="mb-1 text-sm font-medium text-ink">Options</legend>
      {options.length === 0 && <p className="text-xs text-muted">No options yet.</p>}
      <ol className="space-y-1">
        {options.map((option, i) => {
          const duplicate = option.trim() && counts.get(option.trim()) > 1;
          return (
            <li key={i} className="flex items-center gap-1">
              <input
                className={`${inputClass} !py-1`}
                aria-label={`Option ${i + 1}`}
                value={option}
                maxLength={LIMITS.options.length}
                readOnly={readOnly}
                aria-invalid={duplicate || !option.trim() ? true : undefined}
                aria-describedby={duplicate ? problemId : undefined}
                onChange={(e) => set(options.map((o, j) => (j === i ? e.target.value : o)))}
              />
              {!readOnly && (
                <>
                  <button
                    type="button"
                    aria-label={`Move option ${i + 1} up`}
                    disabled={i === 0}
                    onClick={() => {
                      const next = [...options];
                      [next[i - 1], next[i]] = [next[i], next[i - 1]];
                      set(next);
                    }}
                    className="rounded px-1 text-xs text-accent disabled:opacity-30"
                  >
                    <span aria-hidden="true">↑</span>
                  </button>
                  <button
                    type="button"
                    aria-label={`Move option ${i + 1} down`}
                    disabled={i === options.length - 1}
                    onClick={() => {
                      const next = [...options];
                      [next[i + 1], next[i]] = [next[i], next[i + 1]];
                      set(next);
                    }}
                    className="rounded px-1 text-xs text-accent disabled:opacity-30"
                  >
                    <span aria-hidden="true">↓</span>
                  </button>
                  <button
                    type="button"
                    aria-label={`Remove option ${i + 1}`}
                    onClick={() => set(options.filter((_, j) => j !== i))}
                    className="rounded px-1 text-xs text-danger-ink"
                  >
                    <span aria-hidden="true">✕</span>
                  </button>
                </>
              )}
            </li>
          );
        })}
      </ol>
      {[...counts.entries()].some(([k, n]) => k && n > 1) && (
        <p id={problemId} className="text-xs text-danger-ink">
          Each option may appear only once.
        </p>
      )}
      {!readOnly && (
        <Button
          size="sm"
          variant="secondary"
          onClick={() => set([...options, ''])}
          disabled={options.length >= LIMITS.options.count}
        >
          Add option
        </Button>
      )}
    </fieldset>
  );
}

function DefaultValueInput({ region, value, onChange, readOnly }) {
  const common = { className: inputClass, value: value ?? '', readOnly, disabled: readOnly && region.kind === 'CHOICE' };
  const label = 'Default value';
  if (region.kind === 'CHOICE') {
    return (
      <Field label={label}>
        <select {...common} disabled={readOnly} onChange={(e) => onChange(e.target.value)}>
          <option value="">No default</option>
          {(region.props?.options || [])
            .filter((o) => o.trim())
            .map((o) => (
              <option key={o} value={o}>
                {o}
              </option>
            ))}
        </select>
      </Field>
    );
  }
  if (region.kind === 'CHECKBOX') {
    return (
      <Field label={label}>
        <select {...common} disabled={readOnly} onChange={(e) => onChange(e.target.value)}>
          <option value="">Not ticked</option>
          <option value="true">Ticked</option>
        </select>
      </Field>
    );
  }
  if (region.kind === 'MULTILINE') {
    return (
      <Field label={label}>
        <textarea {...common} rows={2} maxLength={LIMITS.defaultValue} onChange={(e) => onChange(e.target.value)} />
      </Field>
    );
  }
  const type = region.kind === 'DATE' ? 'date' : region.kind === 'NUMBER' ? 'number' : 'text';
  return (
    <Field label={label}>
      <input {...common} type={type} maxLength={LIMITS.defaultValue} onChange={(e) => onChange(e.target.value)} />
    </Field>
  );
}

/** The value all `items` share for `name`, or '' when they differ. */
function shared(items, name, fallback = '') {
  const first = items[0]?.[name] ?? fallback;
  return items.every((r) => (r[name] ?? fallback) === first) ? first : '';
}

function fontChoices(fonts, current) {
  const list = [...(fonts || [])];
  for (const name of current) if (name && !list.includes(name)) list.push(name);
  return list;
}

/** Kind, group, font, size, colour and alignment of several fields at once. */
function MultiPanel({ items, onChangeMany, fonts, groups, readOnly }) {
  const groupsId = useId();
  const kind = shared(items, 'kind');
  const fontName = shared(items, 'fontName');
  const fontSize = shared(items, 'fontSize', 0);
  const color = shared(items, 'color', '#000000');
  const align = shared(items, 'align', 'LEFT');
  const group = shared(items, 'group');
  return (
    <div className="space-y-3" data-testid="region-properties">
      <p className="text-sm font-medium" role="status">
        {items.length} fields selected
      </p>
      <p className="text-xs text-muted">Changes here apply to every selected field.</p>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Kind">
          <select
            className={inputClass}
            value={kind}
            disabled={readOnly}
            onChange={(e) =>
              e.target.value && onChangeMany((r) => ({ kind: e.target.value, maxLines: maxLinesFor(e.target.value, r.maxLines) }))
            }
          >
            {!kind && <option value="">Mixed</option>}
            {REGION_KINDS.map((k) => (
              <option key={k} value={k}>
                {KIND_LABELS[k]}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Group">
          <input
            className={inputClass}
            value={group || ''}
            list={groupsId}
            placeholder={group === '' && items.some((r) => r.group) ? 'Mixed' : 'e.g. Applicant'}
            maxLength={LIMITS.group}
            readOnly={readOnly}
            onChange={(e) => onChangeMany(() => ({ group: e.target.value || null }))}
          />
        </Field>
        <datalist id={groupsId}>
          {groups.map((g) => (
            <option key={g} value={g} />
          ))}
        </datalist>
        <Field label="Font">
          <select
            className={inputClass}
            value={fontName}
            disabled={readOnly}
            onChange={(e) => e.target.value && onChangeMany(() => ({ fontName: e.target.value }))}
          >
            {!fontName && <option value="">Mixed</option>}
            {fontChoices(fonts, items.map((r) => r.fontName)).map((f) => (
              <option key={f} value={f}>
                {f}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Font size (pt)">
          <input
            type="number"
            min={LIMITS.fontSize.min}
            max={LIMITS.fontSize.max}
            step={0.5}
            className={inputClass}
            value={fontSize}
            placeholder="Mixed"
            readOnly={readOnly}
            onChange={(e) => e.target.value !== '' && onChangeMany(() => ({ fontSize: clampNumber(e.target.value, LIMITS.fontSize) }))}
          />
        </Field>
        <Field label="Colour">
          <input
            type="color"
            className="h-8 w-full cursor-pointer rounded border border-line"
            value={color || '#000000'}
            disabled={readOnly}
            onChange={(e) => onChangeMany(() => ({ color: e.target.value }))}
          />
        </Field>
        <Field label="Align">
          <select
            className={inputClass}
            value={align}
            disabled={readOnly}
            onChange={(e) => e.target.value && onChangeMany(() => ({ align: e.target.value }))}
          >
            {!align && <option value="">Mixed</option>}
            {ALIGNS.map((a) => (
              <option key={a} value={a}>
                {a.charAt(0) + a.slice(1).toLowerCase()}
              </option>
            ))}
          </select>
        </Field>
      </div>
    </div>
  );
}

/**
 * The properties of a fill-in region, in sections:
 *
 * - **Basics:** label (the key follows it until the key is edited by hand,
 *   always kept unique), key, kind (incl. Dropdown = CHOICE), group;
 * - **Appearance:** font, size (0 = auto, ≤144), colour, alignment, max lines;
 * - **Input:** placeholder, help text, default value, the options of a
 *   dropdown, date / number format and max length (`region.props`);
 * - **Rules:** the rule count, "Edit rules" and a quick Required toggle
 *   (`rules`: { count, required, onToggleRequired(on), onEditRules(), note,
 *   busy }); without `rules` the toggle sets `region.required`.
 *
 * Every change goes to `onChange(patch)`. With several fields selected
 * (`selection`, 2+), only the shared properties are offered and each change
 * goes to `onChangeMany(patchFor)`.
 */
export default function RegionPropertiesPanel({
  region,
  regions,
  onChange,
  onDelete,
  readOnly = false,
  draft = false,
  fonts = [],
  rules = null,
  selection = null,
  onChangeMany,
}) {
  const errorId = useId();
  const groupsId = useId();
  const groups = [...new Set(regions.map((r) => r.group?.trim()).filter(Boolean))].sort((a, b) => a.localeCompare(b));

  if (selection && selection.length > 1) {
    return <MultiPanel items={selection} onChangeMany={onChangeMany} fonts={fonts} groups={groups} readOnly={readOnly} />;
  }

  const taken = new Set(regions.filter((r) => r !== region).map((r) => r.key));
  const problem = keyProblem(region.key, regions, region);
  const choiceProblem = optionsProblem(region);
  const lowConfidence = region.confidence != null && region.confidence < LOW_CONFIDENCE;
  const props = region.props || {};
  const photo = PHOTO.has(region.kind);

  const setLabel = (label) => {
    const patch = { label };
    if (keyFollowsLabel(region.key, region.label)) patch.key = uniqueKey(label, taken);
    onChange(patch);
  };

  const setKind = (kind) => {
    const patch = { kind, maxLines: maxLinesFor(kind, region.maxLines) };
    if (kind === 'CHOICE' && !props.options) patch.props = { ...props, options: [] };
    onChange(patch);
  };

  const setProp = (name, value) => {
    const next = { ...props, [name]: value };
    // A dropdown keeps its (possibly empty) option list while it is being edited.
    const cleaned = cleanProps(next);
    onChange({ props: name === 'options' ? { ...(cleaned || {}), options: value } : cleaned });
  };
  const numberFormat = props.numberFormat || {};
  const setNumberFormat = (name, value) => setProp('numberFormat', { ...numberFormat, [name]: value });

  const fontList = fontChoices(fonts, [region.fontName || 'Helvetica']);
  const requiredOn = rules ? Boolean(rules.required) : Boolean(region.required);

  return (
    <div className="space-y-3" data-testid="region-properties">
      {draft && region.sampleValue && (
        <p className={`rounded px-2 py-1 text-xs ${lowConfidence ? 'bg-amber-100 text-amber-900' : 'bg-pill text-ink'}`}>
          Removed value: “{region.sampleValue}”
          {lowConfidence && ' — low confidence, please check'}
        </p>
      )}

      <Section title="Basics">
        <Field label="Label">
          <input
            className={inputClass}
            value={region.label || ''}
            maxLength={LIMITS.label}
            readOnly={readOnly}
            onChange={(e) => setLabel(e.target.value)}
          />
        </Field>
        <Field label="Key" hint="Lower-case letters, digits and _; photo auto-fill matches it">
          <input
            className={`${inputClass} font-mono`}
            value={region.key || ''}
            maxLength={LIMITS.key}
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
            <select className={inputClass} value={region.kind} disabled={readOnly} onChange={(e) => setKind(e.target.value)}>
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
              maxLength={LIMITS.group}
              readOnly={readOnly}
              list={groupsId}
              placeholder="e.g. Applicant"
              onChange={(e) => onChange({ group: e.target.value || null })}
            />
          </Field>
          <datalist id={groupsId}>
            {groups.map((g) => (
              <option key={g} value={g} />
            ))}
          </datalist>
        </div>
      </Section>

      {!photo && (
        <Section title="Appearance">
          <div className="grid grid-cols-2 gap-3">
            <Field label="Font">
              <select
                className={inputClass}
                value={region.fontName || 'Helvetica'}
                disabled={readOnly}
                onChange={(e) => onChange({ fontName: e.target.value })}
              >
                {fontList.map((f) => (
                  <option key={f} value={f}>
                    {f}
                    {fonts.length > 0 && !fonts.includes(f) ? ' (detected)' : ''}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Font size (pt)" hint="0 = fit automatically">
              <input
                type="number"
                min={LIMITS.fontSize.min}
                max={LIMITS.fontSize.max}
                step={0.5}
                className={inputClass}
                value={region.fontSize ?? 0}
                readOnly={readOnly}
                onChange={(e) => onChange({ fontSize: clampNumber(e.target.value || 0, LIMITS.fontSize) })}
              />
            </Field>
            <Field label="Colour">
              <input
                type="color"
                className="h-8 w-full cursor-pointer rounded border border-line"
                value={/^#[0-9a-fA-F]{6}$/.test(region.color || '') ? region.color : '#000000'}
                disabled={readOnly}
                onChange={(e) => onChange({ color: e.target.value })}
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
                min={region.kind === 'MULTILINE' ? LIMITS.maxLines.min : 0}
                max={LIMITS.maxLines.max}
                className={inputClass}
                value={region.maxLines ?? 1}
                readOnly={readOnly || region.kind !== 'MULTILINE'}
                onChange={(e) => onChange({ maxLines: Math.floor(clampNumber(e.target.value || 1, LIMITS.maxLines)) })}
              />
            </Field>
          </div>
        </Section>
      )}

      <Section title="Input">
        {!photo && region.kind !== 'CHECKBOX' && (
          <Field label="Placeholder" hint={region.kind === 'CHOICE' ? 'Shown as the empty choice' : undefined}>
            <input
              className={inputClass}
              value={props.placeholder || ''}
              maxLength={LIMITS.placeholder}
              readOnly={readOnly}
              onChange={(e) => setProp('placeholder', e.target.value)}
            />
          </Field>
        )}
        <Field label="Help text" hint="Shown under the input">
          <textarea
            className={inputClass}
            rows={2}
            value={props.helpText || ''}
            maxLength={LIMITS.helpText}
            readOnly={readOnly}
            onChange={(e) => setProp('helpText', e.target.value)}
          />
        </Field>
        {region.kind === 'CHOICE' && (
          <>
            <OptionsEditor options={props.options || []} readOnly={readOnly} onChange={(list) => setProp('options', list)} />
            {choiceProblem && (
              <p className="text-xs text-danger-ink" role="alert">
                {choiceProblem}
              </p>
            )}
          </>
        )}
        {!photo && (
          <DefaultValueInput region={region} value={props.defaultValue} readOnly={readOnly} onChange={(v) => setProp('defaultValue', v)} />
        )}
        {region.kind === 'DATE' && (
          <Field label="Date format in the PDF" hint="The value is stored as a date and drawn in this format">
            <select
              className={inputClass}
              value={props.dateFormat || ''}
              disabled={readOnly}
              onChange={(e) => setProp('dateFormat', e.target.value)}
            >
              <option value="">Default (yyyy-MM-dd)</option>
              {DATE_FORMATS.map((f) => (
                <option key={f} value={f}>
                  {f}
                </option>
              ))}
            </select>
          </Field>
        )}
        {region.kind === 'NUMBER' && (
          <fieldset className="grid grid-cols-2 gap-3">
            <legend className="mb-1 text-sm font-medium text-ink">Number format in the PDF</legend>
            <Field label="Decimals" hint="Empty = as typed">
              <input
                type="number"
                min={LIMITS.decimals.min}
                max={LIMITS.decimals.max}
                className={inputClass}
                value={numberFormat.decimals ?? ''}
                readOnly={readOnly}
                onChange={(e) =>
                  setNumberFormat('decimals', e.target.value === '' ? null : Math.floor(clampNumber(e.target.value, LIMITS.decimals)))
                }
              />
            </Field>
            <label className="flex items-center gap-2 self-end pb-2 text-sm">
              <input
                type="checkbox"
                checked={Boolean(numberFormat.thousands)}
                disabled={readOnly}
                onChange={(e) => setNumberFormat('thousands', e.target.checked)}
              />
              Thousands separator
            </label>
            <Field label="Prefix">
              <input
                className={inputClass}
                value={numberFormat.prefix || ''}
                maxLength={LIMITS.affix}
                readOnly={readOnly}
                onChange={(e) => setNumberFormat('prefix', e.target.value)}
              />
            </Field>
            <Field label="Suffix">
              <input
                className={inputClass}
                value={numberFormat.suffix || ''}
                maxLength={LIMITS.affix}
                readOnly={readOnly}
                onChange={(e) => setNumberFormat('suffix', e.target.value)}
              />
            </Field>
          </fieldset>
        )}
        {TEXT_KINDS.has(region.kind) && (
          <Field label="Max length" hint="Characters; empty = no limit">
            <input
              type="number"
              min={LIMITS.maxLength.min}
              max={LIMITS.maxLength.max}
              className={inputClass}
              value={props.maxLength ?? ''}
              readOnly={readOnly}
              onChange={(e) =>
                setProp('maxLength', e.target.value === '' ? null : Math.floor(clampNumber(e.target.value, LIMITS.maxLength)))
              }
            />
          </Field>
        )}
      </Section>

      <Section title="Rules">
        {rules && (
          <p className="flex flex-wrap items-center gap-2 text-xs">
            <Tag tone={rules.count ? 'info' : 'neutral'}>
              {rules.count || 'No'} rule{rules.count === 1 ? '' : 's'}
            </Tag>
            {rules.onEditRules && (
              <button type="button" className="text-accent hover:underline" onClick={rules.onEditRules}>
                Edit rules
              </button>
            )}
          </p>
        )}
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={requiredOn}
            disabled={readOnly || Boolean(rules?.busy)}
            onChange={(e) => (rules?.onToggleRequired ? rules.onToggleRequired(e.target.checked) : onChange({ required: e.target.checked }))}
          />
          Required
        </label>
        {rules?.note && <p className="text-xs text-muted">{rules.note}</p>}
      </Section>

      {!readOnly && onDelete && (
        <Button variant="danger" size="sm" onClick={onDelete}>
          Delete field
        </Button>
      )}
    </div>
  );
}
