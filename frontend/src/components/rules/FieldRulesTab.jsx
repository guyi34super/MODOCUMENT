import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { testRules, toRuleInput } from '../../api/docRules';
import { useToast } from '../../context/ToastContext';
import { KIND_LABELS } from '../pdf/geometry';
import { Button, Callout, Card, ErrorBanner, Spinner, Tag, inputClass } from '../ui';
import ImportRulesDialog from './ImportRulesDialog';
import RuleDialog from './RuleDialog';
import { DEFAULT_RULE_TYPES, RULE_LABELS, originText, ruleSummary } from './ruleText';

const PHOTO = new Set(['IMAGE', 'SIGNATURE']);

let draftCounter = 0;
const toDraft = (rule) => {
  draftCounter += 1;
  return { ...rule, _id: rule.id != null ? `r${rule.id}` : `n${draftCounter}` };
};

/** Sample values for every field → testRules → errors and warnings per field. */
function TestPanel({ blueprintId, fields, draftInputs, dirty }) {
  const [values, setValues] = useState({});
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState('');
  const ids = useId();

  const run = async (event) => {
    event.preventDefault();
    setBusy(true);
    setProblem('');
    try {
      setResult(await testRules(blueprintId, { values, rules: dirty ? draftInputs : undefined }));
    } catch (err) {
      setProblem(err.message);
    } finally {
      setBusy(false);
    }
  };

  const issuesFor = (key) => ({
    errors: (result?.errors || []).filter((e) => e.regionKey === key),
    warnings: (result?.warnings || []).filter((w) => w.regionKey === key),
  });
  const errorCount = result?.errors?.length || 0;
  const warningCount = result?.warnings?.length || 0;

  return (
    <form onSubmit={run} className="space-y-3" data-testid="rules-test">
      <p className="text-xs text-muted">
        Type sample values and run the rules on them. Nothing is stored.
        {dirty ? ' Your unsaved rules are tested.' : ''}
      </p>
      <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
        {fields
          .filter((f) => !PHOTO.has(f.kind))
          .map((f) => {
            const { errors, warnings } = issuesFor(f.key);
            const messageId = `${ids}-${f.key}`;
            const described = errors.length || warnings.length ? messageId : undefined;
            const name = `Sample value for ${f.label || f.key}`;
            return (
              <div key={f.key}>
                {f.kind === 'CHECKBOX' ? (
                  <label className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={values[f.key] === 'true'}
                      aria-describedby={described}
                      onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.checked ? 'true' : '' }))}
                    />
                    {name}
                  </label>
                ) : (
                  <label className="block text-sm">
                    <span className="mb-0.5 block">{name}</span>
                    <input
                      className={inputClass}
                      type={f.kind === 'DATE' ? 'date' : 'text'}
                      value={values[f.key] || ''}
                      aria-invalid={errors.length ? true : undefined}
                      aria-describedby={described}
                      onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))}
                    />
                  </label>
                )}
                {described && (
                  <ul id={messageId} className="mt-0.5 text-xs">
                    {errors.map((e, i) => (
                      <li key={`e${i}`} className="text-danger-ink">
                        {e.message}
                      </li>
                    ))}
                    {warnings.map((w, i) => (
                      <li key={`w${i}`} className="text-amber-800">
                        Warning: {w.message}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            );
          })}
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" size="sm" disabled={busy}>
          {busy ? 'Testing…' : 'Run the rules'}
        </Button>
        {result && (
          <span role="status" className={`text-sm ${errorCount ? 'text-danger-ink' : 'text-success-ink'}`}>
            {errorCount === 0 && warningCount === 0
              ? 'No errors or warnings'
              : `${errorCount} error${errorCount === 1 ? '' : 's'}, ${warningCount} warning${warningCount === 1 ? '' : 's'}`}
          </span>
        )}
      </div>
      {result?.setupWarnings?.length > 0 && (
        <Callout variant="info" title="Rules that were skipped">
          <ul className="list-inside list-disc">
            {result.setupWarnings.map((w, i) => (
              <li key={i}>{w.message}</li>
            ))}
          </ul>
        </Callout>
      )}
      {problem && (
        <p role="alert" className="text-sm text-danger-ink">
          {problem}
        </p>
      )}
    </form>
  );
}

/**
 * The Field rules tab of a template: every field with its rules (type,
 * summary, severity, origin), add / edit / delete rules, import from flow
 * templates and test with sample values. Edits stay in a draft until "Save
 * rules" sends the whole set with the loaded revision (409 → the page's
 * "changed elsewhere" dialog through `fieldRules.conflict`).
 *
 * `focusKey` scrolls to and focuses that field's section (from "Edit rules"
 * in the properties panel); `onShowField(key)` shows a field on the layout.
 */
export default function FieldRulesTab({ blueprintId, fieldRules, readOnly = false, focusKey, onFocused, onShowField }) {
  const toast = useToast();
  const { data } = fieldRules;
  const [draft, setDraft] = useState([]);
  const [dirty, setDirty] = useState(false);
  const [editing, setEditing] = useState(null);
  const [importOpen, setImportOpen] = useState(false);
  const [testOpen, setTestOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const sectionRefs = useRef(new Map());

  // A new server copy (load, save, import, reload after a conflict) replaces the draft.
  useEffect(() => {
    setDraft((data?.rules || []).map(toDraft));
    setDirty(false);
  }, [data]);

  useEffect(() => {
    if (!focusKey || !data) return;
    const section = sectionRefs.current.get(focusKey);
    if (section) {
      section.scrollIntoView?.({ block: 'start' });
      section.querySelector('button')?.focus();
    }
    onFocused?.();
  }, [focusKey, data, onFocused]);

  const fields = useMemo(() => data?.fields || [], [data]);
  const ruleTypes = data?.ruleTypes?.length ? data.ruleTypes : DEFAULT_RULE_TYPES;
  const nameOf = (key) => fields.find((f) => f.key === key)?.label || key;
  const orphanKeys = useMemo(
    () => [...new Set(draft.filter((r) => !fields.some((f) => f.key === r.regionKey)).map((r) => r.regionKey))],
    [draft, fields]
  );
  const draftInputs = draft.map(toRuleInput);

  if (!data) {
    return fieldRules.error ? (
      <div>
        <ErrorBanner message={fieldRules.error} />
        <Button variant="secondary" onClick={fieldRules.load}>
          Try again
        </Button>
      </div>
    ) : (
      <Spinner label="Loading the field rules…" />
    );
  }

  const change = (fn) => {
    setDraft(fn);
    setDirty(true);
  };

  const saveRule = (input) => {
    const { field, rule } = editing;
    change((list) => {
      if (rule) {
        return list.map((r) => (r._id === rule._id ? { ...r, ...input } : r));
      }
      const draftRule = toDraft({ ...input, origin: 'MANUAL' });
      const last = list.map((r) => r.regionKey).lastIndexOf(field.key);
      const next = [...list];
      next.splice(last < 0 ? next.length : last + 1, 0, draftRule);
      return next;
    });
    setEditing(null);
  };

  const save = async () => {
    setSaving(true);
    setError('');
    try {
      await fieldRules.save(draftInputs);
      toast.success('Field rules saved');
    } catch (err) {
      if (err.status !== 409) setError(err.message);
    } finally {
      setSaving(false);
    }
  };

  const section = (field, orphan = false) => {
    const rules = draft.filter((r) => r.regionKey === field.key);
    const name = field.label || field.key;
    return (
      <section
        key={field.key}
        ref={(node) => {
          if (node) sectionRefs.current.set(field.key, node);
          else sectionRefs.current.delete(field.key);
        }}
        aria-labelledby={`rules-heading-${field.key}`}
        className="rounded-lg border border-line bg-white p-3"
        data-testid={`rules-field-${field.key}`}
      >
        <div className="mb-2 flex flex-wrap items-center gap-2">
          <h3 id={`rules-heading-${field.key}`} className="text-sm font-medium">
            {name}
          </h3>
          <span className="font-mono text-xs text-muted">{field.key}</span>
          {field.kind && <Tag tone="neutral">{KIND_LABELS[field.kind] || field.kind}</Tag>}
          <span className="flex-1" />
          {!orphan && onShowField && (
            <button type="button" className="text-xs text-accent hover:underline" onClick={() => onShowField(field.key)}>
              Show on the page<span className="sr-only">: {name}</span>
            </button>
          )}
          {!readOnly && !orphan && (
            <Button size="sm" variant="secondary" onClick={() => setEditing({ field, rule: null })}>
              Add rule<span className="sr-only"> to {name}</span>
            </Button>
          )}
        </div>
        {orphan && (
          <p className="mb-2 rounded bg-amber-100 px-2 py-1 text-xs text-amber-900" role="note">
            This field is no longer on the template: its rules are skipped. Delete them or add the field back.
          </p>
        )}
        {rules.length === 0 ? (
          <p className="text-xs text-muted">No rules.</p>
        ) : (
          <table className="w-full text-left text-xs">
            <caption className="sr-only">Rules of {name}</caption>
            <thead className="text-muted">
              <tr>
                <th className="p-1 font-medium">Rule</th>
                <th className="p-1 font-medium">Checks</th>
                <th className="p-1 font-medium">Severity</th>
                <th className="p-1 font-medium">Origin</th>
                <th className="p-1 font-medium">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rules.map((rule) => {
                const typeName = RULE_LABELS[rule.ruleType] || rule.ruleType;
                return (
                  <tr key={rule._id} className="border-t border-line align-top">
                    <td className="p-1 font-medium">{typeName}</td>
                    <td className="p-1">
                      {ruleSummary(rule, nameOf)}
                      {rule.message && <span className="block italic text-muted">“{rule.message}”</span>}
                    </td>
                    <td className="p-1">
                      <Tag tone={rule.severity === 'WARNING' ? 'warning' : 'danger'}>
                        {rule.severity === 'WARNING' ? 'Warning' : 'Error'}
                      </Tag>
                    </td>
                    <td className="p-1">
                      <Tag tone={rule.origin === 'IMPORTED' ? 'info' : 'neutral'}>{originText(rule)}</Tag>
                    </td>
                    <td className="whitespace-nowrap p-1 text-right">
                      {!readOnly && (
                        <>
                          {!orphan && (
                            <button
                              type="button"
                              className="mr-2 text-accent hover:underline"
                              aria-label={`Edit ${typeName} rule of ${name}`}
                              onClick={() => setEditing({ field, rule })}
                            >
                              Edit
                            </button>
                          )}
                          <button
                            type="button"
                            className="text-danger-ink hover:underline"
                            aria-label={`Delete ${typeName} rule of ${name}`}
                            onClick={() => change((list) => list.filter((r) => r._id !== rule._id))}
                          >
                            Delete
                          </button>
                        </>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </section>
    );
  };

  return (
    <div className="space-y-4" data-testid="field-rules">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-medium">Field rules</h2>
        <span className="text-xs text-muted">revision {data.revision}</span>
        <span className="flex-1" />
        {!readOnly && (
          <Button variant="secondary" onClick={() => setImportOpen(true)} disabled={dirty}>
            Import rules
          </Button>
        )}
        <Button variant="secondary" aria-expanded={testOpen} onClick={() => setTestOpen((o) => !o)}>
          Test with sample values
        </Button>
        {!readOnly && (
          <>
            <Button
              variant="secondary"
              onClick={() => {
                setDraft((data.rules || []).map(toDraft));
                setDirty(false);
              }}
              disabled={!dirty || saving}
            >
              Discard changes
            </Button>
            <Button onClick={save} disabled={!dirty || saving}>
              {saving ? 'Saving…' : 'Save rules'}
            </Button>
          </>
        )}
      </div>
      <p className="text-xs text-muted">
        Rules are checked when a document is saved: errors keep it a draft, warnings are shown but allow it.
        {dirty && <strong className="ml-1 text-amber-800">Unsaved rule changes.</strong>}
        {dirty && !readOnly && ' Save or discard them before importing.'}
      </p>
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {testOpen && (
        <Card padding="compact">
          <TestPanel blueprintId={blueprintId} fields={fields} draftInputs={draftInputs} dirty={dirty} />
        </Card>
      )}

      {fields.length === 0 && orphanKeys.length === 0 && <p className="text-sm text-muted">This template has no fields.</p>}
      {fields.map((f) => section(f))}
      {orphanKeys.map((key) => section({ key, label: key, kind: null }, true))}

      <RuleDialog
        open={Boolean(editing)}
        field={editing?.field}
        rule={editing?.rule}
        ruleTypes={ruleTypes}
        fields={fields}
        onSave={saveRule}
        onCancel={() => setEditing(null)}
      />
      <ImportRulesDialog
        open={importOpen}
        onClose={() => setImportOpen(false)}
        blueprintId={blueprintId}
        baseRevision={data.revision}
        onImported={(dto) => {
          fieldRules.setData(dto);
          setImportOpen(false);
          toast.success('Rules imported');
        }}
        onConflict={() => {
          setImportOpen(false);
          fieldRules.setConflict(true);
        }}
      />
    </div>
  );
}
