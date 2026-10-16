import { useState } from 'react';
import { Link } from 'react-router-dom';
import IssuesPanel from './IssuesPanel';
import { FieldChecksDetail, checksFor, hasChecks, totalRules } from './fieldChecks';
import { fieldLabel } from './fields';

const DONE = new Set(['GENERATED', 'SUBMITTED', 'RELEASED']);

const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;

/** The one-line state of the checks, and its tone. */
function stateLine({ validation, status, locked }) {
  if (!validation?.checked) {
    return {
      tone: 'muted',
      text: locked ? 'Not checked yet.' : 'Not checked yet — press Save to check this document.',
    };
  }
  const errors = validation.errors?.length || 0;
  const warnings = validation.warnings?.length || 0;
  if (errors) {
    return {
      tone: 'error',
      text: `${plural(errors, 'error')}, ${plural(warnings, 'warning')} — fix them and press Save again.`,
    };
  }
  if (status === 'VALIDATED') {
    const at = validation.validatedAt ? new Date(validation.validatedAt).toLocaleString() : '';
    return { tone: 'ok', text: `Validated${at ? ` on ${at}` : ''}` };
  }
  if (DONE.has(status)) return { tone: 'ok', text: 'Generated · validated' };
  // A draft whose last check passed: a value changed since.
  return { tone: 'muted', text: 'Changed since the last check — press Save to check again.' };
}

const TONES = { muted: 'text-muted', error: 'text-danger-ink', ok: 'text-success-ink' };

/** A heading that opens and closes the part under it (disclosure pattern). */
function Disclosure({ id, title, open, onToggle, children }) {
  return (
    <div>
      <h3 className="text-sm font-medium">
        <button
          type="button"
          aria-expanded={open}
          aria-controls={open ? id : undefined}
          onClick={onToggle}
          className="flex w-full items-center gap-1 text-left hover:text-accent"
        >
          <span aria-hidden="true" className="inline-block w-3 text-muted">
            {open ? '▾' : '▸'}
          </span>
          {title}
        </button>
      </h3>
      {open && (
        <div id={id} className="mt-1">
          {children}
        </div>
      )}
    </div>
  );
}

/**
 * The builder's "Checks" panel (field-validation contract §4.4): always on
 * screen (except while editing the layout), so the checks of the document
 * are visible before the first Save — its state, the required fields, the
 * issues of the last Save, the template's rule problems, the rules and
 * data-source mapping of each field, and (for the template's owner) links
 * to the template's Field rules and Data sources tabs.
 */
export default function ValidationPanel({ doc, status, locked, regions, issues, onGoTo, requiredFilled, requiredTotal }) {
  const validation = doc.validation || null;
  const fieldChecks = doc.fieldChecks || {};
  const rulesTotal = totalRules(fieldChecks);
  const [rulesOpen, setRulesOpen] = useState(rulesTotal > 0);
  const [setupOpen, setSetupOpen] = useState(false);
  const line = stateLine({ validation, status, locked });
  const setupWarnings = validation?.setupWarnings || [];
  const fields = regions.map((region) => [region, checksFor(fieldChecks, region.key)]).filter(([, c]) => hasChecks(c));
  const labelOf = (key) => fieldLabel(regions.find((r) => r.key === key) || { key });
  const base = `/creation/blueprints/${doc.blueprintId}`;

  return (
    <section aria-labelledby="checks-heading" className="space-y-3 rounded-lg border border-line bg-white p-3" data-testid="validation-panel">
      <h2 id="checks-heading" className="text-sm font-medium">
        Checks
      </h2>
      <p className={`text-sm ${TONES[line.tone]}`} data-testid="checks-state">
        {line.text}
      </p>
      {validation?.rulesChangedSinceValidation && !locked && (
        <p className="rounded bg-amber-100 px-2 py-1 text-xs text-amber-900" role="note">
          Rules changed since this document was validated — save again to re-check.
        </p>
      )}
      <p className="text-xs text-muted" data-testid="required-progress">
        {requiredTotal > 0
          ? `${requiredFilled} of ${plural(requiredTotal, 'required field')} filled`
          : 'No required fields'}
        {` · ${plural(rulesTotal, 'rule')} on this template`}
      </p>

      <IssuesPanel issues={issues} onGoTo={onGoTo} />

      {setupWarnings.length > 0 && (
        <Disclosure
          id="checks-setup-warnings"
          title={`Template rule problems (${setupWarnings.length})`}
          open={setupOpen}
          onToggle={() => setSetupOpen((open) => !open)}
        >
          <ul className="space-y-1 text-xs text-amber-900" data-testid="setup-warnings">
            {setupWarnings.map((w, i) => (
              <li key={`${w.ruleId ?? 'x'}:${w.regionKey ?? ''}:${i}`}>
                {w.regionKey ? <span className="font-medium">{labelOf(w.regionKey)}: </span> : null}
                {w.message}
              </li>
            ))}
          </ul>
        </Disclosure>
      )}

      <div>
        <Disclosure
          id="checks-field-rules"
          title="Rules for each field"
          open={rulesOpen}
          onToggle={() => setRulesOpen((open) => !open)}
        >
          {fields.length === 0 ? (
            <p className="text-xs text-muted">No checks on the fields of this template.</p>
          ) : (
            <ul className="max-h-80 space-y-2 overflow-y-auto pr-1" data-testid="field-rules-list">
              {fields.map(([region, checks]) => (
                <li key={region.key} data-testid={`field-rules-${region.key}`}>
                  <button
                    type="button"
                    className="text-left text-xs font-medium text-accent hover:underline"
                    onClick={() => onGoTo(region.key)}
                  >
                    {fieldLabel(region)}
                  </button>
                  <FieldChecksDetail checks={checks} />
                </li>
              ))}
            </ul>
          )}
        </Disclosure>
        {rulesTotal === 0 && <p className="mt-1 text-xs text-muted">This template has no field rules yet.</p>}
      </div>

      {doc.canEditRules && (
        <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs">
          <Link to={`${base}?tab=rules`} className="text-accent hover:underline">
            Edit rules
          </Link>
          <Link to={`${base}?tab=sources`} className="text-accent hover:underline">
            Map from data sources
          </Link>
        </div>
      )}
    </section>
  );
}
