import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { getTemplates } from '../api/cache';
import { getAllFields } from '../api/templates';
import {
  RULE_TYPES,
  deleteRule,
  getRulesByTemplate,
  upsertRule,
  validateRows,
} from '../api/validationRules';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Button, Callout, Card, ErrorBanner, Field, inputClass, Spinner } from '../components/ui';
import { useToast } from '../context/ToastContext';

const RULE_TYPE_NAMES = {
  0: 'CONDITIONAL_REQUIREMENT',
  1: 'DATE_FORMAT',
  2: 'EXPIRY_DATE',
  3: 'REQUIRED',
  4: 'REGEX',
  5: 'RANGE',
  6: 'TYPE',
  7: 'CUSTOM',
};

const ruleTypeName = (value) =>
  typeof value === 'number' ? (RULE_TYPE_NAMES[value] ?? value) : value;

export default function ValidationRulesPage() {
  const toast = useToast();
  const [searchParams] = useSearchParams();
  const [templates, setTemplates] = useState([]);
  const [allFields, setAllFields] = useState([]);
  const [templateId, setTemplateId] = useState('');
  const [rules, setRules] = useState(null);
  const [error, setError] = useState('');

  // Upsert form
  const [fieldName, setFieldName] = useState('');
  const [ruleType, setRuleType] = useState('REQUIRED');
  const [configText, setConfigText] = useState('{}');
  const [pattern, setPattern] = useState('');
  const [busy, setBusy] = useState(false);

  // Ad-hoc row test
  const [testRow, setTestRow] = useState({});
  const [testResults, setTestResults] = useState(null);

  useEffect(() => {
    Promise.all([getTemplates(), getAllFields()])
      .then(([tpl, fields]) => {
        setTemplates(tpl);
        setAllFields(fields);
        // Deep link from the template editor / templates list: ?templateId=N
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
    loadRules(tid);
  };

  const submitRule = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      let config;
      if (ruleType === 'REGEX') {
        config = { Pattern: pattern };
      } else {
        try {
          config = configText.trim() ? JSON.parse(configText) : {};
        } catch {
          toast.error('Config must be valid JSON.');
          return;
        }
      }
      await toast.promise(upsertRule(Number(templateId), fieldName, ruleType, config), {
        loading: 'Saving rule…',
        success: `Rule ${ruleType} saved for field "${fieldName}"`,
      });
      await loadRules(templateId);
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const removeRule = async (rule) => {
    const type = ruleTypeName(rule.ruleType);
    if (!window.confirm(`Delete ${type} rule on "${rule.fieldName}"?`)) return;
    try {
      await toast.promise(deleteRule(Number(templateId), rule.fieldName, type), {
        loading: 'Deleting rule…',
        success: `${type} rule on "${rule.fieldName}" deleted`,
      });
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
                { key: 'ruleType', label: 'Rule', render: (row) => ruleTypeName(row.ruleType) },
                {
                  key: 'configJson',
                  label: 'Config',
                  render: (row) => (
                    <code className="text-xs">{row.configJson || JSON.stringify(row.config) || '{}'}</code>
                  ),
                },
                {
                  key: 'actions',
                  label: '',
                  render: (row) => (
                    <div className="text-right">
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

          <Card className="mt-5">
            <h3 className="mb-3 text-lg font-normal">Add or update a rule</h3>
            <form onSubmit={submitRule} className="space-y-4">
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
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
                <Field label="Rule type">
                  <select
                    className={inputClass}
                    value={ruleType}
                    onChange={(e) => setRuleType(e.target.value)}
                  >
                    {RULE_TYPES.map((type) => (
                      <option key={type} value={type}>
                        {type}
                      </option>
                    ))}
                  </select>
                </Field>
              </div>
              {ruleType === 'REGEX' ? (
                <Field label="Pattern" hint="Java regular expression, e.g. ^[A-Z]{2}\d{4}$">
                  <input
                    required
                    className={`${inputClass} font-mono`}
                    value={pattern}
                    onChange={(e) => setPattern(e.target.value)}
                  />
                </Field>
              ) : (
                <Field label="Config (JSON)" hint='Rule-specific settings, e.g. {"Min": 0, "Max": 100}'>
                  <input
                    className={`${inputClass} font-mono`}
                    value={configText}
                    onChange={(e) => setConfigText(e.target.value)}
                  />
                </Field>
              )}
              <Button type="submit" disabled={busy || !fieldName}>
                {busy ? 'Saving…' : 'Save rule'}
              </Button>
            </form>
          </Card>

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
