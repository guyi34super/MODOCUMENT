import { useEffect, useId, useState } from 'react';
import Modal from '../Modal';
import { Button, Callout, Field, Spinner, Tag, inputClass } from '../ui';
import { getTemplates } from '../../api/cache';
import { copyFromFlowTemplate, copyPreview, createDataTemplate } from '../../api/dataTemplates';
import { KIND_LABELS } from '../pdf/geometry';
import { RULE_LABELS, ruleSummary } from '../rules/ruleText';

const NAME_MAX = 200;
const DESCRIPTION_MAX = 1000;

/** "New data template": a name and an optional description → `onCreated(dto)`. */
export function NewDataTemplateDialog({ open, onClose, onCreated }) {
  const formId = useId();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState('');

  useEffect(() => {
    if (!open) return;
    setName('');
    setDescription('');
    setProblem('');
  }, [open]);

  const submit = async (event) => {
    event.preventDefault();
    if (!name.trim()) {
      setProblem('Give the data template a name.');
      return;
    }
    setBusy(true);
    setProblem('');
    try {
      onCreated(await createDataTemplate({ name: name.trim(), description: description.trim() }));
    } catch (err) {
      setProblem(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="New data template"
      description="A data template lists the fields a document collects and the rules each must pass."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" form={formId} disabled={busy}>
            {busy ? 'Creating…' : 'Create'}
          </Button>
        </>
      }
    >
      <form id={formId} onSubmit={submit} className="space-y-3" noValidate>
        <Field label="Name">
          <input className={inputClass} value={name} maxLength={NAME_MAX} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="Description" hint="Optional">
          <textarea
            className={inputClass}
            rows={2}
            value={description}
            maxLength={DESCRIPTION_MAX}
            onChange={(e) => setDescription(e.target.value)}
          />
        </Field>
        {problem && (
          <p role="alert" className="text-sm text-danger-ink">
            {problem}
          </p>
        )}
      </form>
    </Modal>
  );
}

/**
 * "Copy from a flow template" (contract §2): pick a flow template, see its
 * fields with the rules each brings (all ticked; rules that cannot be
 * copied are listed as skipped), untick what is not wanted, name the copy,
 * then Copy → `onCopied(dto)`.
 */
export function CopyFromFlowDialog({ open, onClose, onCopied }) {
  const formId = useId();
  const [templates, setTemplates] = useState(null);
  const [templateId, setTemplateId] = useState('');
  const [preview, setPreview] = useState(null);
  const [loadingPreview, setLoadingPreview] = useState(false);
  const [ticked, setTicked] = useState({});
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState('');

  useEffect(() => {
    if (!open) return undefined;
    let live = true;
    setTemplateId('');
    setPreview(null);
    setTicked({});
    setName('');
    setProblem('');
    getTemplates()
      .then((list) => live && setTemplates(list || []))
      .catch((err) => {
        if (!live) return;
        setTemplates([]);
        setProblem(err.message);
      });
    return () => {
      live = false;
    };
  }, [open]);

  const choose = async (id) => {
    setTemplateId(id);
    setPreview(null);
    setProblem('');
    if (!id) return;
    setLoadingPreview(true);
    try {
      const data = await copyPreview(id);
      setPreview(data);
      setTicked(Object.fromEntries((data?.fields || []).map((f) => [f.name, true])));
      const template = (templates || []).find((t) => String(t.templateId) === String(id));
      setName(data?.templateName || template?.name || '');
    } catch (err) {
      setProblem(err.message);
    } finally {
      setLoadingPreview(false);
    }
  };

  const fields = preview?.fields || [];
  const chosen = fields.filter((f) => ticked[f.name]).map((f) => f.name);
  const allTicked = fields.length > 0 && chosen.length === fields.length;

  const submit = async (event) => {
    event.preventDefault();
    if (!preview || chosen.length === 0) return;
    if (!name.trim()) {
      setProblem('Give the data template a name.');
      return;
    }
    setBusy(true);
    setProblem('');
    try {
      onCopied(await copyFromFlowTemplate({ templateId: preview.templateId ?? templateId, name: name.trim(), fields: chosen }));
    } catch (err) {
      setProblem(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      title="Copy from a flow template"
      description="The fields and their rules are copied into a new data template; you can change everything afterwards."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" form={formId} disabled={busy || !preview || chosen.length === 0}>
            {busy ? 'Copying…' : `Copy ${chosen.length} field${chosen.length === 1 ? '' : 's'}`}
          </Button>
        </>
      }
    >
      <form id={formId} onSubmit={submit} className="space-y-3" noValidate>
        <Field label="Flow template">
          <select
            className={inputClass}
            value={templateId}
            disabled={!templates}
            onChange={(e) => choose(e.target.value)}
          >
            <option value="">{templates ? 'Choose a flow template…' : 'Loading…'}</option>
            {(templates || []).map((t) => (
              <option key={t.templateId} value={t.templateId}>
                {t.name}
              </option>
            ))}
          </select>
        </Field>
        {loadingPreview && <Spinner label="Reading its fields and rules…" />}
        {preview && (
          <>
            {fields.length === 0 ? (
              <Callout variant="info">This flow template has no fields.</Callout>
            ) : (
              <fieldset className="space-y-2" data-testid="copy-fields">
                <legend className="mb-1 text-sm font-medium">Fields to copy</legend>
                <label className="flex items-center gap-2 text-xs text-muted">
                  <input
                    type="checkbox"
                    checked={allTicked}
                    onChange={(e) => setTicked(Object.fromEntries(fields.map((f) => [f.name, e.target.checked])))}
                  />
                  All fields ({chosen.length} of {fields.length} ticked)
                </label>
                <ul className="max-h-80 space-y-1 overflow-y-auto rounded border border-line p-2">
                  {fields.map((f) => (
                    <li key={f.name} className="text-sm" data-testid={`copy-field-${f.name}`}>
                      <label className="flex items-center gap-2">
                        <input
                          type="checkbox"
                          checked={Boolean(ticked[f.name])}
                          onChange={(e) => setTicked((t) => ({ ...t, [f.name]: e.target.checked }))}
                        />
                        <span className="font-medium">{f.name}</span>
                        <Tag tone="neutral">{KIND_LABELS[f.kind] || f.kind || f.dataType}</Tag>
                        {f.piiKind && <Tag tone="warning">PII</Tag>}
                      </label>
                      {(f.rules || []).length > 0 && (
                        <ul className="ml-6 flex flex-wrap gap-1">
                          {f.rules.map((r, i) => (
                            <li key={i}>
                              <Tag tone="info" title={ruleSummary(r)}>
                                {RULE_LABELS[r.ruleType] || r.ruleType}
                              </Tag>
                            </li>
                          ))}
                        </ul>
                      )}
                      {(f.skipped || []).length > 0 && (
                        <p className="ml-6 text-xs text-muted">
                          Not copied: {f.skipped.map((s) => `${s.flowRuleType} (${s.reason})`).join(', ')}
                        </p>
                      )}
                    </li>
                  ))}
                </ul>
              </fieldset>
            )}
            <Field label="Name of the new data template">
              <input className={inputClass} value={name} maxLength={NAME_MAX} onChange={(e) => setName(e.target.value)} />
            </Field>
          </>
        )}
        {problem && (
          <p role="alert" className="text-sm text-danger-ink">
            {problem}
          </p>
        )}
      </form>
    </Modal>
  );
}
