import { useId } from 'react';
import { Link } from 'react-router-dom';
import { Button, Switch, Tag, inputClass } from './ui';

/** The layer an effective value came from, as the UI names it. */
export const SOURCE_LABELS = { BANK: 'Bank', PLATFORM: 'Platform', DEFAULT: 'Default' };

const RANGED = new Set(['INT', 'DECIMAL', 'DURATION']);
const SIMPLE_DURATION = /^\d{1,12}\s*(ms|s|m|h|d)$/i;
const ISO_DURATION = /^P(?=.*\d)(\d+D)?(T(\d+H)?(\d+M)?(\d+(\.\d+)?S)?)?$/i;

/** A value as people read it: On/Off for switches, "—" for nothing. */
export function formatSettingValue(type, value) {
  if (value === null || value === undefined || value === '') return '—';
  if (type === 'BOOLEAN') return value === true || value === 'true' ? 'On' : 'Off';
  return String(value);
}

/**
 * Shape check of what was typed (whole number, number, duration). The
 * server owns the limits — an out-of-range value is its SETTING_OUT_OF_RANGE
 * to report — so this only stops input that cannot be a value at all.
 *
 * @returns an error message, or null when the input is well-formed
 */
export function validateSettingInput(type, raw) {
  const text = raw === null || raw === undefined ? '' : String(raw).trim();
  if (type === 'INT' && !/^-?\d+$/.test(text)) return 'Enter a whole number.';
  if (type === 'DECIMAL' && (text === '' || !Number.isFinite(Number(text)))) {
    return 'Enter a number.';
  }
  if (type === 'DURATION' && !SIMPLE_DURATION.test(text) && !ISO_DURATION.test(text)) {
    return 'Use a duration such as 90s, 15m, 12h or 1d.';
  }
  return null;
}

/** The JSON value the settings API expects for an input (contracts §3). */
export function toApiValue(type, raw) {
  if (type === 'INT') return parseInt(String(raw).trim(), 10);
  if (type === 'DECIMAL') return Number(String(raw).trim());
  if (type === 'BOOLEAN') return raw === true || raw === 'true';
  if (type === 'DURATION') return String(raw).trim().replace(/\s+/g, '');
  return raw;
}

function sameValue(type, a, b) {
  if (type === 'BOOLEAN') return (a === true || a === 'true') === (b === true || b === 'true');
  const left = a === null || a === undefined ? '' : String(a).trim();
  const right = b === null || b === undefined ? '' : String(b).trim();
  if ((type === 'INT' || type === 'DECIMAL') && left !== '' && right !== '') {
    return Number(left) === Number(right);
  }
  return left === right;
}

/**
 * Drops the parts of a draft that equal what the server holds; undefined
 * when nothing is left (the setting is unchanged).
 */
export function normalizeDraft(setting, draft) {
  if (!draft) return undefined;
  const next = { ...draft };
  if (!next.reset && 'value' in next && sameValue(setting.type, next.value, setting.value)) {
    delete next.value;
  }
  for (const bound of ['bankMin', 'bankMax']) {
    if (bound in next && sameValue(setting.type, next[bound], setting[bound])) {
      delete next[bound];
    }
  }
  if (!next.reset) delete next.reset;
  return Object.keys(next).length === 0 ? undefined : next;
}

/** The client-side problem of a draft (malformed input), or null. */
export function draftProblem(setting, draft) {
  if (!draft || draft.reset) return null;
  if ('value' in draft && setting.type !== 'BOOLEAN' && setting.type !== 'ENUM') {
    const problem = validateSettingInput(setting.type, draft.value);
    if (problem) return problem;
  }
  for (const bound of ['bankMin', 'bankMax']) {
    if (bound in draft && draft[bound] !== '' && draft[bound] !== null) {
      const problem = validateSettingInput(setting.type, draft[bound]);
      if (problem) return problem;
    }
  }
  return null;
}

/** "12–128 characters · go-live floor 12" (ranged settings only). */
export function limitsHint(setting) {
  if (!RANGED.has(setting.type) || setting.min == null || setting.max == null) return null;
  const unit = setting.unit && setting.type !== 'DURATION' ? ` ${setting.unit}` : '';
  let hint = `${setting.min}–${setting.max}${unit}`;
  if (setting.hardMin != null) hint += ` · go-live floor ${setting.hardMin}`;
  if (setting.hardMax != null && String(setting.hardMax) !== String(setting.max)) {
    hint += ` · ceiling ${setting.hardMax}`;
  }
  return hint;
}

/**
 * One editable setting (phase 2 shared component): the input for its type
 * (number/decimal with unit, on/off switch, choice list, duration text),
 * the effective value with its source (Bank / Platform / Default), the
 * limits hint, the badges "Requires approval" / "Pending" / "Locked" /
 * "Clamped by platform", "Reset to inherited" and an inline error.
 *
 * Controlled: the page owns the drafts. `draft` is undefined (unchanged) or
 * `{ value }`, `{ reset: true }` and, in platform mode, `{ bankMin, bankMax }`
 * for settings with bank bounds; `onDraft(next)` receives the normalized
 * draft (undefined = back to the stored value). `error` is the server's
 * refusal for this key.
 */
export default function SettingField({ setting, mode = 'bank', draft, onDraft, error, disabled }) {
  const id = useId();
  const labelId = `${id}-label`;
  const inputId = `${id}-input`;
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const ownLayer = mode === 'platform' ? 'PLATFORM' : 'BANK';
  const pending = setting.pendingChangeId !== null && setting.pendingChangeId !== undefined;
  const resetting = Boolean(draft?.reset);
  const readOnly = setting.locked || pending || disabled;
  const current = draft && 'value' in draft ? draft.value : setting.value;
  const problem = draftProblem(setting, draft);
  const shownError = problem || error;
  const unit = setting.unit && setting.type !== 'DURATION' && setting.type !== 'BOOLEAN'
    ? setting.unit
    : null;
  const limits = limitsHint(setting);
  const showBounds = mode === 'platform' && setting.hasBankBounds;

  const change = (patch) => {
    const next = { ...(draft || {}), ...patch };
    if ('value' in patch || 'bankMin' in patch || 'bankMax' in patch) delete next.reset;
    onDraft?.(normalizeDraft(setting, next));
  };

  const describedBy = [hintId, shownError ? errorId : null].filter(Boolean).join(' ');
  const inputProps = {
    id: inputId,
    'aria-describedby': describedBy,
    'aria-invalid': shownError ? true : undefined,
    disabled: readOnly || resetting,
  };

  let input;
  if (setting.type === 'BOOLEAN') {
    const on = current === true || current === 'true';
    input = (
      <div className="flex items-center gap-2">
        <Switch
          id={inputId}
          checked={on}
          labelledBy={labelId}
          disabled={readOnly || resetting}
          onChange={(value) => change({ value })}
        />
        <span className="text-sm text-ink">{on ? 'On' : 'Off'}</span>
      </div>
    );
  } else if (setting.type === 'ENUM') {
    input = (
      <select
        {...inputProps}
        className={`${inputClass} w-64`}
        value={current ?? ''}
        onChange={(e) => change({ value: e.target.value })}
      >
        {(setting.allowed || []).map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    );
  } else if (setting.type === 'DURATION') {
    input = (
      <input
        {...inputProps}
        type="text"
        inputMode="text"
        placeholder="e.g. 15m, 12h, 1d"
        className={`${inputClass} w-40 font-mono`}
        value={current ?? ''}
        onChange={(e) => change({ value: e.target.value })}
      />
    );
  } else {
    input = (
      <input
        {...inputProps}
        type="number"
        step={setting.type === 'INT' ? 1 : 'any'}
        inputMode={setting.type === 'INT' ? 'numeric' : 'decimal'}
        className={`${inputClass} w-40`}
        value={current ?? ''}
        onChange={(e) => change({ value: e.target.value })}
      />
    );
  }

  const boundInput = (bound, title) => {
    const value = draft && bound in draft ? draft[bound] : setting[bound];
    return (
      <label className="flex items-center gap-2 text-xs text-muted">
        <span>{title}</span>
        <input
          aria-label={`${setting.label}: ${title.toLowerCase()}`}
          type={setting.type === 'DURATION' ? 'text' : 'number'}
          step={setting.type === 'INT' ? 1 : 'any'}
          placeholder={String(bound === 'bankMin' ? setting.hardMin ?? '' : setting.hardMax ?? '')}
          className={`${inputClass} w-28`}
          disabled={readOnly}
          value={value ?? ''}
          onChange={(e) => change({ [bound]: e.target.value })}
        />
      </label>
    );
  };

  return (
    <div
      data-testid={`setting-${setting.key}`}
      className="border-b border-line/70 py-4 last:border-0"
    >
      <div className="flex flex-wrap items-center gap-2">
        <label
          id={labelId}
          htmlFor={setting.type === 'BOOLEAN' ? undefined : inputId}
          className="text-sm font-medium text-ink"
        >
          {setting.label}
        </label>
        {setting.requiresApproval && !setting.locked && (
          <Tag tone="warning" title="A second administrator must approve a change.">
            Requires approval
          </Tag>
        )}
        {pending && (
          <>
            <Tag tone="info" title={`Pending approval (change #${setting.pendingChangeId})`}>
              Pending
            </Tag>
            <Link to="/admin/changes" className="text-xs text-accent hover:underline">
              change #{setting.pendingChangeId}
            </Link>
          </>
        )}
        {setting.locked && (
          <Tag title="Fixed by the go-live security gate: nobody can change it.">Locked</Tag>
        )}
        {setting.clamped && (
          <Tag
            tone="danger"
            title="The stored value lay outside the current limits and was corrected."
          >
            Clamped by platform
          </Tag>
        )}
        {setting.requiresRestart && (
          <Tag title="Takes effect after the application restarts.">Restart required</Tag>
        )}
      </div>
      {setting.description && <p className="mt-0.5 text-xs text-muted">{setting.description}</p>}

      <div className="mt-2 flex flex-wrap items-center gap-2">
        {input}
        {unit && <span className="text-sm text-muted">{unit}</span>}
        {resetting && (
          <span className="text-xs text-muted">Resets to the inherited value when saved.</span>
        )}
        {!readOnly && !resetting && setting.source === ownLayer && (
          <Button
            type="button"
            size="sm"
            variant="secondary"
            onClick={() => onDraft?.({ reset: true })}
          >
            Reset to inherited
            {/* Many settings share this button: name the one it acts on. */}
            <span className="sr-only">: {setting.label}</span>
          </Button>
        )}
        {draft && !readOnly && (
          <Button type="button" size="sm" variant="secondary" onClick={() => onDraft?.(undefined)}>
            Undo
            <span className="sr-only"> change to {setting.label}</span>
          </Button>
        )}
      </div>

      {showBounds && (
        <div className="mt-2 flex flex-wrap items-center gap-3">
          <span className="text-xs font-medium text-ink">Banks may choose</span>
          {boundInput('bankMin', 'Bank minimum')}
          {boundInput('bankMax', 'Bank maximum')}
        </div>
      )}

      <p id={hintId} className="mt-1 text-xs text-muted">
        Effective: {formatSettingValue(setting.type, setting.value)}
        {unit ? ` ${unit}` : ''} · Source:{' '}
        <span data-testid="setting-source">{SOURCE_LABELS[setting.source] || setting.source}</span>
        {limits && <> · {limits}</>}
      </p>
      {shownError && (
        <p id={errorId} role="alert" className="mt-1 text-xs text-danger-ink">
          {shownError}
        </p>
      )}
    </div>
  );
}
