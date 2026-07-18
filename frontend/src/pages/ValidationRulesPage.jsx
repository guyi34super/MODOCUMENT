import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { getTemplates } from '../api/cache';
import { getAllFields } from '../api/templates';
import {
  FIELD_REQUIREMENT_OPTIONS,
  FORMAT_RULE_TYPE_OPTIONS,
  apiRuleType,
  buildRuleConfig,
  createEditSnapshot,
  deleteRule,
  formatRuleConfig,
  formatRuleTypeLabel,
  getRulesByTemplate,
  isFieldRequired,
  loadRuleIntoFormState,
  ruleTypeName,
  shouldSyncRequired,
  upsertRule,
  validateRows,
} from '../api/validationRules';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import RegexBuilder from '../components/RegexBuilder';
import { Button, Callout, Card, ErrorBanner, Field, inputClass, Spinner } from '../components/ui';
import { useToast } from '../context/ToastContext';

const RULE_HINTS = {
  EMAIL: 'The value must be a valid email address (e.g. jane@acme.com).',
  DATE_FORMAT: 'The value must be a date in YYYY-MM-DD format (e.g. 2026-07-18).',
  EXPIRY_DATE: 'When the value is a date, it must be in the future.',
};

const EMPTY_FORM = {
  fieldName: '',
  fieldRequirement: 'optional',
  ruleType: 'NONE',
  pattern: '',
  rangeMin: '',
  rangeMax: '',
};

export default function ValidationRulesPage() {
  const toast = useToast();
  const formRef = useRef(null);
  const [searchParams] = useSearchParams();
  const [templates, setTemplates] = useState([]);
  const [allFields, setAllFields] = useState([]);
  const [templateId, setTemplateId] = useState('');
  const [rules, setRules] = useState(null);
  const [error, setError] = useState('');

  const [fieldName, setFieldName] = useState(EMPTY_FORM.fieldName);
  const [fieldRequirement, setFieldRequirement] = useState(EMPTY_FORM.fieldRequirement);
  const [ruleType, setRuleType] = useState(EMPTY_FORM.ruleType);
  const [pattern, setPattern] = useState(EMPTY_FORM.pattern);
  const [rangeMin, setRangeMin] = useState(EMPTY_FORM.rangeMin);
  const [rangeMax, setRangeMax] = useState(EMPTY_FORM.rangeMax);
  const [editingRule, setEditingRule] = useState(null);
  const [busy, setBusy] = useState(false);

  const [testRow, setTestRow] = useState({});
  const [testResults, setTestResults] = useState(null);

  useEffect(() => {
    Promise.all([getTemplates(), getAllFields()])
      .then(([tpl, fields]) => {
        setTemplates(tpl);
        setAllFields(fields);
        const preselected = searchParams.get('templateId');
        if (preselected && tpl.some((t) => t.templateId === Number(preselected))) {
          setTemplateId(preselected);
          loadRules(preselected);
        }
      })
      .catch((err) => setError(err.message));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const templateFields = allFields.filter((f) => f.templateId === Number(templateId));

  const loadRules = useCallback(async (tid) => {
    setRules(null);
    setTestResults(null);
    setTestRow({});
    if (!tid) return;
    try {
      setRules(await getRulesByTemplate(tid));
    } catch (err) {
      setError(err.message);
    }
  }, []);

  const selectTemplate = (tid) => {
    setTemplateId(tid);
    setError('');
    resetForm();
    loadRules(tid);
  };

  const resetForm = () => {
    setFieldName(EMPTY_FORM.fieldName);
    setFieldRequirement(EMPTY_FORM.fieldRequirement);
    setRuleType(EMPTY_FORM.ruleType);
    setPattern(EMPTY_FORM.pattern);
    setRangeMin(EMPTY_FORM.rangeMin);
    setRangeMax(EMPTY_FORM.rangeMax);
    setEditingRule(null);
  };

  const changeRuleType = (nextType) => {
    setRuleType(nextType);
    setPattern('');
    setRangeMin('');
    setRangeMax('');
  };

  const saveFormatRule = async (tid, saveFieldName, saveRuleType, config) => {
    await upsertRule(tid, saveFieldName, apiRuleType(saveRuleType), config);
  };

  const deleteFormatRule = async (tid, deleteFieldName, deleteRuleType) => {
    await deleteRule(tid, deleteFieldName, apiRuleType(deleteRuleType));
  };

  const loadRuleForEdit = (rule) => {
    const loaded = loadRuleIntoFormState(rule, rules);
    setFieldName(rule.fieldName);
    setFieldRequirement(loaded.fieldRequirement);
    setRuleType(loaded.ruleType);
    setPattern(loaded.pattern);
    setRangeMin(loaded.rangeMin);
    setRangeMax(loaded.rangeMax);
    setEditingRule(createEditSnapshot(rule, rules));
    formRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  };

  const validateFormatConfig = () => {
    if (ruleType === 'REGEX' && !pattern.trim()) {
      toast.error('Choose or enter a format pattern.');
      return false;
    }
    if (ruleType === 'RANGE') {
      if (rangeMin === '' || rangeMax === '') {
        toast.error('Enter both a minimum and maximum value.');
        return false;
      }
      const min = Number(rangeMin);
      const max = Number(rangeMax);
      if (Number.isNaN(min) || Number.isNaN(max)) {
        toast.error('Minimum and maximum must be numbers.');
        return false;
      }
      if (min > max) {
        toast.error('Minimum must be less than or equal to maximum.');
        return false;
      }
    }
    return true;
  };

  const submitRule = async (e) => {
    e.preventDefault();
    if (ruleType !== 'NONE' && !validateFormatConfig()) return;

    setBusy(true);
    const tid = Number(templateId);
    const loadingMessage = editingRule ? 'Updating rule…' : 'Saving rule…';
    const successMessage = editingRule
      ? `Rules updated for field "${fieldName}"`
      : `Rules saved for field "${fieldName}"`;

    try {
      await toast.promise(
        (async () => {
          const isEditing = !!editingRule;
          const isEditingRequiredRow = isEditing && editingRule.ruleType === 'REQUIRED';
          const isEditingFormatRow = isEditing && editingRule.ruleType !== 'REQUIRED';

          if (
            !isEditing ||
            shouldSyncRequired(fieldRequirement, editingRule.fieldRequirement)
          ) {
            if (fieldRequirement === 'required') {
              await upsertRule(tid, fieldName, 'REQUIRED', {});
            } else if (isFieldRequired(rules, fieldName)) {
              await deleteRule(tid, fieldName, 'REQUIRED');
            }
          }

          if (isEditingFormatRow) {
            const oldField = editingRule.fieldName;
            const oldType = editingRule.ruleType;

            if (
              oldType !== 'REQUIRED' &&
              (oldField !== fieldName || oldType !== ruleType || ruleType === 'NONE')
            ) {
              await deleteFormatRule(tid, oldField, oldType);
            }

            if (ruleType !== 'NONE') {
              const config = buildRuleConfig(ruleType, { pattern, rangeMin, rangeMax });
              await saveFormatRule(tid, fieldName, ruleType, config);
            }
          } else if (isEditingRequiredRow) {
            if (ruleType !== 'NONE') {
              const config = buildRuleConfig(ruleType, { pattern, rangeMin, rangeMax });
              await saveFormatRule(tid, fieldName, ruleType, config);
            }
          } else if (ruleType !== 'NONE') {
            const config = buildRuleConfig(ruleType, { pattern, rangeMin, rangeMax });
            await saveFormatRule(tid, fieldName, ruleType, config);
          }
        })(),
        { loading: loadingMessage, success: successMessage }
      );

      resetForm();
      await loadRules(templateId);
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const removeRule = async (rule) => {
    const type = ruleTypeName(rule.ruleType);
    if (!window.confirm(`Delete ${formatRuleTypeLabel(rule)} on "${rule.fieldName}"?`)) return;
    try {
      await toast.promise(deleteRule(Number(templateId), rule.fieldName, type), {
        loading: 'Deleting rule…',
        success: `Rule on "${rule.fieldName}" deleted`,
      });
      if (
        editingRule &&
        editingRule.fieldName === rule.fieldName &&
        editingRule.ruleType === type
      ) {
        resetForm();
      }
      await loadRules(templateId);
    } catch {
      // The error toast is already shown.
    }
  };

  const runTest = async (e) => {
    e.preventDefault();
    setTestResults(null);
    try {
      const results = await toast.promise(validateRows(Number(templateId), [testRow]), {
        loading: 'Validating row…',
      });
      setTestResults(results);
    } catch {
      // The error toast is already shown.
    }
  };

  const testErrors = (testResults || []).filter((r) => r.errorMessage);
  const ruleHint = RULE_HINTS[ruleType];
  const saveDisabled =
    busy ||
    !fieldName ||
    (ruleType === 'REGEX' && !pattern.trim()) ||
    (ruleType === 'RANGE' && (rangeMin === '' || rangeMax === ''));

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Set Field Rules' }]}
        title="Set Field Rules"
        subtitle="Step 2 — per-field rules every processed row is checked against"
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      <Card className="mb-5">
        <Field label="Template">
          <select
            className={inputClass}
            value={templateId}
            onChange={(e) => selectTemplate(e.target.value)}
          >
            <option value="">Select a template…</option>
            {templates.map((t) => (
              <option key={t.templateId} value={t.templateId}>
                {t.name}
              </option>
            ))}
          </select>
        </Field>
      </Card>

      {templateId && (
        <>
          <h2 className="mb-3 text-xl font-normal">Rules</h2>
          {!rules ? (
            <Spinner />
          ) : (
            <DataTable
              columns={[
                { key: 'fieldName', label: 'Field' },
                {
                  key: 'required',
                  label: 'Required',
                  render: (row) => (isFieldRequired(rules, row.fieldName) ? 'Yes' : 'No'),
                },
                {
                  key: 'ruleType',
                  label: 'Rule',
                  render: (row) => formatRuleTypeLabel(row),
                },
                {
                  key: 'settings',
                  label: 'Settings',
                  render: (row) => (
                    <span className="text-sm text-muted">{formatRuleConfig(row)}</span>
                  ),
                },
                {
                  key: 'actions',
                  label: '',
                  render: (row) => (
                    <div className="flex justify-end gap-2">
                      <Button variant="secondary" onClick={() => loadRuleForEdit(row)}>
                        Update
                      </Button>
                      <Button variant="danger" onClick={() => removeRule(row)}>
                        Delete
                      </Button>
                    </div>
                  ),
                },
              ]}
              rows={rules}
              emptyMessage="No rules for this template yet."
            />
          )}

          <div ref={formRef}>
          <Card className="mt-5">
            <h3 className="mb-3 text-lg font-normal">
              {editingRule ? 'Update field rules' : 'Add field rules'}
            </h3>
            <form onSubmit={submitRule} className="space-y-4">
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                <Field label="Field">
                  <select
                    required
                    className={inputClass}
                    value={fieldName}
                    onChange={(e) => setFieldName(e.target.value)}
                  >
                    <option value="">Select a field…</option>
                    {templateFields.map((f) => (
                      <option key={f.templateFieldsId} value={f.fieldName}>
                        {f.fieldName}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="Required or Optional">
                  <select
                    className={inputClass}
                    value={fieldRequirement}
                    onChange={(e) => setFieldRequirement(e.target.value)}
                  >
                    {FIELD_REQUIREMENT_OPTIONS.map(({ value, label }) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="Format rule type">
                  <select
                    className={inputClass}
                    value={ruleType}
                    onChange={(e) => changeRuleType(e.target.value)}
                  >
                    {FORMAT_RULE_TYPE_OPTIONS.map(({ value, label }) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                  </select>
                </Field>
              </div>

              {fieldRequirement === 'required' && (
                <p className="text-sm text-muted">
                  The field must contain a value — empty cells fail validation.
                </p>
              )}

              {ruleHint && <p className="text-sm text-muted">{ruleHint}</p>}

              {ruleType === 'REGEX' && (
                <RegexBuilder pattern={pattern} onChange={setPattern} />
              )}

              {ruleType === 'RANGE' && (
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                  <Field label="Minimum">
                    <input
                      type="number"
                      required
                      className={inputClass}
                      value={rangeMin}
                      onChange={(e) => setRangeMin(e.target.value)}
                    />
                  </Field>
                  <Field label="Maximum">
                    <input
                      type="number"
                      required
                      className={inputClass}
                      value={rangeMax}
                      onChange={(e) => setRangeMax(e.target.value)}
                    />
                  </Field>
                </div>
              )}

              <div className="flex flex-wrap gap-2">
                <Button type="submit" disabled={saveDisabled}>
                  {busy ? 'Saving…' : editingRule ? 'Update rule' : 'Save rule'}
                </Button>
                {editingRule && (
                  <Button type="button" variant="secondary" onClick={resetForm}>
                    Cancel
                  </Button>
                )}
              </div>
            </form>
          </Card>
          </div>

          <Card className="mt-5">
            <h3 className="mb-1 text-lg font-normal">Test a row</h3>
            <p className="mb-3 text-sm text-muted">
              Enter sample values and run them through this template's rules.
            </p>
            <form onSubmit={runTest}>
              <div className="mb-3 grid max-h-72 grid-cols-1 gap-3 overflow-y-auto pr-1 sm:grid-cols-2">
                {templateFields.map((f) => (
                  <Field key={f.templateFieldsId} label={f.fieldName}>
                    <input
                      className={inputClass}
                      value={testRow[f.fieldName] || ''}
                      onChange={(e) =>
                        setTestRow((row) => ({ ...row, [f.fieldName]: e.target.value }))
                      }
                    />
                  </Field>
                ))}
              </div>
              <Button type="submit">Validate row</Button>
            </form>

            {testResults && (
              <div className="mt-4">
                {testErrors.length === 0 ? (
                  <Callout variant="success" title="Row is valid">
                    All rules passed for the sample row.
                  </Callout>
                ) : (
                  <div className="space-y-2">
                    <Callout variant="error" title={`${testErrors.length} validation error${testErrors.length > 1 ? 's' : ''}`}>
                      <ul className="list-disc pl-4">
                        {testErrors.map((r, i) => (
                          <li key={i}>
                            <strong>{r.fieldName}</strong>
                            {r.rawValue ? ` = "${r.rawValue}"` : ''}: {r.errorMessage}
                          </li>
                        ))}
                      </ul>
                    </Callout>
                  </div>
                )}
              </div>
            )}
          </Card>

          <p className="mt-5 text-sm text-muted">
            Done with rules?{' '}
            <Link to="/ingestion" className="text-accent hover:underline">
              Next: upload &amp; process a file →
            </Link>
          </p>
        </>
      )}
    </div>
  );
}
