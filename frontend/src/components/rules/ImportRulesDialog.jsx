import { useEffect, useId, useState } from 'react';
import { getTemplates } from '../../api/cache';
import { importPreview, importRules } from '../../api/docRules';
import Modal from '../Modal';
import { Button, Field, Spinner, Tag, inputClass } from '../ui';
import { RULE_LABELS, ruleSummary } from './ruleText';

const rowId = (row) => `${row.regionKey}:${row.templateId ?? ''}`;

/**
 * Imports flow-template field rules into the blueprint's rules: from its
 * data sources or from a chosen flow template. Shows each document field
 * with its matched template field (editable for a template), the rules to
 * import and the skipped ones, then imports with Merge or Replace.
 * `onImported(rulesDto)`; a 409 goes to `onConflict()`.
 */
export default function ImportRulesDialog({ open, onClose, blueprintId, baseRevision, onImported, onConflict }) {
  const ids = useId();
  const [source, setSource] = useState('DATA_SOURCES');
  const [templates, setTemplates] = useState(null);
  const [templateId, setTemplateId] = useState('');
  const [preview, setPreview] = useState(null);
  const [mapping, setMapping] = useState({});
  const [included, setIncluded] = useState({});
  const [mode, setMode] = useState('MERGE');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState('');

  useEffect(() => {
    if (!open) return;
    setSource('DATA_SOURCES');
    setTemplateId('');
    setPreview(null);
    setMode('MERGE');
    setProblem('');
  }, [open]);

  useEffect(() => {
    if (!open || source !== 'TEMPLATE' || templates) return;
    getTemplates()
      .then((list) => setTemplates(list || []))
      .catch((err) => {
        setTemplates([]);
        setProblem(err.message);
      });
  }, [open, source, templates]);

  const loadPreview = async () => {
    setBusy(true);
    setProblem('');
    try {
      const dto = await importPreview(blueprintId, source === 'TEMPLATE' ? Number(templateId) : undefined);
      setPreview(dto);
      const map = {};
      const inc = {};
      for (const row of dto?.fields || []) {
        map[rowId(row)] = row.fieldName || '';
        inc[rowId(row)] = Boolean(row.fieldName);
      }
      setMapping(map);
      setIncluded(inc);
    } catch (err) {
      setProblem(err.message);
    } finally {
      setBusy(false);
    }
  };

  const rows = preview?.fields || [];
  const fromTemplate = preview?.source === 'TEMPLATE';
  const chosen = rows.filter((row) => included[rowId(row)] && mapping[rowId(row)]);

  const submit = async () => {
    setBusy(true);
    setProblem('');
    const body = {
      baseRevision,
      mapping: Object.fromEntries(chosen.map((row) => [row.regionKey, mapping[rowId(row)]])),
      mode,
    };
    if (fromTemplate) body.templateId = preview.templateId ?? Number(templateId);
    try {
      const result = await importRules(blueprintId, body);
      onImported(result);
    } catch (err) {
      if (err.status === 409) onConflict();
      else setProblem(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      title="Import rules"
      description="Copy the field rules of a flow template onto this template's fields."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          {preview && (
            <Button onClick={submit} disabled={busy || chosen.length === 0}>
              {busy ? 'Importing…' : `Import rules of ${chosen.length} field${chosen.length === 1 ? '' : 's'}`}
            </Button>
          )}
        </>
      }
    >
      <div className="space-y-4">
        <fieldset>
          <legend className="mb-1 text-sm font-medium">Take the rules from</legend>
          <div className="flex flex-wrap gap-4 text-sm">
            <label className="flex items-center gap-1.5">
              <input
                type="radio"
                name={`${ids}-source`}
                checked={source === 'DATA_SOURCES'}
                onChange={() => {
                  setSource('DATA_SOURCES');
                  setPreview(null);
                }}
              />
              Data sources
            </label>
            <label className="flex items-center gap-1.5">
              <input
                type="radio"
                name={`${ids}-source`}
                checked={source === 'TEMPLATE'}
                onChange={() => {
                  setSource('TEMPLATE');
                  setPreview(null);
                }}
              />
              A flow template
            </label>
          </div>
        </fieldset>
        {source === 'TEMPLATE' && (
          <Field label="Flow template">
            <select
              className={inputClass}
              value={templateId}
              disabled={!templates}
              onChange={(e) => {
                setTemplateId(e.target.value);
                setPreview(null);
              }}
            >
              <option value="">{templates ? 'Choose a template…' : 'Loading templates…'}</option>
              {(templates || []).map((t) => (
                <option key={t.templateId} value={t.templateId}>
                  {t.name}
                </option>
              ))}
            </select>
          </Field>
        )}
        <Button
          variant="secondary"
          size="sm"
          onClick={loadPreview}
          disabled={busy || (source === 'TEMPLATE' && !templateId)}
        >
          {preview ? 'Refresh preview' : 'Show what would be imported'}
        </Button>

        {busy && !preview && <Spinner label="Reading the rules…" />}
        {problem && (
          <p role="alert" className="text-sm text-danger-ink">
            {problem}
          </p>
        )}

        {preview && (
          <>
            {rows.length === 0 ? (
              <p className="text-sm text-muted">
                {fromTemplate ? 'No field of this template matches a document field.' : 'No data source maps a field yet.'}
              </p>
            ) : (
              <div className="max-h-[50vh] overflow-auto">
                <table className="w-full text-left text-xs" aria-label="Rules to import">
                  <thead className="text-muted">
                    <tr>
                      <th className="p-1">Import</th>
                      <th className="p-1">Document field</th>
                      <th className="p-1">Template field</th>
                      <th className="p-1">Rules</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row) => {
                      const key = rowId(row);
                      const name = row.label || row.regionKey;
                      return (
                        <tr key={key} className="border-t border-line align-top" data-testid={`import-row-${row.regionKey}`}>
                          <td className="p-1">
                            <input
                              type="checkbox"
                              aria-label={`Import the rules of ${name}`}
                              checked={Boolean(included[key])}
                              onChange={(e) => setIncluded((s) => ({ ...s, [key]: e.target.checked }))}
                            />
                          </td>
                          <td className="p-1">
                            {name}
                            {!fromTemplate && row.templateName && <span className="block text-muted">from {row.templateName}</span>}
                          </td>
                          <td className="p-1">
                            {fromTemplate ? (
                              <select
                                aria-label={`Template field for ${name}`}
                                className={`${inputClass} !py-0.5 text-xs`}
                                value={mapping[key] || ''}
                                onChange={(e) => {
                                  const value = e.target.value;
                                  setMapping((m) => ({ ...m, [key]: value }));
                                  setIncluded((s) => ({ ...s, [key]: Boolean(value) }));
                                }}
                              >
                                <option value="">Not matched</option>
                                {(preview.templateFields || []).map((f) => (
                                  <option key={f} value={f}>
                                    {f}
                                  </option>
                                ))}
                              </select>
                            ) : (
                              <span>{row.fieldName || '—'}</span>
                            )}
                            <span className="mt-0.5 flex flex-wrap gap-1">
                              {row.sameName && mapping[key] === row.fieldName && <Tag tone="success">same name</Tag>}
                              {row.score != null && mapping[key] === row.fieldName && !row.sameName && (
                                <Tag tone="neutral">match {Math.round(row.score * 100)} %</Tag>
                              )}
                              {mapping[key] && mapping[key] !== row.fieldName && <Tag tone="info">chosen by you</Tag>}
                            </span>
                          </td>
                          <td className="p-1">
                            {mapping[key] && mapping[key] !== row.fieldName ? (
                              <span className="text-muted">The rules of {mapping[key]} are read when you import.</span>
                            ) : (
                              <>
                                {(row.rules || []).length === 0 && <span className="text-muted">No rules to import.</span>}
                                <ul className="space-y-0.5">
                                  {(row.rules || []).map((rule, i) => (
                                    // Rules have no id before they are imported.
                                    <li key={`${rule.ruleType}-${i}`}>
                                      <strong>{RULE_LABELS[rule.ruleType] || rule.ruleType}</strong>: {ruleSummary(rule)}
                                    </li>
                                  ))}
                                </ul>
                                {(row.skipped || []).map((skip, i) => (
                                  <p key={`${skip.flowRuleType}-${i}`} className="text-amber-800">
                                    Skipped {skip.flowRuleType}: {skip.reason}
                                  </p>
                                ))}
                              </>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
            <fieldset>
              <legend className="mb-1 text-sm font-medium">How to import</legend>
              <div className="space-y-1 text-sm">
                <label className="flex items-start gap-1.5">
                  <input type="radio" name={`${ids}-mode`} checked={mode === 'MERGE'} onChange={() => setMode('MERGE')} />
                  <span>
                    Merge <span className="text-muted">— replace only the same rules imported before; keep the rest</span>
                  </span>
                </label>
                <label className="flex items-start gap-1.5">
                  <input type="radio" name={`${ids}-mode`} checked={mode === 'REPLACE'} onChange={() => setMode('REPLACE')} />
                  <span>
                    Replace <span className="text-muted">— remove all imported rules of these fields first (rules added by hand stay)</span>
                  </span>
                </label>
              </div>
            </fieldset>
          </>
        )}
      </div>
    </Modal>
  );
}
