import { useMemo, useState } from 'react';
import { Field, inputClass } from './ui';

import { EMAIL_PATTERN } from '../api/validationRules';

/** Escapes a literal so it matches itself inside a regular expression. */
export function escapeLiteral(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

const PART_CLASSES = {
  letters: '[A-Za-z]',
  digits: '\\d',
  lettersDigits: '[A-Za-z0-9]',
  any: '.',
};

function quantifier(part) {
  if (part.countMode === 'exact') return `{${part.n || 1}}`;
  if (part.countMode === 'range') return `{${part.n || 0},${part.m || part.n || 1}}`;
  return '+';
}

/** Composes an anchored pattern from ordered builder parts. Exported for tests. */
export function buildPattern(parts) {
  const body = parts
    .map((part) => {
      if (part.type === 'text') {
        return part.value ? escapeLiteral(part.value) : '';
      }
      if (part.type === 'oneOf') {
        const options = (part.value || '')
          .split(',')
          .map((v) => v.trim())
          .filter(Boolean)
          .map(escapeLiteral);
        return options.length ? `(${options.join('|')})` : '';
      }
      return PART_CLASSES[part.type] + quantifier(part);
    })
    .join('');
  return body ? `^${body}$` : '';
}

// Generic, field-agnostic presets — the custom builder covers anything specific.
export const PRESETS = [
  {
    key: 'email',
    label: 'Email address',
    pattern: EMAIL_PATTERN,
    example: 'jane@acme.com',
  },
  {
    key: 'phone',
    label: 'Phone number',
    pattern: '^\\+?[0-9][0-9 ()-]{5,18}[0-9]$',
    example: '+1 416 555-0199',
  },
  {
    key: 'date',
    label: 'Date (YYYY-MM-DD)',
    pattern: '^\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$',
    example: '2026-07-18',
  },
  {
    key: 'wholeNumber',
    label: 'Whole number',
    pattern: '^\\d+$',
    example: '42',
  },
  {
    key: 'decimal',
    label: 'Amount with 2 decimals',
    pattern: '^\\d{1,9}\\.\\d{2}$',
    example: '1250.00',
  },
  {
    key: 'letters',
    label: 'Letters only',
    pattern: '^[A-Za-z]+$',
    example: 'Nairobi',
  },
  {
    key: 'lettersSpaces',
    label: 'Letters and spaces',
    pattern: '^[A-Za-z ]+$',
    example: 'Mary Jane',
  },
  {
    key: 'alphanumeric',
    label: 'Letters and digits (code)',
    pattern: '^[A-Za-z0-9]+$',
    example: 'AB12CD',
  },
  {
    key: 'url',
    label: 'Web address (URL)',
    pattern: '^https?://[A-Za-z0-9.-]+(/\\S*)?$',
    example: 'https://example.com/page',
  },
];

const PART_TYPE_LABELS = {
  text: 'Exact text',
  letters: 'Letters',
  digits: 'Digits',
  lettersDigits: 'Letters or digits',
  any: 'Any characters',
  oneOf: 'One of a list',
};

const NEW_PART = { type: 'digits', countMode: 'oneOrMore', n: 1, m: 2, value: '' };

// inputClass minus its w-full so part-row controls can sit side by side.
const rowInputClass = inputClass.replace('w-full ', '');

function Tester({ pattern }) {
  const [sample, setSample] = useState('');
  const verdict = useMemo(() => {
    if (!pattern) return null;
    try {
      return new RegExp(pattern).test(sample);
    } catch {
      return 'invalid';
    }
  }, [pattern, sample]);

  return (
    <div className="mt-3 border-t border-line pt-3">
      <Field label="Try a sample value" hint="Checked instantly against the pattern as you type.">
        <div className="flex items-center gap-3">
          <input
            className={inputClass}
            value={sample}
            onChange={(e) => setSample(e.target.value)}
            placeholder="Type a value this field could contain…"
          />
          {sample && verdict === true && (
            <span className="shrink-0 text-sm font-medium text-success-ink">✓ Matches</span>
          )}
          {sample && verdict === false && (
            <span className="shrink-0 text-sm font-medium text-danger-ink">✗ Doesn't match</span>
          )}
          {verdict === 'invalid' && (
            <span className="shrink-0 text-sm text-muted">Pattern is not valid yet</span>
          )}
        </div>
      </Field>
    </div>
  );
}

/**
 * Visual regular-expression builder for the REGEX validation rule: pick a
 * common pattern, assemble one part by part, or type raw regex. Controlled
 * via `pattern`/`onChange`; whatever tab produced the pattern, the same
 * string is saved.
 */
export default function RegexBuilder({ pattern, onChange }) {
  const [mode, setMode] = useState('presets');
  const [presetKey, setPresetKey] = useState('');
  const [parts, setParts] = useState([{ ...NEW_PART, type: 'text' }]);

  const preset = PRESETS.find((p) => p.key === presetKey);

  const selectPreset = (key) => {
    setPresetKey(key);
    const chosen = PRESETS.find((p) => p.key === key);
    onChange(chosen ? chosen.pattern : '');
  };

  const updateParts = (next) => {
    setParts(next);
    onChange(buildPattern(next));
  };

  const setPart = (index, patch) =>
    updateParts(parts.map((p, i) => (i === index ? { ...p, ...patch } : p)));

  const tabs = [
    ['presets', 'Common patterns'],
    ['builder', 'Custom builder'],
    ['advanced', 'Advanced (raw)'],
  ];

  return (
    <div className="rounded-md border border-line p-4">
      <div className="mb-4 flex flex-wrap gap-1 text-sm" role="tablist" aria-label="Pattern mode">
        {tabs.map(([key, label]) => (
          <button
            key={key}
            type="button"
            role="tab"
            aria-selected={mode === key}
            onClick={() => setMode(key)}
            className={`cursor-pointer rounded px-3 py-1.5 ${
              mode === key ? 'bg-pill font-medium text-accent' : 'text-muted hover:bg-neutral-200/60'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {mode === 'presets' && (
        <div className="space-y-2">
          <Field label="What should this field look like?">
            <select
              className={inputClass}
              value={presetKey}
              onChange={(e) => selectPreset(e.target.value)}
            >
              <option value="">Choose a pattern…</option>
              {PRESETS.map((p) => (
                <option key={p.key} value={p.key}>
                  {p.label}
                </option>
              ))}
            </select>
          </Field>
          {preset && (
            <p className="text-sm text-muted">
              Example of a matching value: <code className="text-ink">{preset.example}</code>
            </p>
          )}
        </div>
      )}

      {mode === 'builder' && (
        <div className="space-y-3">
          <p className="text-sm text-muted">
            Describe the value piece by piece, from left to right — no regex knowledge needed.
          </p>
          {parts.map((part, i) => (
            <div key={i} className="flex flex-wrap items-center gap-2">
              <select
                className={`${rowInputClass} w-44`}
                value={part.type}
                aria-label={`Part ${i + 1} type`}
                onChange={(e) => setPart(i, { type: e.target.value })}
              >
                {Object.entries(PART_TYPE_LABELS).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>

              {(part.type === 'text' || part.type === 'oneOf') && (
                <input
                  className={`${rowInputClass} min-w-40 flex-1`}
                  value={part.value}
                  aria-label={`Part ${i + 1} value`}
                  placeholder={part.type === 'text' ? 'e.g. INV-' : 'e.g. Yes, No, Maybe'}
                  onChange={(e) => setPart(i, { value: e.target.value })}
                />
              )}

              {part.type !== 'text' && part.type !== 'oneOf' && (
                <>
                  <select
                    className={`${rowInputClass} w-36`}
                    value={part.countMode}
                    aria-label={`Part ${i + 1} count`}
                    onChange={(e) => setPart(i, { countMode: e.target.value })}
                  >
                    <option value="oneOrMore">one or more</option>
                    <option value="exact">exactly</option>
                    <option value="range">between</option>
                  </select>
                  {part.countMode !== 'oneOrMore' && (
                    <input
                      type="number"
                      min="0"
                      className={`${rowInputClass} w-20`}
                      value={part.n}
                      aria-label={`Part ${i + 1} count n`}
                      onChange={(e) => setPart(i, { n: e.target.value })}
                    />
                  )}
                  {part.countMode === 'range' && (
                    <>
                      <span className="text-sm text-muted">and</span>
                      <input
                        type="number"
                        min="0"
                        className={`${rowInputClass} w-20`}
                        value={part.m}
                        aria-label={`Part ${i + 1} count m`}
                        onChange={(e) => setPart(i, { m: e.target.value })}
                      />
                    </>
                  )}
                </>
              )}

              <button
                type="button"
                aria-label={`Remove part ${i + 1}`}
                onClick={() => updateParts(parts.filter((_, j) => j !== i))}
                disabled={parts.length === 1}
                className="cursor-pointer rounded border border-line px-2 py-1 text-sm text-danger-ink disabled:cursor-not-allowed disabled:opacity-40"
              >
                ×
              </button>
            </div>
          ))}
          <button
            type="button"
            onClick={() => updateParts([...parts, { ...NEW_PART }])}
            className="cursor-pointer rounded border border-line px-3 py-1.5 text-sm text-accent hover:bg-pill/50"
          >
            + Add part
          </button>
        </div>
      )}

      {mode === 'advanced' && (
        <Field label="Pattern" hint="Java regular expression, e.g. ^[A-Z]{2}\d{4}$">
          <input
            className={`${inputClass} font-mono`}
            value={pattern}
            onChange={(e) => onChange(e.target.value)}
          />
        </Field>
      )}

      {mode !== 'advanced' && pattern && (
        <p className="mt-3 text-xs text-muted">
          Generated pattern: <code className="text-ink">{pattern}</code>
        </p>
      )}

      <Tester pattern={pattern} />
    </div>
  );
}
