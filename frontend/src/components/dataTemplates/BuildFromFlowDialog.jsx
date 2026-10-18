import { useEffect, useId, useMemo, useState } from 'react';
import Modal from '../Modal';
import Stepper from '../Stepper';
import { Button, Callout, Field, Spinner, Tag, inputClass } from '../ui';
import { getTemplates } from '../../api/cache';
import { addFromFlowTemplates, buildFromFlowTemplates, copyPreview } from '../../api/dataTemplates';
import { slugify } from '../pdf/geometry';
import { RULE_LABELS, ruleSummary } from '../rules/ruleText';
import { DATA_KIND_LABELS } from './dataFieldKinds';

const NAME_MAX = 200;
const KEY_MAX = 64;
/** The server's slugs (OverlayMapper.slug) are cut to 56 characters. */
const SLUG_MAX = 56;

/** OverlayMapper.slug: `[a-z0-9_]`, at most 56 characters; '' when nothing is left. */
export const serverSlug = (text) =>
  String(text ?? '')
    .normalize('NFKD')
    .replace(/\p{M}/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, SLUG_MAX)
    .replace(/_+$/g, '');

/**
 * The field names the server will give (contract §2, "Building from several
 * flow templates"; DataTemplateUseCase.clashName): the flow field name
 * slugged ("field" when empty); when that is taken (by an earlier source, or
 * by `existing` — the template being added to) it becomes `base_<slug of its
 * flow template's name>` ("template_<id>" when that slug is empty), then
 * `base_<slug>_2`, `_3`…; the base is cut so the whole name, suffix
 * included, fits 64 characters.
 *
 * `sources`: [{ templateId, templateName, fields: [flowFieldName] }] in request order.
 * Returns one entry per field: { source, flowName, name, renamed }.
 */
export function predictFieldNames(sources, existing = []) {
  const taken = new Set(existing);
  const out = [];
  sources.forEach((source, index) => {
    const templateSlug = serverSlug(source.templateName) || `template_${source.templateId}`;
    for (const flowName of source.fields) {
      const base = serverSlug(flowName) || 'field';
      let name = base;
      for (let n = 1; taken.has(name); n += 1) {
        const suffix = `_${templateSlug}${n === 1 ? '' : `_${n}`}`;
        name = `${base.slice(0, Math.max(1, Math.min(base.length, KEY_MAX - suffix.length)))}${suffix}`;
      }
      taken.add(name);
      out.push({ source: index, flowName, name, renamed: name !== base });
    }
  });
  return out;
}

const STEPS = [
  { id: 'templates', label: '1. Templates' },
  { id: 'fields', label: '2. Fields' },
  { id: 'preview', label: '3. Preview' },
  { id: 'save', label: '4. Save' },
];

/**
 * "Build from Document Flow templates" (contract §2 follow-up, §6): tick one
 * or more flow templates, choose the fields of each (all ticked, with their
 * rules), preview the combined field names (a clash is renamed), then save
 * them as a new data template or add them to `current` (a DataTemplateDto,
 * with its revision). `onSaved(dto, { added, count })`; a stale revision
 * calls `onConflict()` when given.
 */
export default function BuildFromFlowDialog({ open, onClose, current = null, onSaved, onConflict }) {
  const ids = useId();
  const [step, setStep] = useState('templates');
  const [templates, setTemplates] = useState(null);
  const [search, setSearch] = useState('');
  const [chosen, setChosen] = useState([]);
  const [previews, setPreviews] = useState({});
  const [loading, setLoading] = useState(false);
  const [ticked, setTicked] = useState({});
  const [target, setTarget] = useState('new');
  const [name, setName] = useState('');
  const [nameTouched, setNameTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState('');

  useEffect(() => {
    if (!open) return undefined;
    let live = true;
    setStep('templates');
    setSearch('');
    setChosen([]);
    setPreviews({});
    setTicked({});
    setTarget('new');
    setName('');
    setNameTouched(false);
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

  const templateName = (id) =>
    previews[id]?.templateName || (templates || []).find((t) => String(t.templateId) === String(id))?.name || `Template ${id}`;

  const toggleTemplate = (id, on) => {
    setChosen((list) => (on ? [...list.filter((x) => x !== id), id] : list.filter((x) => x !== id)));
  };

  const visible = (templates || []).filter((t) => (t.name || '').toLowerCase().includes(search.trim().toLowerCase()));

  /** Loads the copy preview of every ticked template not read yet; all its fields start ticked. */
  const goToFields = async () => {
    setProblem('');
    setStep('fields');
    const missing = chosen.filter((id) => !previews[id]);
    if (!missing.length) return;
    setLoading(true);
    try {
      const loaded = await Promise.all(missing.map((id) => copyPreview(id)));
      setPreviews((p) => ({ ...p, ...Object.fromEntries(missing.map((id, i) => [id, loaded[i]])) }));
      setTicked((t) => ({
        ...t,
        ...Object.fromEntries(missing.map((id, i) => [id, Object.fromEntries((loaded[i]?.fields || []).map((f) => [f.name, true]))])),
      }));
    } catch (err) {
      setProblem(err.message);
    } finally {
      setLoading(false);
    }
  };

  const sources = useMemo(
    () =>
      chosen
        .filter((id) => previews[id])
        .map((id) => ({
          templateId: previews[id].templateId ?? id,
          templateName: templateName(id),
          fields: (previews[id].fields || []).filter((f) => ticked[id]?.[f.name]).map((f) => f.name),
          preview: previews[id],
        }))
        .filter((s) => s.fields.length > 0),
    // templateName reads previews/templates, both listed.
    [chosen, previews, ticked, templates] // eslint-disable-line react-hooks/exhaustive-deps
  );
  const fieldCount = sources.reduce((n, s) => n + s.fields.length, 0);
  const adding = target === 'add' && current;
  const predicted = useMemo(
    () => predictFieldNames(sources, adding ? (current.fields || []).map((f) => f.name) : []),
    [sources, adding, current]
  );

  const goToSave = () => {
    if (!nameTouched) setName(sources.map((s) => s.templateName).join(' + ').slice(0, NAME_MAX));
    setStep('save');
  };

  const save = async (event) => {
    event.preventDefault();
    if (!fieldCount) return;
    const body = sources.map((s) => ({ templateId: s.templateId, fields: s.fields }));
    if (!adding && !name.trim()) {
      setProblem('Give the new template a name.');
      return;
    }
    setBusy(true);
    setProblem('');
    try {
      const dto = adding
        ? await addFromFlowTemplates(current.id, { baseRevision: current.revision, sources: body })
        : await buildFromFlowTemplates({ name: name.trim(), sources: body });
      onSaved(dto, { added: Boolean(adding), count: fieldCount });
    } catch (err) {
      if (err.status === 409 && onConflict) onConflict();
      else setProblem(err.message);
    } finally {
      setBusy(false);
    }
  };

  const steps = STEPS.map((s, i) => ({
    ...s,
    done: STEPS.findIndex((x) => x.id === step) > i,
    locked:
      (s.id !== 'templates' && chosen.length === 0) ||
      ((s.id === 'preview' || s.id === 'save') && (fieldCount === 0 || loading)),
  }));

  const footer = (
    <>
      <Button variant="secondary" onClick={onClose} disabled={busy}>
        Cancel
      </Button>
      {step !== 'templates' && (
        <Button
          variant="secondary"
          disabled={busy}
          onClick={() => setStep(STEPS[STEPS.findIndex((s) => s.id === step) - 1].id)}
        >
          Back
        </Button>
      )}
      {step === 'templates' && (
        <Button onClick={goToFields} disabled={chosen.length === 0}>
          Next: choose fields
        </Button>
      )}
      {step === 'fields' && (
        <Button onClick={() => setStep('preview')} disabled={loading || fieldCount === 0}>
          Next: preview
        </Button>
      )}
      {step === 'preview' && (
        <Button onClick={goToSave} disabled={fieldCount === 0}>
          Next: save
        </Button>
      )}
      {step === 'save' && (
        <Button type="submit" form={`${ids}-save`} disabled={busy || fieldCount === 0}>
          {busy ? 'Saving…' : adding ? `Add to ${current.name}` : 'Save as a new template'}
        </Button>
      )}
    </>
  );

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      scrollBody
      title="Build from Document Flow templates"
      description="Pick one or more Document Flow templates and the fields you want; their rules come along. Everything stays editable afterwards."
      footer={footer}
    >
      <div className="space-y-3">
        <Stepper
          steps={steps}
          current={step}
          onSelect={(id) => (id === 'fields' && step === 'templates' ? goToFields() : id === 'save' ? goToSave() : setStep(id))}
        />

        {step === 'templates' && (
          <div className="space-y-2" data-testid="build-templates">
            <Field label="Search flow templates">
              <input className={inputClass} type="search" value={search} onChange={(e) => setSearch(e.target.value)} />
            </Field>
            {!templates ? (
              <Spinner label="Loading the Document Flow templates…" />
            ) : templates.length === 0 ? (
              <Callout variant="info">There are no Document Flow templates yet.</Callout>
            ) : (
              <fieldset>
                <legend className="mb-1 text-sm font-medium">
                  Flow templates ({chosen.length} ticked)
                </legend>
                {visible.length === 0 ? (
                  <p className="text-sm text-muted">No flow template matches “{search}”.</p>
                ) : (
                  <ul className="max-h-72 space-y-1 overflow-y-auto rounded border border-line p-2">
                    {visible.map((t) => (
                      <li key={t.templateId}>
                        <label className="flex items-center gap-2 text-sm">
                          <input
                            type="checkbox"
                            checked={chosen.includes(t.templateId)}
                            onChange={(e) => toggleTemplate(t.templateId, e.target.checked)}
                          />
                          {t.name}
                        </label>
                      </li>
                    ))}
                  </ul>
                )}
              </fieldset>
            )}
          </div>
        )}

        {step === 'fields' && (
          <div className="space-y-3" data-testid="build-fields">
            {loading && <Spinner label="Reading their fields and rules…" />}
            {!loading &&
              chosen
                .filter((id) => previews[id])
                .map((id) => {
                  const preview = previews[id];
                  const fields = preview.fields || [];
                  const tname = templateName(id);
                  const count = fields.filter((f) => ticked[id]?.[f.name]).length;
                  const setAll = (on) =>
                    setTicked((t) => ({ ...t, [id]: Object.fromEntries(fields.map((f) => [f.name, on])) }));
                  return (
                    <fieldset key={id} className="rounded border border-line p-2" data-testid={`build-source-${id}`}>
                      <legend className="px-1 text-sm font-medium">
                        {tname} ({count} of {fields.length} fields)
                      </legend>
                      {fields.length === 0 ? (
                        <p className="text-sm text-muted">This flow template has no fields.</p>
                      ) : (
                        <>
                          <div className="mb-1 flex gap-2">
                            <Button size="sm" variant="secondary" aria-label={`Select all fields of ${tname}`} onClick={() => setAll(true)}>
                              Select all
                            </Button>
                            <Button size="sm" variant="secondary" aria-label={`Select no fields of ${tname}`} onClick={() => setAll(false)}>
                              None
                            </Button>
                          </div>
                          <ul className="max-h-64 space-y-1 overflow-y-auto">
                            {fields.map((f) => (
                              <li key={f.name} className="text-sm" data-testid={`build-field-${id}-${f.name}`}>
                                <div className="flex flex-wrap items-center gap-2">
                                  <label className="flex items-center gap-2">
                                    <input
                                      type="checkbox"
                                      checked={Boolean(ticked[id]?.[f.name])}
                                      onChange={(e) =>
                                        setTicked((t) => ({ ...t, [id]: { ...(t[id] || {}), [f.name]: e.target.checked } }))
                                      }
                                    />
                                    <span className="font-medium">{f.name}</span>
                                  </label>
                                  <Tag tone="neutral">{DATA_KIND_LABELS[f.kind] || f.dataType || f.kind}</Tag>
                                  {f.piiKind && f.piiKind !== 'NONE' && <Tag tone="warning">PII</Tag>}
                                  {(f.rules || []).map((r, i) => (
                                    <Tag key={i} tone="info" title={ruleSummary(r)}>
                                      {RULE_LABELS[r.ruleType] || r.ruleType}
                                    </Tag>
                                  ))}
                                </div>
                                {(f.skipped || []).length > 0 && (
                                  <p className="ml-6 text-xs text-muted">
                                    Not copied: {f.skipped.map((s) => `${s.flowRuleType} (${s.reason})`).join(', ')}
                                  </p>
                                )}
                              </li>
                            ))}
                          </ul>
                        </>
                      )}
                    </fieldset>
                  );
                })}
          </div>
        )}

        {step === 'preview' && (
          <div className="space-y-3" data-testid="build-preview">
            {current && (
              <fieldset>
                <legend className="mb-1 text-sm font-medium">Where do the fields go?</legend>
                <div className="flex flex-wrap gap-4 text-sm">
                  <label className="flex items-center gap-1.5">
                    <input type="radio" name={`${ids}-target`} checked={target === 'new'} onChange={() => setTarget('new')} />
                    A new template
                  </label>
                  <label className="flex items-center gap-1.5">
                    <input type="radio" name={`${ids}-target`} checked={target === 'add'} onChange={() => setTarget('add')} />
                    Add to {current.name}
                  </label>
                </div>
              </fieldset>
            )}
            <p className="text-sm text-muted">
              {fieldCount} field{fieldCount === 1 ? '' : 's'} from {sources.length} flow template{sources.length === 1 ? '' : 's'}.
              A name that is already taken gets the flow template’s name added.
            </p>
            <div className="max-h-80 overflow-auto rounded border border-line">
              <table className="w-full text-left text-sm">
                <caption className="sr-only">{adding ? `Fields to add to ${current.name}` : 'Fields of the new template'}</caption>
                <thead className="bg-sidebar text-xs text-muted">
                  <tr>
                    <th scope="col" className="p-2 font-medium">Field name</th>
                    <th scope="col" className="p-2 font-medium">From</th>
                    <th scope="col" className="p-2 font-medium">Type</th>
                    <th scope="col" className="p-2 font-medium">Rules</th>
                  </tr>
                </thead>
                <tbody>
                  {predicted.map((p) => {
                    const source = sources[p.source];
                    const field = (source.preview.fields || []).find((f) => f.name === p.flowName) || {};
                    return (
                      <tr key={`${p.source}-${p.flowName}`} className="border-t border-line" data-testid={`preview-row-${p.name}`}>
                        <td className="p-2">
                          <span className="font-mono text-xs">{p.name}</span>{' '}
                          {p.renamed && (
                            <Tag tone="warning" title={`“${slugify(p.flowName)}” is already taken`}>
                              renamed
                            </Tag>
                          )}
                        </td>
                        <td className="p-2">
                          {source.templateName}.{p.flowName}
                        </td>
                        <td className="p-2">{DATA_KIND_LABELS[field.kind] || field.dataType || field.kind}</td>
                        <td className="p-2">
                          {(field.rules || []).length === 0
                            ? '—'
                            : field.rules.map((r) => RULE_LABELS[r.ruleType] || r.ruleType).join(', ')}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {step === 'save' && (
          <form id={`${ids}-save`} onSubmit={save} className="space-y-3" noValidate data-testid="build-save">
            {adding ? (
              <p className="text-sm">
                Add {fieldCount} field{fieldCount === 1 ? '' : 's'} and their rules to <strong>{current.name}</strong>.
              </p>
            ) : (
              <Field label="Name of the new template">
                <input
                  className={inputClass}
                  value={name}
                  maxLength={NAME_MAX}
                  onChange={(e) => {
                    setName(e.target.value);
                    setNameTouched(true);
                  }}
                />
              </Field>
            )}
          </form>
        )}

        {problem && (
          <p role="alert" className="text-sm text-danger-ink">
            {problem}
          </p>
        )}
      </div>
    </Modal>
  );
}
