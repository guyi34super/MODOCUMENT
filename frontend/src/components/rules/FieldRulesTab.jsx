import { useEffect, useMemo, useRef, useState } from 'react';
import { testRules, toRuleInput } from '../../api/docRules';
import { useToast } from '../../context/ToastContext';
import { KIND_LABELS } from '../pdf/geometry';
import { Button, Card, ErrorBanner, Spinner, Tag } from '../ui';
import ImportRulesDialog from './ImportRulesDialog';
import RuleDialog from './RuleDialog';
import RuleTable from './RuleTable';
import RulesTestPanel from './RulesTestPanel';
import { DEFAULT_RULE_TYPES } from './ruleText';

let draftCounter = 0;
const toDraft = (rule) => {
  draftCounter += 1;
  return { ...rule, _id: rule.id != null ? `r${rule.id}` : `n${draftCounter}` };
};

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
export default function FieldRulesTab({
  blueprintId,
  fieldRules,
  readOnly = false,
  focusKey,
  onFocused,
  onShowField,
  dataTemplateName,
}) {
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
  // ws34: the data template's rules re-keyed onto the mapped fields (read-only here).
  const inherited = useMemo(() => data?.inherited || [], [data]);
  const inheritedKeys = useMemo(() => [...new Set(inherited.map((r) => r.regionKey))], [inherited]);
  const inheritedName =
    dataTemplateName || inherited[0]?.dataTemplateName || inherited[0]?.sourceTemplateName || 'the data template';

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
        <RuleTable
          name={name}
          rules={rules}
          nameOf={nameOf}
          readOnly={readOnly}
          canEdit={!orphan}
          onEdit={(rule) => setEditing({ field, rule })}
          onDelete={(rule) => change((list) => list.filter((r) => r._id !== rule._id))}
        />
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
          <RulesTestPanel
            fields={fields}
            dirty={dirty}
            onTest={(values) => testRules(blueprintId, { values, rules: dirty ? draftInputs : undefined })}
          />
        </Card>
      )}

      {inherited.length > 0 && (
        <section
          aria-labelledby="inherited-rules-heading"
          className="space-y-2 rounded-lg border border-line bg-pill/30 p-3"
          data-testid="inherited-rules"
        >
          <h3 id="inherited-rules-heading" className="text-sm font-medium">
            Inherited from {inheritedName}
          </h3>
          <p className="text-xs text-muted">
            These rules come with the data template through the field mapping. Change them on the data template; they
            are read-only here.
          </p>
          {inheritedKeys.map((key) => {
            const name = nameOf(key);
            return (
              <div key={key} data-testid={`inherited-field-${key}`}>
                <p className="text-xs font-medium">
                  {name} <span className="font-mono font-normal text-muted">{key}</span>
                </p>
                <RuleTable name={`${name} (inherited)`} rules={inherited.filter((r) => r.regionKey === key)} nameOf={nameOf} readOnly />
              </div>
            );
          })}
        </section>
      )}
      {inherited.length > 0 && <h3 className="text-sm font-medium">This PDF’s own extra rules</h3>}

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
