import { useCallback, useEffect, useId, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  DATA_FIELD_KINDS,
  getDataTemplate,
  saveDataTemplateFields,
  saveDataTemplateRules,
  testDataTemplateRules,
  toDataRuleInput,
  toFieldInput,
  updateDataTemplate,
} from '../api/dataTemplates';
import ConfirmDialog from '../components/ConfirmDialog';
import Modal from '../components/Modal';
import PageHeader from '../components/PageHeader';
import { FieldInputProperties, optionsProblem } from '../components/pdf/RegionPropertiesPanel';
import { KEY_PATTERN, KIND_LABELS, uniqueKey } from '../components/pdf/geometry';
import { LIMITS } from '../components/pdf/limits';
import FieldRulesDrawer from '../components/rules/FieldRulesDrawer';
import RulesTestPanel from '../components/rules/RulesTestPanel';
import { DEFAULT_RULE_TYPES, ruleSummary } from '../components/rules/ruleText';
import { Button, Card, ErrorBanner, Field, Spinner, Switch, Tag, inputClass } from '../components/ui';
import { useToast } from '../context/ToastContext';
import useUnsavedGuard from '../hooks/useUnsavedGuard';

/** PII classes of a data field (the flow's PiiKind); '' = not personal data or not set. */
export const PII_KINDS = [
  { value: '', label: 'Not set' },
  { value: 'NAME', label: 'Name' },
  { value: 'NIC', label: 'NIC' },
  { value: 'ACCOUNT_NUMBER', label: 'Account number' },
  { value: 'EMAIL', label: 'Email' },
  { value: 'PHONE', label: 'Phone' },
  { value: 'NONE', label: 'Not PII' },
];

let uidCounter = 0;
const withUid = (field) => {
  uidCounter += 1;
  return { ...field, _uid: `f${uidCounter}`, _new: field._new || false };
};
let ruleCounter = 0;
const withRuleId = (rule) => {
  ruleCounter += 1;
  return { ...rule, _id: rule.id != null ? `r${rule.id}` : `n${ruleCounter}` };
};

/** The problem with one field of the draft, or ''. */
export function dataFieldProblem(field, fields, { rules = [], ruleTypes = [] } = {}) {
  if (!KEY_PATTERN.test(field.name || '')) return 'Use 1–64 lower-case letters, digits or _ for the name';
  if (fields.some((f) => f !== field && f.name === field.name)) return 'Another field already uses this name';
  if (!field.label?.trim()) return 'Give the field a label';
  // The server refuses a rule that does not fit its field's kind (e.g. after a kind change).
  const unfit = rules.filter((r) => {
    const type = ruleTypes.find((t) => t.type === r.ruleType);
    return r.fieldName === field.name && type?.kinds?.length && !type.kinds.includes(field.kind);
  });
  if (unfit.length) {
    const kind = KIND_LABELS[field.kind] || field.kind;
    return `${unfit.length === 1 ? 'A rule does' : `${unfit.length} rules do`} not fit a ${kind} field — remove ${
      unfit.length === 1 ? 'it' : 'them'
    } with Edit rules`;
  }
  return optionsProblem(field);
}

/**
 * A data template (data-templates contract §2, §6): its details, its fields
 * (name, label, kind, PII, Required, rules) with their properties, the
 * rules of each field in a drawer, and a test with sample values. Fields
 * and rules are drafts until "Save fields" / "Save rules" (each with the
 * loaded revision; a 409 offers to reload).
 */
export default function DataTemplateEditorPage() {
  const { id } = useParams();
  const toast = useToast();
  const ids = useId();
  const [dto, setDto] = useState(null);
  const [error, setError] = useState('');
  const [details, setDetails] = useState({ name: '', description: '' });
  const [fields, setFields] = useState([]);
  const [rules, setRules] = useState([]);
  const [fieldsDirty, setFieldsDirty] = useState(false);
  const [rulesDirty, setRulesDirty] = useState(false);
  const [selected, setSelected] = useState(null);
  const [drawer, setDrawer] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const [testOpen, setTestOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [tried, setTried] = useState(false);
  const [focusUid, setFocusUid] = useState(null);

  const adopt = useCallback((next, { keepRules = false } = {}) => {
    setDto(next);
    setDetails({ name: next.name || '', description: next.description || '' });
    setFields([...(next.fields || [])].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0)).map(withUid));
    setFieldsDirty(false);
    if (!keepRules) {
      setRules((next.rules || []).map(withRuleId));
      setRulesDirty(false);
    }
    setTried(false);
  }, []);

  const load = useCallback(async () => {
    try {
      adopt(await getDataTemplate(id));
      setError('');
    } catch (err) {
      setError(err.message);
    }
  }, [id, adopt]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (!focusUid) return;
    const input = document.getElementById(`${ids}-label-${focusUid}`);
    if (input) {
      input.focus();
      setFocusUid(null);
    }
  });

  const dirty = fieldsDirty || rulesDirty;
  useUnsavedGuard(dirty, 'You have unsaved changes to this data template. Leave without saving them?');

  const ruleTypes = dto?.ruleTypes?.length ? dto.ruleTypes : DEFAULT_RULE_TYPES;
  const asRuleFields = useMemo(() => fields.map((f) => ({ key: f.name, label: f.label, kind: f.kind })), [fields]);
  const nameOf = (key) => fields.find((f) => f.name === key)?.label || key;
  const rulesOf = (name) => rules.filter((r) => r.fieldName === name);
  const problems = fields.map((f) => dataFieldProblem(f, fields, { rules, ruleTypes }));
  const blocking = problems.some(Boolean);
  const detailsDirty = dto && (details.name !== (dto.name || '') || details.description !== (dto.description || ''));

  const changeFields = (fn) => {
    setFields(fn);
    setFieldsDirty(true);
  };

  /** A field changed: a renamed field takes its rules along. */
  const updateField = (uid, patch) => {
    const before = fields.find((f) => f._uid === uid);
    if (!before) return;
    const next = { ...before, ...patch };
    // A new field's name follows its label until the user types a name.
    if ('label' in patch && before._new && !before._namedByHand) {
      const taken = new Set(fields.filter((f) => f._uid !== uid).map((f) => f.name));
      next.name = uniqueKey(patch.label || 'field', taken);
    }
    if ('name' in patch) next._namedByHand = true;
    if (next.kind === 'CHOICE' && !next.props?.options) next.props = { ...(next.props || {}), options: [] };
    changeFields((list) => list.map((f) => (f._uid === uid ? next : f)));
    if (next.name !== before.name && rules.some((r) => r.fieldName === before.name)) {
      setRules((list) => list.map((r) => (r.fieldName === before.name ? { ...r, fieldName: next.name } : r)));
      setRulesDirty(true);
    }
  };

  const addField = () => {
    const taken = new Set(fields.map((f) => f.name));
    const field = withUid({ name: uniqueKey('new_field', taken), label: 'New field', kind: 'TEXT', piiKind: null, props: null, _new: true });
    changeFields((list) => [...list, field]);
    setSelected(field._uid);
    setFocusUid(field._uid);
  };

  /** Rules that go with a field: its own, and REQUIRED_IF / COMPARE rules of other fields naming it. */
  const rulesGoingWith = (name) =>
    rules.filter(
      (r) => r.fieldName === name || ((r.ruleType === 'REQUIRED_IF' || r.ruleType === 'COMPARE') && r.config?.Field === name)
    );

  const removeField = () => {
    const field = deleting;
    changeFields((list) => list.filter((f) => f._uid !== field._uid));
    const going = new Set(rulesGoingWith(field.name).map((r) => r._id));
    if (going.size) {
      setRules((list) => list.filter((r) => !going.has(r._id)));
      setRulesDirty(true);
    }
    if (selected === field._uid) setSelected(null);
    setDeleting(null);
    toast.success(`Removed “${field.label || field.name}” — save the fields to keep it removed`);
  };

  const hasRequired = (name) => rules.some((r) => r.fieldName === name && r.ruleType === 'REQUIRED');
  const setRequired = (name, on) => {
    setRules((list) => {
      if (on) {
        if (list.some((r) => r.fieldName === name && r.ruleType === 'REQUIRED')) return list;
        const required = withRuleId({ fieldName: name, ruleType: 'REQUIRED', config: {}, message: null, severity: 'ERROR', origin: 'MANUAL' });
        const index = list.findIndex((r) => r.fieldName === name);
        const next = [...list];
        next.splice(index < 0 ? next.length : index, 0, required);
        return next;
      }
      return list.filter((r) => !(r.fieldName === name && r.ruleType === 'REQUIRED'));
    });
    setRulesDirty(true);
  };

  const saveDrawer = (fieldRules) => {
    const name = drawer.name;
    setRules((list) => {
      const others = list.filter((r) => r.fieldName !== name);
      const first = list.findIndex((r) => r.fieldName === name);
      const mine = fieldRules.map((r) => withRuleId({ ...r, fieldName: name }));
      const next = [...others];
      next.splice(first < 0 ? next.length : Math.min(first, next.length), 0, ...mine);
      return next;
    });
    setRulesDirty(true);
    setDrawer(null);
  };

  const failed = (err, { fields: savingFields = false } = {}) => {
    if (err.status === 409) setConflict(true);
    // A kind change that a field's rules no longer fit is refused (400): say how to get past it.
    else if (savingFields && err.status === 400 && /rule/i.test(err.message || '')) {
      setError(`${err.message} Remove the rules that no longer fit (Edit rules), save the rules, then save the fields again.`);
    } else setError(err.message);
  };

  /** PUT /fields; resolves to the new DTO (rules of the draft kept when `keepRules`). */
  const writeFields = async ({ keepRules, baseRevision = dto.revision }) => {
    const next = await saveDataTemplateFields(id, {
      baseRevision,
      fields: fields.map((f) => toFieldInput(f)),
    });
    adopt(next, { keepRules });
    return next;
  };

  const saveFields = async () => {
    setTried(true);
    if (blocking) {
      setError('Fix the problems shown in the fields table first.');
      return;
    }
    setSaving(true);
    setError('');
    try {
      await writeFields({ keepRules: rulesDirty });
      toast.success('Fields saved');
    } catch (err) {
      failed(err, { fields: true });
    } finally {
      setSaving(false);
    }
  };

  const saveRules = async () => {
    setTried(true);
    if (blocking) {
      setError('Fix the problems shown in the fields table first.');
      return;
    }
    setSaving(true);
    setError('');
    let base = dto;
    try {
      if (fieldsDirty) {
        try {
          base = await writeFields({ keepRules: true });
        } catch (err) {
          if (err.status !== 400) {
            failed(err, { fields: true });
            return;
          }
          // The saved rules may block a kind change: save the rules of the saved fields first, then the fields.
          try {
            const saved = new Set((dto.fields || []).map((f) => f.name));
            const first = await saveDataTemplateRules(id, {
              baseRevision: dto.revision,
              rules: rules.filter((r) => saved.has(r.fieldName)).map(toDataRuleInput),
            });
            base = await writeFields({ keepRules: true, baseRevision: first.revision });
          } catch (again) {
            failed(again, { fields: true });
            return;
          }
        }
      }
      const known = new Set((base.fields || []).map((f) => f.name));
      const next = await saveDataTemplateRules(id, {
        baseRevision: base.revision,
        rules: rules.filter((r) => known.has(r.fieldName)).map(toDataRuleInput),
      });
      adopt(next);
      toast.success(fieldsDirty ? 'Fields and rules saved' : 'Rules saved');
    } catch (err) {
      failed(err);
    } finally {
      setSaving(false);
    }
  };

  const saveDetails = async (event) => {
    event.preventDefault();
    if (!details.name.trim()) {
      setError('Give the data template a name.');
      return;
    }
    try {
      const next = await updateDataTemplate(id, { name: details.name.trim(), description: details.description.trim() });
      setDto((current) => ({ ...current, ...next, fields: current.fields, rules: current.rules }));
      setDetails({ name: next.name || '', description: next.description || '' });
      toast.success('Details saved');
    } catch (err) {
      setError(err.message);
    }
  };

  const reload = async () => {
    setConflict(false);
    await load();
    toast.success('Loaded the latest version — your unsaved changes were discarded');
  };

  const selectedField = fields.find((f) => f._uid === selected) || null;
  const drawerField = drawer ? { key: drawer.name, label: drawer.label, kind: drawer.kind } : null;

  return (
    <div>
      <PageHeader
        sticky
        crumbs={[{ label: 'Validation', to: '/creation/data-templates' }, { label: dto?.name || `#${id}` }]}
        title={dto?.name || 'Data template'}
        subtitle={
          dto
            ? `${fields.length} field${fields.length === 1 ? '' : 's'} · ${rules.length} rule${rules.length === 1 ? '' : 's'} · revision ${dto.revision ?? 0}`
            : undefined
        }
        actions={
          dto && (
            <>
              <Button variant="secondary" aria-expanded={testOpen} onClick={() => setTestOpen((o) => !o)}>
                Test with sample values
              </Button>
              <Button variant="secondary" onClick={saveFields} disabled={saving || !fieldsDirty}>
                Save fields
              </Button>
              <Button onClick={saveRules} disabled={saving || !rulesDirty}>
                {saving ? 'Saving…' : 'Save rules'}
              </Button>
            </>
          )
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {!dto ? (
        error ? null : <Spinner />
      ) : (
        <div className="space-y-4">
          {dirty && (
            <p role="status" className="text-sm text-amber-800">
              Unsaved changes:{' '}
              {[fieldsDirty && 'fields', rulesDirty && 'rules'].filter(Boolean).join(' and ')}.
              {rulesDirty && fieldsDirty && ' “Save rules” saves the fields first.'}
            </p>
          )}

          <Card>
            <form onSubmit={saveDetails} className="grid grid-cols-1 gap-3 md:grid-cols-[1fr_2fr_auto] md:items-end">
              <Field label="Name">
                <input
                  className={inputClass}
                  value={details.name}
                  maxLength={200}
                  onChange={(e) => setDetails((d) => ({ ...d, name: e.target.value }))}
                />
              </Field>
              <Field label="Description">
                <input
                  className={inputClass}
                  value={details.description}
                  maxLength={1000}
                  onChange={(e) => setDetails((d) => ({ ...d, description: e.target.value }))}
                />
              </Field>
              <Button type="submit" variant="secondary" disabled={!detailsDirty}>
                Save details
              </Button>
            </form>
            <p className="mt-3 text-xs text-muted">
              {dto.sourceTemplateName || dto.sourceTemplateId != null
                ? `Copied from the flow template ${dto.sourceTemplateName || dto.sourceTemplateId}. `
                : 'Made from scratch. '}
              {dto.usedBy?.length ? (
                <>
                  Used by:{' '}
                  {dto.usedBy.map((b, i) => (
                    <span key={b.blueprintId}>
                      {i > 0 && ', '}
                      <Link to={`/creation/blueprints/${b.blueprintId}?step=mapping`} className="text-accent hover:underline">
                        {b.name}
                      </Link>
                    </span>
                  ))}
                  . Saving changes here re-checks their documents.
                </>
              ) : (
                'No PDF template uses it yet.'
              )}
            </p>
          </Card>

          {testOpen && (
            <Card padding="compact">
              <RulesTestPanel
                photoAsCheckbox
                fields={asRuleFields.filter((f) => (dto.fields || []).some((s) => s.name === f.key))}
                dirty={rulesDirty}
                onTest={(values) =>
                  testDataTemplateRules(id, { values, rules: rulesDirty ? rules.map(toDataRuleInput) : undefined })
                }
              />
            </Card>
          )}

          <section aria-labelledby={`${ids}-fields`} className="rounded-lg border border-line bg-white p-3">
            <div className="mb-2 flex flex-wrap items-center gap-2">
              <h2 id={`${ids}-fields`} className="text-base font-medium">
                Fields
              </h2>
              <span className="flex-1" />
              <Button size="sm" onClick={addField} disabled={fields.length >= 500}>
                + Add field
              </Button>
            </div>
            {fields.length === 0 ? (
              <p className="text-sm text-muted">No fields yet — use “+ Add field”.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <caption className="sr-only">Fields of {dto.name}</caption>
                  <thead className="text-xs text-muted">
                    <tr>
                      <th className="p-1 font-medium">Name</th>
                      <th className="p-1 font-medium">Label</th>
                      <th className="p-1 font-medium">Kind</th>
                      <th className="p-1 font-medium">PII</th>
                      <th className="p-1 font-medium">Required</th>
                      <th className="p-1 font-medium">Rules</th>
                      <th className="p-1 font-medium">
                        <span className="sr-only">Actions</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {fields.map((f, i) => {
                      const name = f.label || f.name;
                      const own = rulesOf(f.name);
                      const problem = tried || !f._new ? problems[i] : '';
                      return (
                        <tr
                          key={f._uid}
                          data-testid={`data-field-${f.name}`}
                          className={`border-t border-line align-top ${selected === f._uid ? 'bg-pill/40' : ''}`}
                        >
                          <td className="p-1">
                            <span className="font-mono text-xs">{f.name}</span>
                            {problem && (
                              <p className="text-xs text-danger-ink" role="alert">
                                {problem}
                              </p>
                            )}
                          </td>
                          <td className="p-1">
                            <input
                              id={`${ids}-label-${f._uid}`}
                              className={`${inputClass} !py-1`}
                              aria-label={`Label of ${f.name}`}
                              value={f.label || ''}
                              maxLength={LIMITS.label}
                              onChange={(e) => updateField(f._uid, { label: e.target.value })}
                            />
                          </td>
                          <td className="p-1">
                            <select
                              className={`${inputClass} !py-1`}
                              aria-label={`Kind of ${name}`}
                              value={f.kind}
                              onChange={(e) => updateField(f._uid, { kind: e.target.value })}
                            >
                              {DATA_FIELD_KINDS.map((k) => (
                                <option key={k} value={k}>
                                  {KIND_LABELS[k] || k}
                                </option>
                              ))}
                            </select>
                          </td>
                          <td className="p-1">
                            <select
                              className={`${inputClass} !py-1`}
                              aria-label={`PII of ${name}`}
                              value={f.piiKind || ''}
                              onChange={(e) => updateField(f._uid, { piiKind: e.target.value || null })}
                            >
                              {PII_KINDS.map((k) => (
                                <option key={k.value} value={k.value}>
                                  {k.label}
                                </option>
                              ))}
                            </select>
                          </td>
                          <td className="p-1">
                            <Switch
                              checked={hasRequired(f.name)}
                              label={`Required: ${name}`}
                              onChange={(on) => setRequired(f.name, on)}
                            />
                          </td>
                          <td className="p-1">
                            {own.length === 0 ? (
                              <span className="text-xs text-muted">No rules</span>
                            ) : (
                              <ul className="flex flex-wrap gap-1" aria-label={`Rules of ${name}`}>
                                {own.map((r) => (
                                  <li key={r._id}>
                                    <Tag tone={r.severity === 'WARNING' ? 'warning' : 'info'}>{ruleSummary(r, nameOf)}</Tag>
                                  </li>
                                ))}
                              </ul>
                            )}
                          </td>
                          <td className="whitespace-nowrap p-1 text-right">
                            <button
                              type="button"
                              className="mr-2 text-xs text-accent hover:underline"
                              aria-label={`Edit rules of ${name}`}
                              onClick={() => setDrawer({ name: f.name, label: f.label, kind: f.kind })}
                            >
                              Edit rules
                            </button>
                            <button
                              type="button"
                              className="mr-2 text-xs text-accent hover:underline"
                              aria-label={`Properties of ${name}`}
                              aria-pressed={selected === f._uid}
                              onClick={() => setSelected((s) => (s === f._uid ? null : f._uid))}
                            >
                              Properties
                            </button>
                            <button
                              type="button"
                              className="text-xs text-danger-ink hover:underline"
                              aria-label={`Delete ${name}`}
                              onClick={() => setDeleting(f)}
                            >
                              Delete
                            </button>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          {selectedField && (
            <section aria-labelledby={`${ids}-props`} className="rounded-lg border border-line bg-white p-3" data-testid="field-properties">
              <h2 id={`${ids}-props`} className="mb-2 text-base font-medium">
                Properties: {selectedField.label || selectedField.name}
              </h2>
              <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                <Field label="Name" hint="Lower-case letters, digits and _; the key the PDF fields map to">
                  <input
                    className={`${inputClass} font-mono`}
                    value={selectedField.name}
                    maxLength={LIMITS.key}
                    onChange={(e) => updateField(selectedField._uid, { name: e.target.value })}
                  />
                </Field>
                {selectedField.sourceFieldName && (
                  <p className="self-end text-xs text-muted">Copied from the flow field {selectedField.sourceFieldName}.</p>
                )}
              </div>
              <div className="mt-3 space-y-3">
                <FieldInputProperties field={selectedField} onChange={(patch) => updateField(selectedField._uid, patch)} />
              </div>
            </section>
          )}
        </div>
      )}

      <FieldRulesDrawer
        open={Boolean(drawer)}
        field={drawerField}
        fields={asRuleFields}
        rules={drawer ? rulesOf(drawer.name) : []}
        ruleTypes={ruleTypes}
        onSave={saveDrawer}
        onClose={() => setDrawer(null)}
      />

      <ConfirmDialog
        open={Boolean(deleting)}
        title="Delete this field?"
        confirmLabel="Delete field"
        tone="danger"
        onConfirm={removeField}
        onCancel={() => setDeleting(null)}
      >
        Delete “{deleting?.label || deleting?.name}” and its rules?
        {deleting && rulesGoingWith(deleting.name).some((r) => r.fieldName !== deleting.name) && (
          <>
            {' '}
            Rules of other fields that compare with it or depend on it (
            {rulesGoingWith(deleting.name)
              .filter((r) => r.fieldName !== deleting.name)
              .map((r) => nameOf(r.fieldName))
              .join(', ')}
            ) go too.
          </>
        )}{' '}
        PDF fields mapped to it lose their mapping when you save the fields.
      </ConfirmDialog>

      <Modal
        open={conflict}
        onClose={() => setConflict(false)}
        title="This data template was changed elsewhere"
        footer={
          <>
            <Button variant="secondary" onClick={() => setConflict(false)}>
              Keep editing
            </Button>
            <Button onClick={reload}>Reload the latest version</Button>
          </>
        }
      >
        <p>
          Someone saved this data template after you opened it, so your changes were not saved. Reload to see the
          latest version (your unsaved changes are discarded), or keep editing to copy what you need first.
        </p>
      </Modal>
    </div>
  );
}

