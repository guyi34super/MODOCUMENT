import { useEffect, useId, useState } from 'react';
import Modal from '../Modal';
import RegexBuilder from '../RegexBuilder';
import { Button, Field, inputClass } from '../ui';
import { DATE_FORMATS, LIMITS } from '../pdf/limits';
import { DATE_VALUE_RE, OPERATORS, RULE_LABELS } from './ruleText';

const RELATIVE_HELPERS = ['today', '+30d', '-18y'];

const num = (v) => (v === '' || v == null ? null : Number(v));

/** The problem with a rule's config, or ''. */
export function configProblem(type, c) {
  switch (type) {
    case 'REGEX':
      if (!c.Pattern) return 'Enter or build a pattern.';
      if (c.Pattern.length > LIMITS.pattern) return `The pattern is longer than ${LIMITS.pattern} characters.`;
      return '';
    case 'LENGTH':
    case 'RANGE': {
      const min = num(c.Min);
      const max = num(c.Max);
      if (min == null && max == null) return 'Enter a minimum, a maximum or both.';
      if ((min != null && !Number.isFinite(min)) || (max != null && !Number.isFinite(max))) return 'Use numbers.';
      if (min != null && max != null && min > max) return 'The minimum is greater than the maximum.';
      return '';
    }
    case 'ALLOWED_VALUES':
      if (!c.Values?.length) return 'List at least one value.';
      if (c.Values.length > LIMITS.ruleValues.count) return `At most ${LIMITS.ruleValues.count} values.`;
      if (c.Values.some((v) => v.length > LIMITS.ruleValues.length)) return `Each value is at most ${LIMITS.ruleValues.length} characters.`;
      return '';
    case 'DATE_RANGE':
      if (!c.Min && !c.Max) return 'Enter the earliest date, the latest or both.';
      if ((c.Min && !DATE_VALUE_RE.test(c.Min)) || (c.Max && !DATE_VALUE_RE.test(c.Max))) {
        return 'Use a date (yyyy-mm-dd), today, or a relative value like +30d or -18y.';
      }
      return '';
    case 'REQUIRED_IF':
      return c.Field ? '' : 'Choose the other field.';
    case 'COMPARE':
      if (!c.Field) return 'Choose the other field.';
      return c.Op ? '' : 'Choose how to compare.';
    default:
      return '';
  }
}

/** The config as sent: numbers as numbers, empty entries dropped. */
export function cleanConfig(type, c) {
  const out = {};
  for (const key of Object.keys(c)) {
    const v = c[key];
    if (v === '' || v == null || (Array.isArray(v) && v.length === 0)) continue;
    out[key] = v;
  }
  if (type === 'LENGTH' || type === 'RANGE') {
    if (out.Min != null) out.Min = Number(out.Min);
    if (out.Max != null) out.Max = Number(out.Max);
  }
  if (type === 'ALLOWED_VALUES' && !out.IgnoreCase) delete out.IgnoreCase;
  if (type === 'REQUIRED_IF' && out.Equals != null) out.Equals = String(out.Equals);
  return out;
}

/**
 * Add or edit one rule of `field` ({ key, label, kind }). The rule types on
 * offer are those whose `kinds` include the field's kind (from the server's
 * `ruleTypes`); each type gets its own inputs. `onSave(ruleInput)`.
 *
 * Generic over what owns the rules: `fields` are `{ key, label, kind }`
 * (a PDF template's regions, or a data template's fields with `key` = the
 * field name) and `keyName` names the key in the saved input
 * ('regionKey' for a PDF template, 'fieldName' for a data template).
 */
export default function RuleDialog({ open, field, rule, ruleTypes, fields, onSave, onCancel, keyName = 'regionKey' }) {
  const ids = useId();
  const fitting = ruleTypes.filter((t) => !t.kinds?.length || t.kinds.includes(field?.kind));
  const [type, setType] = useState('');
  const [config, setConfig] = useState({});
  const [message, setMessage] = useState('');
  const [severity, setSeverity] = useState('ERROR');
  const [valuesText, setValuesText] = useState('');
  const [tried, setTried] = useState(false);

  useEffect(() => {
    if (!open) return;
    const startType = rule?.ruleType || fitting[0]?.type || 'REQUIRED';
    setType(startType);
    setConfig({ ...(rule?.config || {}) });
    setValuesText((rule?.config?.Values || []).join('\n'));
    setMessage(rule?.message || '');
    setSeverity(rule?.severity || 'ERROR');
    setTried(false);
    // Reset only when the dialog opens.
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!field) return null;
  const options = fitting.some((t) => t.type === type) || !type ? fitting : [...fitting, { type, kinds: [] }];
  const set = (key, value) => setConfig((c) => ({ ...c, [key]: value }));
  const effective = type === 'ALLOWED_VALUES' ? { ...config, Values: valuesText.split('\n').map((v) => v.trim()).filter(Boolean) } : config;
  const problem = configProblem(type, effective);
  const others = (fields || []).filter((f) => f.key !== field.key);

  const changeType = (next) => {
    setType(next);
    setConfig({});
    setValuesText('');
    setTried(false);
  };

  const submit = (event) => {
    event.preventDefault();
    setTried(true);
    if (problem) return;
    onSave({
      [keyName]: field.key,
      ruleType: type,
      config: cleanConfig(type, effective),
      message: message.trim() || null,
      severity,
    });
  };

  const minMax = (label, unit) => (
    <div className="grid grid-cols-2 gap-3">
      <Field label={`Minimum ${label}`}>
        <input type="number" className={inputClass} value={config.Min ?? ''} onChange={(e) => set('Min', e.target.value)} />
      </Field>
      <Field label={`Maximum ${label}`}>
        <input type="number" className={inputClass} value={config.Max ?? ''} onChange={(e) => set('Max', e.target.value)} />
      </Field>
      {unit && <p className="col-span-2 -mt-2 text-xs text-muted">{unit}</p>}
    </div>
  );

  const dateInput = (key, label) => (
    <div>
      <Field label={label} hint="yyyy-mm-dd, today, or relative: +30d, -18y, +2m, +1w">
        <input className={inputClass} value={config[key] ?? ''} onChange={(e) => set(key, e.target.value.trim())} />
      </Field>
      <div className="mt-1 flex gap-1">
        {RELATIVE_HELPERS.map((value) => (
          <button
            key={value}
            type="button"
            className="rounded border border-line px-1.5 text-xs text-accent hover:bg-pill/50"
            aria-label={`${label}: ${value}`}
            onClick={() => set(key, value)}
          >
            {value}
          </button>
        ))}
      </div>
    </div>
  );

  return (
    <Modal
      open={open}
      onClose={onCancel}
      size="lg"
      scrollBody
      title={`${rule ? 'Edit' : 'Add'} a rule — ${field.label || field.key}`}
      footer={
        <>
          <Button variant="secondary" onClick={onCancel}>
            Cancel
          </Button>
          <Button type="submit" form={`${ids}-form`}>
            {rule ? 'Update rule' : 'Add rule'}
          </Button>
        </>
      }
    >
      <form id={`${ids}-form`} onSubmit={submit} className="space-y-3" noValidate>
        <Field label="Rule type">
          <select className={inputClass} value={type} onChange={(e) => changeType(e.target.value)}>
            {options.map((t) => (
              <option key={t.type} value={t.type}>
                {RULE_LABELS[t.type] || t.type}
                {fitting.some((f) => f.type === t.type) ? '' : ' (does not apply to this kind)'}
              </option>
            ))}
          </select>
        </Field>

        {type === 'REGEX' && <RegexBuilder pattern={config.Pattern || ''} onChange={(pattern) => set('Pattern', pattern)} />}
        {type === 'LENGTH' && minMax('length', 'Characters, counted after trimming.')}
        {type === 'RANGE' && minMax('value', null)}
        {type === 'ALLOWED_VALUES' && (
          <>
            <Field label="Allowed values" hint="One per line">
              <textarea className={inputClass} rows={4} value={valuesText} onChange={(e) => setValuesText(e.target.value)} />
            </Field>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={Boolean(config.IgnoreCase)} onChange={(e) => set('IgnoreCase', e.target.checked)} />
              Ignore upper / lower case
            </label>
          </>
        )}
        {type === 'DATE_FORMAT' && (
          <fieldset>
            <legend className="mb-1 text-sm font-medium">Accepted formats</legend>
            <p className="mb-1 text-xs text-muted">None ticked = yyyy-MM-dd.</p>
            <div className="grid grid-cols-2 gap-1">
              {DATE_FORMATS.map((format) => {
                const formats = config.Formats || [];
                return (
                  <label key={format} className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={formats.includes(format)}
                      onChange={(e) =>
                        set('Formats', e.target.checked ? [...formats, format] : formats.filter((f) => f !== format))
                      }
                    />
                    {format}
                  </label>
                );
              })}
            </div>
          </fieldset>
        )}
        {type === 'DATE_RANGE' && (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {dateInput('Min', 'Earliest date')}
            {dateInput('Max', 'Latest date')}
          </div>
        )}
        {(type === 'REQUIRED_IF' || type === 'COMPARE') && (
          <div className="grid grid-cols-2 gap-3">
            <Field label="Other field">
              <select className={inputClass} value={config.Field || ''} onChange={(e) => set('Field', e.target.value)}>
                <option value="">Choose a field…</option>
                {others.map((f) => (
                  <option key={f.key} value={f.key}>
                    {f.label || f.key}
                  </option>
                ))}
              </select>
            </Field>
            {type === 'REQUIRED_IF' ? (
              <Field label="Only when it equals" hint="Optional; empty = whenever it is filled">
                <input className={inputClass} value={config.Equals ?? ''} onChange={(e) => set('Equals', e.target.value)} />
              </Field>
            ) : (
              <Field label="This value must be">
                <select className={inputClass} value={config.Op || ''} onChange={(e) => set('Op', e.target.value)}>
                  <option value="">Choose…</option>
                  {OPERATORS.map(([op, text]) => (
                    <option key={op} value={op}>
                      {op} ({text})
                    </option>
                  ))}
                </select>
              </Field>
            )}
          </div>
        )}

        <Field label="Message" hint="Optional; empty = the default message. Never include the value.">
          <input
            className={inputClass}
            value={message}
            maxLength={LIMITS.ruleMessage}
            onChange={(e) => setMessage(e.target.value)}
          />
        </Field>
        <fieldset>
          <legend className="mb-1 text-sm font-medium">Severity</legend>
          <div className="flex gap-4 text-sm">
            <label className="flex items-center gap-1.5">
              <input type="radio" name={`${ids}-severity`} checked={severity === 'ERROR'} onChange={() => setSeverity('ERROR')} />
              Error (blocks validation)
            </label>
            <label className="flex items-center gap-1.5">
              <input type="radio" name={`${ids}-severity`} checked={severity === 'WARNING'} onChange={() => setSeverity('WARNING')} />
              Warning
            </label>
          </div>
        </fieldset>
        {tried && problem && (
          <p role="alert" className="text-sm text-danger-ink">
            {problem}
          </p>
        )}
      </form>
    </Modal>
  );
}
