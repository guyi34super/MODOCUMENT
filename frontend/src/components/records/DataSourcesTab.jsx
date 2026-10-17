import { useCallback, useEffect, useId, useMemo, useState } from 'react';
import Modal from '../Modal';
import ConfirmDialog from '../ConfirmDialog';
import { Button, Callout, Card, Spinner, Tag, inputClass } from '../ui';
import { useToast } from '../../context/ToastContext';
import {
  addBlueprintSource,
  deleteBlueprintSource,
  listBlueprintSources,
  saveBlueprintSource,
} from '../../api/doccreation';
import { getTemplates } from '../../api/cache';
import { getAllFields } from '../../api/templates';

/** Region kinds that never take a value from the flow (contract §4, auto-mapping). */
const UNMAPPABLE = new Set(['IMAGE', 'SIGNATURE']);
const ROLE_LABELS = { PRIMARY: 'Primary', LINKED: 'Linked' };

const regionLabel = (r) => r.label?.trim() || r.key;
const normalise = (s) => String(s || '').toLowerCase().replace(/[^a-z0-9]/g, '');
const byRole = (a, b) => (a.role === 'PRIMARY' ? 0 : 1) - (b.role === 'PRIMARY' ? 0 : 1) || (a.id ?? 0) - (b.id ?? 0);

function draftOf(source) {
  return {
    mapping: { ...(source.mapping || {}) },
    linkFieldPrimary: source.linkFieldPrimary ?? '',
    linkFieldThis: source.linkFieldThis ?? '',
  };
}

/** The mapping without unmapped regions, as the PUT expects it. */
function compact(mapping) {
  return Object.fromEntries(Object.entries(mapping).filter(([, field]) => field));
}

function sameDraft(a, b) {
  const ma = compact(a.mapping);
  const mb = compact(b.mapping);
  const keys = new Set([...Object.keys(ma), ...Object.keys(mb)]);
  for (const key of keys) if (ma[key] !== mb[key]) return false;
  return a.linkFieldPrimary === b.linkFieldPrimary && a.linkFieldThis === b.linkFieldThis;
}

/** Adds a flow template as the primary source or as a source linked to it by a key field. */
function AddSourceDialog({ open, onClose, blueprintId, sources, onAdded }) {
  const toast = useToast();
  const ids = useId();
  const primary = sources.find((s) => s.role === 'PRIMARY');
  const [templates, setTemplates] = useState(null);
  const [templateId, setTemplateId] = useState('');
  const [role, setRole] = useState('PRIMARY');
  const [thisFields, setThisFields] = useState([]);
  const [linkFieldPrimary, setLinkFieldPrimary] = useState('');
  const [linkFieldThis, setLinkFieldThis] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState('');

  useEffect(() => {
    if (!open) return;
    setTemplateId('');
    setRole(primary ? 'LINKED' : 'PRIMARY');
    setLinkFieldPrimary('');
    setLinkFieldThis('');
    setProblem('');
    getTemplates()
      .then((list) => setTemplates(list || []))
      .catch((err) => {
        setTemplates([]);
        setProblem(err.message);
      });
    // `primary` is read once per opening.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    setLinkFieldThis('');
    if (!templateId) {
      setThisFields([]);
      return undefined;
    }
    let live = true;
    getAllFields(templateId)
      .then((list) => live && setThisFields((list || []).map((f) => f.fieldName ?? f.name).filter(Boolean)))
      .catch((err) => {
        if (!live) return;
        setThisFields([]);
        setProblem(err.message);
      });
    return () => {
      live = false;
    };
  }, [templateId]);

  const available = (templates || []).filter((t) => !sources.some((s) => String(s.templateId) === String(t.templateId)));
  const linked = role === 'LINKED';
  const ready = Boolean(templateId) && (!linked || (linkFieldPrimary && linkFieldThis));

  const submit = async (event) => {
    event.preventDefault();
    if (!ready) return;
    setBusy(true);
    setProblem('');
    try {
      const added = await addBlueprintSource(blueprintId, {
        templateId: Number(templateId),
        role,
        ...(linked ? { linkFieldPrimary, linkFieldThis } : {}),
      });
      const mapped = Object.values(added?.mapping || {}).filter(Boolean).length;
      toast.success(
        `Added ${added?.templateName || 'the template'} as the ${linked ? 'linked' : 'primary'} source — ${mapped} field${
          mapped === 1 ? '' : 's'
        } mapped automatically`
      );
      onAdded(added);
      onClose();
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
      title="Add data source"
      description="A flow template whose validated records can fill documents made from this template."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" form={`${ids}-form`} disabled={busy || !ready}>
            {busy ? 'Adding…' : 'Add source'}
          </Button>
        </>
      }
    >
      <form id={`${ids}-form`} onSubmit={submit} className="space-y-3">
        {problem && (
          <p role="alert" className="rounded bg-danger-bg px-3 py-2 text-sm text-danger-ink">
            {problem}
          </p>
        )}
        <label className="block text-sm">
          <span className="mb-1 block font-medium">Flow template</span>
          <select
            className={inputClass}
            value={templateId}
            disabled={!templates}
            onChange={(e) => setTemplateId(e.target.value)}
          >
            <option value="">{templates ? 'Choose a template…' : 'Loading templates…'}</option>
            {available.map((t) => (
              <option key={t.templateId} value={t.templateId}>
                {t.name}
              </option>
            ))}
          </select>
        </label>
        <fieldset>
          <legend className="mb-1 text-sm font-medium">Role</legend>
          <div className="flex flex-wrap gap-4 text-sm">
            <label className="flex items-center gap-2">
              <input
                type="radio"
                name={`${ids}-role`}
                value="PRIMARY"
                checked={role === 'PRIMARY'}
                disabled={Boolean(primary)}
                onChange={() => setRole('PRIMARY')}
              />
              Primary
            </label>
            <label className="flex items-center gap-2">
              <input
                type="radio"
                name={`${ids}-role`}
                value="LINKED"
                checked={role === 'LINKED'}
                disabled={!primary}
                onChange={() => setRole('LINKED')}
              />
              Linked
            </label>
          </div>
          <p className="mt-1 text-xs text-muted">
            {primary
              ? `The primary source is ${primary.templateName}. A linked source finds the same person by a shared key, such as an ID number.`
              : 'The first source is the primary one: the person is searched in it.'}
          </p>
        </fieldset>
        {linked && primary && (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <label className="block text-sm">
              <span className="mb-1 block font-medium">Key field in {primary.templateName}</span>
              <select className={inputClass} value={linkFieldPrimary} onChange={(e) => setLinkFieldPrimary(e.target.value)}>
                <option value="">Choose a field…</option>
                {(primary.fields || []).map((f) => (
                  <option key={f.name} value={f.name}>
                    {f.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-sm">
              <span className="mb-1 block font-medium">Key field in the new source</span>
              <select
                className={inputClass}
                value={linkFieldThis}
                disabled={!templateId}
                onChange={(e) => setLinkFieldThis(e.target.value)}
              >
                <option value="">{templateId ? 'Choose a field…' : 'Choose a template first'}</option>
                {thisFields.map((name) => (
                  <option key={name} value={name}>
                    {name}
                  </option>
                ))}
              </select>
            </label>
          </div>
        )}
      </form>
    </Modal>
  );
}

/** One source: its link fields (linked sources), the region → field mapping and the suggestions. */
function SourceCard({ source, primary, regions, draft, dirty, readOnly, saving, onDraft, onSave, onDelete }) {
  const headingId = useId();
  const fields = source.fields || [];
  const suggestionFor = new Map((source.suggestions || []).map((s) => [s.regionKey, s]));
  const pending = regions
    .map((r) => suggestionFor.get(r.key))
    .filter((s) => s && !draft.mapping[s.regionKey]);
  const setMapping = (regionKey, fieldName) => onDraft({ ...draft, mapping: { ...draft.mapping, [regionKey]: fieldName } });
  const acceptAll = () => {
    const mapping = { ...draft.mapping };
    for (const s of pending) mapping[s.regionKey] = s.fieldName;
    onDraft({ ...draft, mapping });
  };
  const mappedCount = Object.values(draft.mapping).filter(Boolean).length;

  return (
    <Card padding="compact">
      <section aria-labelledby={headingId} data-testid={`source-${source.templateId}`}>
        <div className="mb-2 flex flex-wrap items-center gap-2">
          <h2 id={headingId} className="text-base font-medium">
            {source.templateName}
          </h2>
          <Tag tone={source.role === 'PRIMARY' ? 'info' : 'neutral'}>{ROLE_LABELS[source.role] || source.role}</Tag>
          {/* ws34: kept by the server from the data template's mapping. */}
          {source.managed && (
            <Tag tone="success" title="Updated each time the data template or the mapping is saved">
              kept in step with the data template
            </Tag>
          )}
          <span className="text-xs text-muted">
            {mappedCount} of {regions.length} field{regions.length === 1 ? '' : 's'} mapped
          </span>
          <span className="flex-1" />
          {!readOnly && (
            <>
              <Button size="sm" onClick={onSave} disabled={!dirty || saving}>
                {saving ? 'Saving…' : 'Save mapping'}
              </Button>
              <Button size="sm" variant="danger" onClick={onDelete} disabled={saving}>
                Delete source
              </Button>
            </>
          )}
        </div>
        {source.role === 'LINKED' && (
          <div className="mb-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
            <label className="block text-sm">
              <span className="mb-1 block text-xs text-muted">Key field in {primary?.templateName || 'the primary source'}</span>
              <select
                className={inputClass}
                value={draft.linkFieldPrimary}
                disabled={readOnly}
                onChange={(e) => onDraft({ ...draft, linkFieldPrimary: e.target.value })}
              >
                {!draft.linkFieldPrimary && <option value="">Choose a field…</option>}
                {(primary?.fields || []).map((f) => (
                  <option key={f.name} value={f.name}>
                    {f.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-sm">
              <span className="mb-1 block text-xs text-muted">Key field in {source.templateName}</span>
              <select
                className={inputClass}
                value={draft.linkFieldThis}
                disabled={readOnly}
                onChange={(e) => onDraft({ ...draft, linkFieldThis: e.target.value })}
              >
                {!draft.linkFieldThis && <option value="">Choose a field…</option>}
                {fields.map((f) => (
                  <option key={f.name} value={f.name}>
                    {f.name}
                  </option>
                ))}
              </select>
            </label>
          </div>
        )}
        {!readOnly && pending.length > 0 && (
          <div className="mb-2 flex flex-wrap items-center gap-2 text-xs">
            <span className="text-muted">
              {pending.length} suggested match{pending.length === 1 ? '' : 'es'} not applied yet.
            </span>
            <Button size="sm" variant="secondary" onClick={acceptAll}>
              Accept all suggestions ({pending.length})
            </Button>
          </div>
        )}
        <div className="overflow-x-auto rounded-lg border border-line">
          <table className="w-full border-collapse text-[13px]" aria-label={`Mapping for ${source.templateName}`}>
            <thead>
              <tr className="bg-sidebar text-left">
                <th scope="col" className="border-b border-line px-3 py-2 font-medium text-muted">
                  Document field
                </th>
                <th scope="col" className="border-b border-line px-3 py-2 font-medium text-muted">
                  Template field
                </th>
                <th scope="col" className="border-b border-line px-3 py-2 font-medium text-muted">
                  Match
                </th>
              </tr>
            </thead>
            <tbody>
              {regions.map((region) => {
                const label = regionLabel(region);
                const mapped = draft.mapping[region.key] || '';
                const suggestion = suggestionFor.get(region.key);
                const isSuggested = suggestion && mapped === suggestion.fieldName;
                const sameName =
                  mapped && (normalise(mapped) === normalise(region.key) || normalise(mapped) === normalise(region.label));
                return (
                  <tr key={region.key} data-testid={`mapping-row-${region.key}`}>
                    <td className="border-b border-line px-3 py-2 align-top">
                      {label}
                      {label !== region.key && <span className="ml-1 font-mono text-[11px] text-muted">{region.key}</span>}
                    </td>
                    <td className="border-b border-line px-3 py-2 align-top">
                      <select
                        aria-label={`Template field for ${label}`}
                        className={`${inputClass} !py-0.5`}
                        value={mapped}
                        disabled={readOnly}
                        onChange={(e) => setMapping(region.key, e.target.value)}
                      >
                        <option value="">— not mapped —</option>
                        {fields.map((f) => (
                          <option key={f.name} value={f.name}>
                            {f.name}
                            {f.dataType ? ` (${f.dataType})` : ''}
                          </option>
                        ))}
                      </select>
                    </td>
                    <td className="border-b border-line px-3 py-2 align-top">
                      <span className="flex flex-wrap items-center gap-1">
                        {(sameName || (isSuggested && suggestion.sameName)) && <Tag tone="success">same name</Tag>}
                        {isSuggested && !suggestion.sameName && !sameName && (
                          <Tag tone="info">suggested {Number(suggestion.score).toFixed(2)}</Tag>
                        )}
                        {!mapped && suggestion && (
                          <>
                            <Tag tone={suggestion.sameName ? 'success' : 'info'}>
                              {suggestion.sameName ? 'same name' : `suggested ${Number(suggestion.score).toFixed(2)}`}:{' '}
                              {suggestion.fieldName}
                            </Tag>
                            {!readOnly && (
                              <button
                                type="button"
                                className="text-accent hover:underline"
                                aria-label={`Accept ${suggestion.fieldName} for ${label}`}
                                onClick={() => setMapping(region.key, suggestion.fieldName)}
                              >
                                Accept
                              </button>
                            )}
                          </>
                        )}
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>
    </Card>
  );
}

/**
 * The blueprint's "Data sources" (flow-records contract §4): the flow
 * templates whose records fill its documents — one primary, plus linked
 * ones joined to it by a key field — each with a saved region → field
 * mapping. `regions` are the blueprint's fields; `readOnly` for an older
 * version.
 */
export default function DataSourcesTab({ blueprintId, regions, readOnly = false }) {
  const toast = useToast();
  const [sources, setSources] = useState(null);
  const [drafts, setDrafts] = useState({});
  const [error, setError] = useState('');
  const [addOpen, setAddOpen] = useState(false);
  const [savingId, setSavingId] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const [deleteBusy, setDeleteBusy] = useState(false);

  const mappable = useMemo(
    () =>
      [...(regions || [])]
        .filter((r) => !UNMAPPABLE.has(r.kind))
        .sort((a, b) => a.page - b.page || (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.fy - b.fy || a.fx - b.fx),
    [regions]
  );

  const adoptList = useCallback((list) => {
    const sorted = [...(list || [])].sort(byRole);
    setSources(sorted);
    setDrafts(Object.fromEntries(sorted.map((s) => [s.id, draftOf(s)])));
  }, []);

  const load = useCallback(async () => {
    try {
      adoptList(await listBlueprintSources(blueprintId));
      setError('');
    } catch (err) {
      setSources((current) => current || []);
      setError(err.message);
    }
  }, [blueprintId, adoptList]);

  useEffect(() => {
    load();
  }, [load]);

  const primary = sources?.find((s) => s.role === 'PRIMARY');

  const replaceSource = (updated) => {
    setSources((list) => [...list.filter((s) => s.id !== updated.id), updated].sort(byRole));
    setDrafts((current) => ({ ...current, [updated.id]: draftOf(updated) }));
  };

  const save = async (source) => {
    const draft = drafts[source.id];
    setSavingId(source.id);
    setError('');
    try {
      const body = { mapping: compact(draft.mapping) };
      if (source.role === 'LINKED') {
        body.linkFieldPrimary = draft.linkFieldPrimary;
        body.linkFieldThis = draft.linkFieldThis;
      }
      const saved = await saveBlueprintSource(blueprintId, source.id, body);
      replaceSource(saved && typeof saved === 'object' && saved.id != null ? saved : { ...source, ...body, mapping: body.mapping });
      toast.success(`Mapping saved for ${source.templateName}`);
    } catch (err) {
      setError(err.message);
    } finally {
      setSavingId(null);
    }
  };

  const confirmDelete = async () => {
    const source = deleting;
    setDeleteBusy(true);
    setError('');
    try {
      await deleteBlueprintSource(blueprintId, source.id);
      setSources((list) => list.filter((s) => s.id !== source.id));
      toast.success(`Removed ${source.templateName} from the data sources`);
    } catch (err) {
      setError(
        err.status === 409 && source.role === 'PRIMARY'
          ? `${source.templateName} is the primary source and linked sources depend on it. Delete the linked sources first.`
          : err.message
      );
    } finally {
      setDeleteBusy(false);
      setDeleting(null);
    }
  };

  if (!sources) return <Spinner label="Loading data sources…" />;

  return (
    <div className="space-y-4" data-testid="data-sources">
      {error && (
        <div role="alert" className="flex items-start justify-between rounded-md bg-danger-bg px-4 py-3 text-sm text-danger-ink">
          <span>{error}</span>
          <button type="button" onClick={() => setError('')} className="ml-4 cursor-pointer font-bold" aria-label="Dismiss">
            ×
          </button>
        </div>
      )}
      <div className="flex flex-wrap items-center gap-3">
        <p className="flex-1 text-sm text-muted">
          Flow templates whose validated records fill documents made from this template. Fields with the same or a
          similar name are matched automatically; change any match below.
        </p>
        {!readOnly && <Button onClick={() => setAddOpen(true)}>Add data source</Button>}
      </div>
      {readOnly && (
        <Callout variant="info" title="Read-only">
          Data sources belong to the template, not to a version. Open the current version to change them.
        </Callout>
      )}
      {sources.length === 0 ? (
        <p className="text-sm text-muted">No data sources yet.</p>
      ) : (
        sources.map((source) => {
          const draft = drafts[source.id] || draftOf(source);
          return (
            <SourceCard
              key={source.id}
              source={source}
              primary={primary}
              regions={mappable}
              draft={draft}
              dirty={!sameDraft(draft, draftOf(source))}
              readOnly={readOnly}
              saving={savingId === source.id}
              onDraft={(next) => setDrafts((current) => ({ ...current, [source.id]: next }))}
              onSave={() => save(source)}
              onDelete={() => setDeleting(source)}
            />
          );
        })
      )}

      <AddSourceDialog
        open={addOpen}
        onClose={() => setAddOpen(false)}
        blueprintId={blueprintId}
        sources={sources}
        onAdded={(added) => (added && added.id != null ? replaceSource(added) : load())}
      />
      <ConfirmDialog
        open={Boolean(deleting)}
        title="Delete data source"
        confirmLabel="Delete source"
        tone="danger"
        busy={deleteBusy}
        onConfirm={confirmDelete}
        onCancel={() => setDeleting(null)}
      >
        {deleting && (
          <p>
            Remove {deleting.templateName} and its mapping? Documents already filled keep their values.
          </p>
        )}
      </ConfirmDialog>
    </div>
  );
}
