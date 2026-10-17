import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import {
  DATA_FIELD_KINDS,
  deleteDataTemplate,
  getDataTemplate,
  listDataTemplates,
  saveDataTemplateFields,
  saveDataTemplateRules,
  testDataTemplateRules,
  toDataRuleInput,
  toFieldInput,
  updateDataTemplate,
} from '../api/dataTemplates';
import ConfirmDialog from '../components/ConfirmDialog';
import DataTable from '../components/DataTable';
import Modal from '../components/Modal';
import PageHeader from '../components/PageHeader';
import RegexBuilder from '../components/RegexBuilder';
import BuildFromFlowDialog from '../components/dataTemplates/BuildFromFlowDialog';
import { NewDataTemplateDialog } from '../components/dataTemplates/DataTemplateDialogs';
import { DATA_KIND_LABELS, PII_KINDS } from '../components/dataTemplates/dataFieldKinds';
import {
  applyRuleForm,
  isRequired,
  renameRules,
  ruleFits,
  ruleOptionsFor,
  ruleRows,
  rulesGoingWith,
  sourceText,
} from '../components/dataTemplates/dataRules';
import { OptionsEditor, optionsProblem } from '../components/pdf/RegionPropertiesPanel';
import { KEY_PATTERN, uniqueKey } from '../components/pdf/geometry';
import { DATE_FORMATS, LIMITS } from '../components/pdf/limits';
import { cleanConfig, configProblem } from '../components/rules/RuleDialog';
import RulesTestPanel from '../components/rules/RulesTestPanel';
import { DEFAULT_RULE_TYPES, OPERATORS, RULE_LABELS, ruleSummary } from '../components/rules/ruleText';
import { Button, Callout, Card, ErrorBanner, Field, Spinner, inputClass } from '../components/ui';
import { useToast } from '../context/ToastContext';
import useUnsavedGuard from '../hooks/useUnsavedGuard';

const RULE_HINTS = {
  EMAIL: 'The value must be a valid email address (e.g. jane@acme.com).',
  EXPIRY_DATE: 'When the value is a date, it must be in the future.',
  DATE_FORMAT: 'None ticked = yyyy-MM-dd.',
};

const EMPTY_FORM = {
  fieldName: '',
  requirement: 'optional',
  ruleType: 'NONE',
  config: {},
  valuesText: '',
  message: '',
  warningOnly: false,
};

let uidCounter = 0;
const draftField = (field, saved = true) => {
  uidCounter += 1;
  return { ...field, _uid: `f${uidCounter}`, _orig: saved ? field.name : null, _new: !saved };
};

const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;

/** How many PDF templates use a data template (a summary carries a count, the DTO a list). */
export function usedByCount(usedBy) {
  if (Array.isArray(usedBy)) return usedBy.length;
  return Number(usedBy) || 0;
}

/** The problem with one draft field, or ''. `rules` are the saved rules (keyed by the saved name). */
export function dataFieldProblem(field, fields, { rules = [], ruleTypes = [] } = {}) {
  if (!KEY_PATTERN.test(field.name || '')) return 'Use 1–64 lower-case letters, digits or _ for the name';
  if (fields.some((f) => f !== field && f.name === field.name)) return 'Another field already uses this name';
  if (!field.label?.trim()) return 'Give the field a label';
  // The server refuses a kind change that one of the field's rules no longer fits.
  const savedName = field._orig ?? field.name;
  const unfit = rules.filter(
    (r) => r.fieldName === savedName && ruleTypes.some((t) => t.type === r.ruleType) && !ruleFits(ruleTypes, r.ruleType, field.kind)
  );
  if (unfit.length) {
    const kind = DATA_KIND_LABELS[field.kind] || field.kind;
    return `${unfit.length === 1 ? 'A rule does' : `${unfit.length} rules do`} not fit a ${kind} field — remove ${
      unfit.length === 1 ? 'it' : 'them'
    } in the rules table`;
  }
  return optionsProblem(field);
}

/**
 * Document Creation step 2, "Validation" (data-templates contract §6): one
 * page with the same controls as Document Flow → Set Field Rules. Choose a
 * data template (`?template=`), edit its fields, add or update field rules
 * (each saves the rule set at once), and test a sample row. These templates
 * are only used in Document Creation.
 */
export default function DataValidationPage() {
  const toast = useToast();
  const navigate = useNavigate();
  const ids = useId();
  const formRef = useRef(null);
  const [searchParams, setSearchParams] = useSearchParams();
  const templateId = searchParams.get('template') || '';

  const [summaries, setSummaries] = useState(null);
  const [dto, setDto] = useState(null);
  const [error, setError] = useState('');
  const [conflict, setConflict] = useState(false);

  const [fields, setFields] = useState([]);
  const [fieldsDirty, setFieldsDirty] = useState(false);
  const [tried, setTried] = useState(false);
  const [savingFields, setSavingFields] = useState(false);
  const [deletingField, setDeletingField] = useState(null);
  const [focusUid, setFocusUid] = useState(null);

  const [form, setForm] = useState(EMPTY_FORM);
  const [editing, setEditing] = useState(null);
  const [ruleBusy, setRuleBusy] = useState(false);
  const [deletingRule, setDeletingRule] = useState(null);

  const [newOpen, setNewOpen] = useState(false);
  const [buildOpen, setBuildOpen] = useState(false);
  const [renameOpen, setRenameOpen] = useState(false);
  const [rename, setRename] = useState({ name: '', description: '' });
  const [deletingTemplate, setDeletingTemplate] = useState(false);
  const [busy, setBusy] = useState(false);

  const loadSummaries = useCallback(async () => {
    try {
      setSummaries((await listDataTemplates()) || []);
    } catch (err) {
      setSummaries([]);
      setError(err.message);
    }
  }, []);

  useEffect(() => {
    loadSummaries();
  }, [loadSummaries]);

  const adopt = useCallback((next, { keepFields = false } = {}) => {
    setDto(next);
    if (!keepFields) {
      setFields([...(next.fields || [])].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0)).map((f) => draftField(f)));
      setFieldsDirty(false);
      setTried(false);
    }
  }, []);

  const resetForm = () => {
    setForm(EMPTY_FORM);
    setEditing(null);
  };

  useEffect(() => {
    setDto(null);
    setFields([]);
    setFieldsDirty(false);
    setTried(false);
    setForm(EMPTY_FORM);
    setEditing(null);
    if (!templateId) return undefined;
    let live = true;
    getDataTemplate(templateId)
      .then((next) => live && adopt(next))
      .catch((err) => live && setError(err.message));
    return () => {
      live = false;
    };
  }, [templateId, adopt]);

  useEffect(() => {
    if (!focusUid) return;
    const input = document.getElementById(`${ids}-label-${focusUid}`);
    if (input) {
      input.focus();
      setFocusUid(null);
    }
  });

  useUnsavedGuard(fieldsDirty, 'You have unsaved changes to the fields. Leave without saving them?');

  const ruleTypes = dto?.ruleTypes?.length ? dto.ruleTypes : DEFAULT_RULE_TYPES;
  const rules = useMemo(() => dto?.rules || [], [dto]);
  const savedFields = useMemo(
    () => [...(dto?.fields || [])].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0)),
    [dto]
  );
  const labelOf = (name) => savedFields.find((f) => f.name === name)?.label || name;
  const problems = fields.map((f) => dataFieldProblem(f, fields, { rules, ruleTypes }));
  const blocking = problems.some(Boolean);
  const usedBy = dto?.usedBy || [];

  // ------------------------------------------------------------ templates

  const select = (id) => {
    if (fieldsDirty && !window.confirm('Discard your unsaved changes to the fields?')) return;
    setError('');
    setSearchParams(id ? { template: String(id) } : {});
  };

  const created = (next, message) => {
    toast.success(message);
    loadSummaries();
    if (String(next.id) === templateId) adopt(next);
    else setSearchParams({ template: String(next.id) });
  };

  const saveRename = async (event) => {
    event.preventDefault();
    if (!rename.name.trim()) return;
    setBusy(true);
    try {
      const next = await updateDataTemplate(dto.id, { name: rename.name.trim(), description: rename.description.trim() });
      setDto((current) => ({ ...current, name: next?.name ?? rename.name.trim(), description: next?.description ?? rename.description.trim() }));
      setRenameOpen(false);
      toast.success(`Renamed to ${next?.name ?? rename.name.trim()}`);
      loadSummaries();
    } catch (err) {
      setRenameOpen(false);
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const removeTemplate = async () => {
    setBusy(true);
    try {
      await deleteDataTemplate(dto.id);
      toast.success(`Deleted ${dto.name}`);
      setDeletingTemplate(false);
      setFieldsDirty(false);
      setSearchParams({});
      loadSummaries();
    } catch (err) {
      setDeletingTemplate(false);
      if (err.status === 409) {
        const n = usedBy.length || Number(/\d+/.exec(err.message || '')?.[0]) || 0;
        setError(`“${dto.name}” can’t be deleted: it is used by ${plural(n, 'PDF template')}.`);
      } else setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const reload = async () => {
    setConflict(false);
    try {
      adopt(await getDataTemplate(templateId));
      resetForm();
      toast.success('Loaded the latest version — your unsaved changes were discarded');
    } catch (err) {
      setError(err.message);
    }
  };

  // --------------------------------------------------------------- fields

  const changeFields = (fn) => {
    setFields(fn);
    setFieldsDirty(true);
  };

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
    if ('kind' in patch) {
      if (next.kind === 'CHOICE' && !next.props?.options) next.props = { ...(next.props || {}), options: [] };
      if (next.kind !== 'CHOICE' && next.props?.options) {
        const { options, ...rest } = next.props; // eslint-disable-line no-unused-vars
        next.props = Object.keys(rest).length ? rest : null;
      }
    }
    changeFields((list) => list.map((f) => (f._uid === uid ? next : f)));
  };

  const addField = () => {
    const taken = new Set(fields.map((f) => f.name));
    const field = draftField(
      { name: uniqueKey('new_field', taken), label: 'New field', kind: 'TEXT', piiKind: null, props: null },
      false
    );
    changeFields((list) => [...list, field]);
    setFocusUid(field._uid);
  };

  const removeField = () => {
    const field = deletingField;
    changeFields((list) => list.filter((f) => f._uid !== field._uid));
    setDeletingField(null);
    toast.success(`Removed “${field.label || field.name}” — save the fields to keep it removed`);
  };

  const failed = (err, { savingFieldSet = false } = {}) => {
    if (err.status === 409) setConflict(true);
    // A kind change that a field's rules no longer fit is refused (400): say how to get past it.
    else if (savingFieldSet && err.status === 400 && /rule/i.test(err.message || '')) {
      setError(`${err.message} Remove the rules that no longer fit (in the rules table), then save the fields again.`);
    } else setError(err.message);
  };

  const saveFields = async () => {
    setTried(true);
    if (blocking) {
      setError('Fix the problems shown in the fields table first.');
      return;
    }
    setSavingFields(true);
    setError('');
    try {
      let next = await saveDataTemplateFields(dto.id, { baseRevision: dto.revision, fields: fields.map(toFieldInput) });
      // A renamed field is a removed field to the server (its rules go): carry them over.
      const renames = new Map(fields.filter((f) => f._orig && f._orig !== f.name).map((f) => [f._orig, f.name]));
      const carried = rules.some(
        (r) => renames.has(r.fieldName) || ((r.ruleType === 'REQUIRED_IF' || r.ruleType === 'COMPARE') && renames.has(r.config?.Field))
      );
      if (carried) {
        const names = new Set(fields.map((f) => f.name));
        next = await saveDataTemplateRules(dto.id, {
          baseRevision: next.revision,
          rules: renameRules(rules, renames, names).map(toDataRuleInput),
        });
      }
      adopt(next);
      resetForm();
      toast.success('Fields saved');
      loadSummaries();
    } catch (err) {
      failed(err, { savingFieldSet: true });
    } finally {
      setSavingFields(false);
    }
  };

  // ---------------------------------------------------------------- rules

  const writeRules = async (list, message) => {
    setRuleBusy(true);
    setError('');
    try {
      const next = await saveDataTemplateRules(dto.id, { baseRevision: dto.revision, rules: list.map(toDataRuleInput) });
      adopt(next, { keepFields: fieldsDirty });
      toast.success(message);
      return true;
    } catch (err) {
      failed(err);
      return false;
    } finally {
      setRuleBusy(false);
    }
  };

  const formField = savedFields.find((f) => f.name === form.fieldName) || null;
  const fitting = ruleOptionsFor(ruleTypes, formField?.kind);
  const typeOptions =
    fitting.some((o) => o.value === form.ruleType) || form.ruleType === 'NONE'
      ? fitting
      : [...fitting, { value: form.ruleType, label: `${RULE_LABELS[form.ruleType] || form.ruleType} (does not fit this field)` }];
  const setConfig = (key, value) => setForm((f) => ({ ...f, config: { ...f.config, [key]: value } }));
  const effective =
    form.ruleType === 'ALLOWED_VALUES'
      ? { ...form.config, Values: form.valuesText.split('\n').map((v) => v.trim()).filter(Boolean) }
      : form.config;
  const formProblem = form.ruleType === 'NONE' ? '' : configProblem(form.ruleType, effective);
  const editingFormat = Boolean(editing) && editing.ruleType !== 'REQUIRED';
  const nothingToSave =
    form.ruleType === 'NONE' && form.requirement === 'optional' && !isRequired(rules, form.fieldName) && !editingFormat;
  const saveDisabled = ruleBusy || !form.fieldName || nothingToSave || Boolean(formProblem);
  const saveDisabledReason =
    ruleBusy || !form.fieldName
      ? ''
      : formProblem ||
        (nothingToSave ? 'Mark the field Required or pick a format rule — there is nothing to save yet.' : '');

  const changeField = (name) => {
    const kind = savedFields.find((f) => f.name === name)?.kind;
    setForm((f) => {
      const keepType = f.ruleType === 'NONE' || (kind && ruleFits(ruleTypes, f.ruleType, kind));
      return {
        ...f,
        fieldName: name,
        requirement: editing ? f.requirement : isRequired(rules, name) ? 'required' : 'optional',
        ...(keepType ? {} : { ruleType: 'NONE', config: {}, valuesText: '' }),
      };
    });
  };

  const changeType = (type) => setForm((f) => ({ ...f, ruleType: type, config: {}, valuesText: '' }));

  const loadRule = (rule) => {
    const required = rule.ruleType === 'REQUIRED';
    setForm({
      fieldName: rule.fieldName,
      requirement: isRequired(rules, rule.fieldName) ? 'required' : 'optional',
      ruleType: required ? 'NONE' : rule.ruleType,
      config: required ? {} : { ...(rule.config || {}) },
      valuesText: (rule.config?.Values || []).join('\n'),
      message: rule.message || '',
      warningOnly: rule.severity === 'WARNING',
    });
    setEditing(rule);
    formRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  };

  const submitRule = async (event) => {
    event.preventDefault();
    if (saveDisabled) return;
    const list = applyRuleForm(
      rules,
      {
        fieldName: form.fieldName,
        requirement: form.requirement,
        ruleType: form.ruleType,
        config: form.ruleType === 'NONE' ? {} : cleanConfig(form.ruleType, effective),
        message: form.message,
        severity: form.warningOnly ? 'WARNING' : 'ERROR',
      },
      editing
    );
    const label = labelOf(form.fieldName);
    if (await writeRules(list, editing ? `Rules updated for ${label}` : `Rules saved for ${label}`)) resetForm();
  };

  const removeRule = async () => {
    const rule = deletingRule;
    setDeletingRule(null);
    if (await writeRules(rules.filter((r) => r !== rule), `Rule on ${labelOf(rule.fieldName)} deleted`)) {
      if (editing === rule) resetForm();
    }
  };

  // ----------------------------------------------------------------- render

  const knownInList = (summaries || []).some((s) => String(s.id) === templateId);
  const fieldName = (f) => f.label || f.name;
  const describe = (rule) => `${RULE_LABELS[rule.ruleType] || rule.ruleType} rule of ${labelOf(rule.fieldName)}`;

  const minMax = (label) => (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
      <Field label={`Minimum${label}`}>
        <input type="number" className={inputClass} value={form.config.Min ?? ''} onChange={(e) => setConfig('Min', e.target.value)} />
      </Field>
      <Field label={`Maximum${label}`}>
        <input type="number" className={inputClass} value={form.config.Max ?? ''} onChange={(e) => setConfig('Max', e.target.value)} />
      </Field>
    </div>
  );

  const dateInput = (key, label) => (
    <Field label={label} hint="yyyy-mm-dd, today, or relative: +30d, -18y">
      <input className={inputClass} value={form.config[key] ?? ''} onChange={(e) => setConfig(key, e.target.value.trim())} />
    </Field>
  );

  const others = savedFields.filter((f) => f.name !== form.fieldName);

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Validation' }]}
        title="Validation"
        subtitle="Step 2 · the fields a document collects and the rules they must pass (Document Creation only)"
        actions={
          <Button variant="secondary" onClick={() => navigate('/creation/mapping')}>
            Next: map fields →
          </Button>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      <div className="mb-5 flex flex-wrap items-end gap-2">
        <div className="w-full max-w-sm">
          <Field label="Template">
            <select className={inputClass} value={templateId} disabled={!summaries} onChange={(e) => select(e.target.value)}>
              <option value="">{summaries ? 'Select a template…' : 'Loading…'}</option>
              {(summaries || []).map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
              {templateId && !knownInList && <option value={templateId}>{dto?.name || `Template ${templateId}`}</option>}
            </select>
          </Field>
        </div>
        <Button variant="secondary" onClick={() => setNewOpen(true)}>
          New template
        </Button>
        <Button variant="secondary" onClick={() => setBuildOpen(true)}>
          Build from Document Flow templates
        </Button>
        {dto && (
          <>
            <Button
              variant="secondary"
              onClick={() => {
                setRename({ name: dto.name || '', description: dto.description || '' });
                setRenameOpen(true);
              }}
            >
              Rename
            </Button>
            <Button variant="danger" onClick={() => setDeletingTemplate(true)}>
              Delete
            </Button>
          </>
        )}
      </div>

      {!templateId ? (
        <Callout variant="info" title="Choose a template">
          Pick a template above, start a new one, or build one from the fields and rules of Document Flow templates.
          These templates are only used in Document Creation; Document Flow’s field rules stay as they are.
        </Callout>
      ) : !dto ? (
        error ? null : <Spinner />
      ) : (
        <div className="space-y-5">
          <p className="text-sm text-muted" data-testid="used-by">
            {usedBy.length ? (
              <>
                Used by {plural(usedBy.length, 'PDF template')}:{' '}
                {usedBy.map((b, i) => (
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
              'Used by 0 PDF templates.'
            )}
            {dto.description ? ` ${dto.description}` : ''}
          </p>

          {/* ------------------------------------------------------ Fields */}
          <Card>
            <div className="mb-3 flex flex-wrap items-center gap-2">
              <h2 className="text-lg font-normal">Fields</h2>
              {fieldsDirty && (
                <span role="status" className="text-sm text-amber-800">
                  Unsaved changes to the fields.
                </span>
              )}
              <span className="flex-1" />
              <Button size="sm" variant="secondary" onClick={addField} disabled={fields.length >= 500}>
                + Add field
              </Button>
              <Button size="sm" onClick={saveFields} disabled={savingFields || !fieldsDirty}>
                {savingFields ? 'Saving…' : 'Save fields'}
              </Button>
            </div>
            {fields.length === 0 ? (
              <p className="text-sm text-muted">No fields yet — use “+ Add field”, or build from Document Flow templates.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <caption className="sr-only">Fields of {dto.name}</caption>
                  <thead className="text-xs text-muted">
                    <tr>
                      <th scope="col" className="p-1 font-medium">Field name</th>
                      <th scope="col" className="p-1 font-medium">Label</th>
                      <th scope="col" className="p-1 font-medium">Type</th>
                      <th scope="col" className="p-1 font-medium">PII</th>
                      <th scope="col" className="p-1 font-medium">
                        <span className="sr-only">Delete</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {fields.map((f, i) => {
                      const name = fieldName(f);
                      const problem = tried || !f._new ? problems[i] : '';
                      const from = f.sourceFieldName
                        ? `From ${f.sourceTemplateName || dto.sourceTemplateName || 'a flow template'}.${f.sourceFieldName}`
                        : '';
                      return [
                        <tr key={f._uid} data-testid={`data-field-${f.name}`} className="border-t border-line align-top">
                          <td className="p-1">
                            <input
                              className={`${inputClass} !py-1 font-mono`}
                              aria-label={`Field name of ${name}`}
                              value={f.name}
                              maxLength={LIMITS.key}
                              onChange={(e) => updateField(f._uid, { name: e.target.value })}
                            />
                            {from && <p className="mt-0.5 text-xs text-muted">{from}</p>}
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
                              aria-label={`Type of ${name}`}
                              value={f.kind}
                              onChange={(e) => updateField(f._uid, { kind: e.target.value })}
                            >
                              {DATA_FIELD_KINDS.map((k) => (
                                <option key={k} value={k}>
                                  {DATA_KIND_LABELS[k] || k}
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
                          <td className="whitespace-nowrap p-1 text-right">
                            <Button size="sm" variant="danger" aria-label={`Delete ${name}`} onClick={() => setDeletingField(f)}>
                              Delete
                            </Button>
                          </td>
                        </tr>,
                        f.kind === 'CHOICE' && (
                          <tr key={`${f._uid}-options`} data-testid={`field-options-${f.name}`}>
                            <td colSpan={5} className="px-1 pb-3">
                              <div className="ml-4 max-w-md rounded border border-line p-2">
                                <OptionsEditor
                                  options={f.props?.options || []}
                                  onChange={(list) => updateField(f._uid, { props: { ...(f.props || {}), options: list } })}
                                />
                              </div>
                            </td>
                          </tr>
                        ),
                      ];
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          {/* ------------------------------------------ Add/Update field rules */}
          <div ref={formRef}>
            <Card>
              <h2 className="mb-3 text-lg font-normal">{editing ? 'Update field rules' : 'Add field rules'}</h2>
              {fieldsDirty && (
                <p className="mb-3 text-sm text-muted">Save the fields first to add rules to new or renamed fields.</p>
              )}
              <form onSubmit={submitRule} className="space-y-4" noValidate>
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                  <Field label="Field">
                    <select className={inputClass} value={form.fieldName} onChange={(e) => changeField(e.target.value)}>
                      <option value="">Select a field…</option>
                      {savedFields.map((f) => (
                        <option key={f.name} value={f.name}>
                          {f.label && f.label !== f.name ? `${f.label} (${f.name})` : f.name}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="Required or Optional">
                    <select
                      className={inputClass}
                      value={form.requirement}
                      onChange={(e) => setForm((f) => ({ ...f, requirement: e.target.value }))}
                    >
                      <option value="required">Required</option>
                      <option value="optional">Optional</option>
                    </select>
                  </Field>
                  <Field label="Format rule type">
                    <select className={inputClass} value={form.ruleType} onChange={(e) => changeType(e.target.value)}>
                      {typeOptions.map(({ value, label }) => (
                        <option key={value} value={value}>
                          {label}
                        </option>
                      ))}
                    </select>
                  </Field>
                </div>

                {form.requirement === 'required' && (
                  <p className="text-sm text-muted">The field must contain a value — an empty field fails validation.</p>
                )}
                {RULE_HINTS[form.ruleType] && <p className="text-sm text-muted">{RULE_HINTS[form.ruleType]}</p>}

                {form.ruleType === 'REGEX' && (
                  <RegexBuilder pattern={form.config.Pattern || ''} onChange={(pattern) => setConfig('Pattern', pattern)} />
                )}
                {form.ruleType === 'RANGE' && minMax('')}
                {form.ruleType === 'LENGTH' && minMax(' length')}
                {form.ruleType === 'ALLOWED_VALUES' && (
                  <div className="space-y-2">
                    <Field label="Allowed values" hint="One per line">
                      <textarea
                        className={inputClass}
                        rows={4}
                        value={form.valuesText}
                        onChange={(e) => setForm((f) => ({ ...f, valuesText: e.target.value }))}
                      />
                    </Field>
                    <label className="flex items-center gap-2 text-sm">
                      <input
                        type="checkbox"
                        checked={Boolean(form.config.IgnoreCase)}
                        onChange={(e) => setConfig('IgnoreCase', e.target.checked)}
                      />
                      Ignore upper / lower case
                    </label>
                  </div>
                )}
                {form.ruleType === 'DATE_FORMAT' && (
                  <fieldset>
                    <legend className="mb-1 text-sm font-medium">Accepted formats</legend>
                    <div className="grid grid-cols-2 gap-1 sm:grid-cols-4">
                      {DATE_FORMATS.map((format) => {
                        const formats = form.config.Formats || [];
                        return (
                          <label key={format} className="flex items-center gap-2 text-sm">
                            <input
                              type="checkbox"
                              checked={formats.includes(format)}
                              onChange={(e) =>
                                setConfig('Formats', e.target.checked ? [...formats, format] : formats.filter((x) => x !== format))
                              }
                            />
                            {format}
                          </label>
                        );
                      })}
                    </div>
                  </fieldset>
                )}
                {form.ruleType === 'DATE_RANGE' && (
                  <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                    {dateInput('Min', 'Earliest date')}
                    {dateInput('Max', 'Latest date')}
                  </div>
                )}
                {(form.ruleType === 'REQUIRED_IF' || form.ruleType === 'COMPARE') && (
                  <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                    <Field label="Other field">
                      <select className={inputClass} value={form.config.Field || ''} onChange={(e) => setConfig('Field', e.target.value)}>
                        <option value="">Choose a field…</option>
                        {others.map((f) => (
                          <option key={f.name} value={f.name}>
                            {f.label || f.name}
                          </option>
                        ))}
                      </select>
                    </Field>
                    {form.ruleType === 'REQUIRED_IF' ? (
                      <Field label="Only when it equals" hint="Optional; empty = whenever it is filled">
                        <input className={inputClass} value={form.config.Equals ?? ''} onChange={(e) => setConfig('Equals', e.target.value)} />
                      </Field>
                    ) : (
                      <Field label="This value must be">
                        <select className={inputClass} value={form.config.Op || ''} onChange={(e) => setConfig('Op', e.target.value)}>
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

                <div className="grid grid-cols-1 gap-4 sm:grid-cols-3 sm:items-end">
                  <div className="sm:col-span-2">
                    <Field label="Custom message" hint="Optional; empty = the default message. Never include the value.">
                      <input
                        className={inputClass}
                        value={form.message}
                        maxLength={LIMITS.ruleMessage}
                        onChange={(e) => setForm((f) => ({ ...f, message: e.target.value }))}
                      />
                    </Field>
                  </div>
                  <label className="flex items-center gap-2 pb-2 text-sm">
                    <input
                      type="checkbox"
                      checked={form.warningOnly}
                      onChange={(e) => setForm((f) => ({ ...f, warningOnly: e.target.checked }))}
                    />
                    Warning only
                  </label>
                </div>

                <div className="flex flex-wrap items-center gap-2">
                  <Button type="submit" disabled={saveDisabled}>
                    {ruleBusy ? 'Saving…' : editing ? 'Update rule' : 'Save rule'}
                  </Button>
                  {editing && (
                    <Button type="button" variant="secondary" onClick={resetForm}>
                      Cancel
                    </Button>
                  )}
                  {saveDisabledReason && <span className="text-xs text-muted">{saveDisabledReason}</span>}
                </div>
              </form>
            </Card>
          </div>

          {/* ------------------------------------------------------- Rules */}
          <section aria-labelledby={`${ids}-rules`}>
            <h2 id={`${ids}-rules`} className="mb-3 text-xl font-normal">
              Rules
            </h2>
            <DataTable
              caption={`Rules of ${dto.name}`}
              maxHeight="45vh"
              rows={ruleRows(rules, savedFields)}
              emptyMessage="No rules for this template yet."
              columns={[
                { key: 'field', label: 'Field', render: (r) => labelOf(r.fieldName) },
                { key: 'required', label: 'Required', render: (r) => (isRequired(rules, r.fieldName) ? 'Yes' : 'No') },
                { key: 'rule', label: 'Rule', render: (r) => ruleSummary(r, labelOf) },
                {
                  key: 'settings',
                  label: 'Settings',
                  render: (r) => (
                    <span className="text-sm text-muted">
                      {[r.severity === 'WARNING' ? 'Warning only' : 'Error', r.message ? `Message: “${r.message}”` : null]
                        .filter(Boolean)
                        .join(' · ')}
                    </span>
                  ),
                },
                { key: 'source', label: 'Source', render: (r) => sourceText(r) },
                {
                  key: 'update',
                  label: '',
                  srLabel: 'Update',
                  render: (r) => (
                    <Button size="sm" variant="secondary" aria-label={`Update ${describe(r)}`} onClick={() => loadRule(r)}>
                      Update
                    </Button>
                  ),
                },
                {
                  key: 'delete',
                  label: '',
                  srLabel: 'Delete',
                  render: (r) => (
                    <Button size="sm" variant="danger" aria-label={`Delete ${describe(r)}`} onClick={() => setDeletingRule(r)}>
                      Delete
                    </Button>
                  ),
                },
              ]}
            />
          </section>

          {/* ---------------------------------------------- Test a sample row */}
          <Card>
            <h2 className="mb-1 text-lg font-normal">Test a sample row</h2>
            <RulesTestPanel
              key={dto.id}
              photoAsCheckbox
              fields={savedFields.map((f) => ({ key: f.name, label: f.label, kind: f.kind }))}
              onTest={(values) => testDataTemplateRules(dto.id, { values })}
            />
          </Card>
        </div>
      )}

      <NewDataTemplateDialog
        open={newOpen}
        onClose={() => setNewOpen(false)}
        onCreated={(next) => {
          setNewOpen(false);
          created(next, `Created ${next.name} — now add its fields`);
        }}
      />
      <BuildFromFlowDialog
        open={buildOpen}
        current={dto}
        onClose={() => setBuildOpen(false)}
        onConflict={() => {
          setBuildOpen(false);
          setConflict(true);
        }}
        onSaved={(next, { added, count }) => {
          setBuildOpen(false);
          created(next, added ? `Added ${plural(count, 'field')} to ${next.name}` : `Saved ${next.name} with ${plural(count, 'field')}`);
        }}
      />
      <Modal
        open={renameOpen}
        onClose={() => setRenameOpen(false)}
        title="Rename template"
        footer={
          <>
            <Button variant="secondary" onClick={() => setRenameOpen(false)} disabled={busy}>
              Cancel
            </Button>
            <Button type="submit" form={`${ids}-rename`} disabled={busy || !rename.name.trim()}>
              Save
            </Button>
          </>
        }
      >
        <form id={`${ids}-rename`} onSubmit={saveRename} className="space-y-3" noValidate>
          <Field label="Name">
            <input
              className={inputClass}
              value={rename.name}
              maxLength={200}
              onChange={(e) => setRename((r) => ({ ...r, name: e.target.value }))}
            />
          </Field>
          <Field label="Description" hint="Optional">
            <input
              className={inputClass}
              value={rename.description}
              maxLength={1000}
              onChange={(e) => setRename((r) => ({ ...r, description: e.target.value }))}
            />
          </Field>
        </form>
      </Modal>
      <ConfirmDialog
        open={deletingTemplate}
        title="Delete this template?"
        confirmLabel="Delete template"
        tone="danger"
        busy={busy}
        onConfirm={removeTemplate}
        onCancel={() => setDeletingTemplate(false)}
      >
        Delete “{dto?.name}” with its fields and rules? A template that a PDF template uses cannot be deleted.
      </ConfirmDialog>
      <ConfirmDialog
        open={Boolean(deletingField)}
        title="Delete this field?"
        confirmLabel="Delete field"
        tone="danger"
        onConfirm={removeField}
        onCancel={() => setDeletingField(null)}
      >
        Delete “{deletingField ? fieldName(deletingField) : ''}” and its rules?
        {deletingField &&
          (() => {
            const saved = deletingField._orig;
            const dependents = saved ? rulesGoingWith(rules, saved).filter((r) => r.fieldName !== saved) : [];
            return dependents.length ? (
              <>
                {' '}
                Rules of other fields that compare with it or depend on it (
                {[...new Set(dependents.map((r) => labelOf(r.fieldName)))].join(', ')}) go too.
              </>
            ) : null;
          })()}{' '}
        PDF fields mapped to it lose their mapping when you save the fields.
      </ConfirmDialog>
      <ConfirmDialog
        open={Boolean(deletingRule)}
        title="Delete this rule?"
        confirmLabel="Delete rule"
        tone="danger"
        onConfirm={removeRule}
        onCancel={() => setDeletingRule(null)}
      >
        Delete “{deletingRule ? ruleSummary(deletingRule, labelOf) : ''}” on {deletingRule ? labelOf(deletingRule.fieldName) : ''}?
      </ConfirmDialog>
      <Modal
        open={conflict}
        onClose={() => setConflict(false)}
        title="This template was changed elsewhere"
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
          Someone saved this template after you opened it, so your change was not saved. Reload to see the latest
          version (your unsaved changes are discarded), or keep editing to copy what you need first.
        </p>
      </Modal>
    </div>
  );
}
