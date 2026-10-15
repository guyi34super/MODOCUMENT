import { useEffect, useId, useMemo, useRef, useState } from 'react';
import Modal from '../Modal';
import Pager from '../Pager';
import DataTable from '../DataTable';
import { Button, Callout, Spinner, Tag, inputClass } from '../ui';
import { useToast } from '../../context/ToastContext';
import { fillFromRecord, getFillPreview, listBlueprintSources } from '../../api/doccreation';
import { getRecord, recordLabel, searchRecords } from '../../api/records';
import { getTemplates } from '../../api/cache';
import { getAllFields } from '../../api/templates';

/** How long typing pauses before the record search runs. */
export const SEARCH_DEBOUNCE_MS = 300;
export const RECORD_PAGE_SIZE = 20;
const OTHER = '__other';
const MAX_COLUMNS = 4;

const sourceValue = (templateId) => `t:${templateId}`;
const filled = (value) => value != null && String(value).trim() !== '';

/** The record table's columns: the template's fields that the page's records carry, at most four. */
function columnKeys(items, fields) {
  const present = [];
  for (const item of items) {
    for (const key of Object.keys(item.values || {})) if (!present.includes(key)) present.push(key);
  }
  const ordered = fields.length ? [...fields.filter((f) => present.includes(f)), ...present.filter((k) => !fields.includes(k))] : present;
  return ordered.slice(0, MAX_COLUMNS);
}

/**
 * "Fill from data" (flow-records contract §4): pick a data source (the
 * blueprint's saved sources first, then any readable template), search its
 * validated records, preview what one record fills, choose the fields and
 * the linked records, then apply. `onBeforeFill` runs before the preview
 * and the apply (the builder saves pending typing there, so current values
 * are accurate); `onFilled(dto)` receives the updated document.
 */
export default function FillFromDataDialog({ open, onClose, instance, onBeforeFill, onFilled }) {
  const toast = useToast();
  const ids = useId();
  const [sources, setSources] = useState(null);
  const [choice, setChoice] = useState('');
  const [templates, setTemplates] = useState(null);
  const [otherTemplateId, setOtherTemplateId] = useState('');
  const [fields, setFields] = useState([]);
  const [field, setField] = useState('');
  const [q, setQ] = useState('');
  const [debouncedQ, setDebouncedQ] = useState('');
  const [page, setPage] = useState(0);
  const [results, setResults] = useState(null);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState('');
  // Preview step.
  const [record, setRecord] = useState(null);
  const [preview, setPreview] = useState(null);
  const [checked, setChecked] = useState({});
  const [emptyOnly, setEmptyOnly] = useState(false);
  const [linkedChoice, setLinkedChoice] = useState({});
  const [linkedRecords, setLinkedRecords] = useState({});
  const [busy, setBusy] = useState(false);
  const searchSeq = useRef(0);

  const blueprintId = instance?.blueprintId;
  const templateId = choice === OTHER ? otherTemplateId : choice.startsWith('t:') ? choice.slice(2) : '';
  const source = sources?.find((s) => String(s.templateId) === String(templateId));

  // Opening starts over: load the blueprint's sources and preselect the primary one.
  useEffect(() => {
    if (!open) return undefined;
    let live = true;
    setSources(null);
    setChoice('');
    setOtherTemplateId('');
    setQ('');
    setDebouncedQ('');
    setField('');
    setPage(0);
    setResults(null);
    setRecord(null);
    setPreview(null);
    setError('');
    setEmptyOnly(false);
    listBlueprintSources(blueprintId)
      .then((list) => {
        if (!live) return;
        const sorted = [...(list || [])].sort(
          (a, b) => (a.role === 'PRIMARY' ? 0 : 1) - (b.role === 'PRIMARY' ? 0 : 1)
        );
        setSources(sorted);
        setChoice(sorted.length ? sourceValue(sorted[0].templateId) : OTHER);
      })
      .catch((err) => {
        if (!live) return;
        setSources([]);
        setChoice(OTHER);
        setError(err.message);
      });
    return () => {
      live = false;
    };
  }, [open, blueprintId]);

  // "Other templates…": every template the user can read.
  useEffect(() => {
    if (!open || choice !== OTHER || templates) return;
    getTemplates()
      .then((list) => setTemplates(list || []))
      .catch((err) => {
        setTemplates([]);
        setError(err.message);
      });
  }, [open, choice, templates]);

  // The selected template's field names, for the field filter and the column order.
  useEffect(() => {
    setField('');
    if (!templateId) {
      setFields([]);
      return undefined;
    }
    if (source?.fields) {
      setFields(source.fields.map((f) => f.name));
      return undefined;
    }
    let live = true;
    getAllFields(templateId)
      .then((list) => live && setFields((list || []).map((f) => f.fieldName ?? f.name).filter(Boolean)))
      .catch(() => live && setFields([]));
    return () => {
      live = false;
    };
  }, [templateId, source]);

  // Typing settles for SEARCH_DEBOUNCE_MS before the search runs; a new query starts at page 1.
  useEffect(() => {
    if (q === debouncedQ) return undefined;
    const timer = setTimeout(() => {
      setDebouncedQ(q);
      setPage(0);
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [q, debouncedQ]);

  useEffect(() => {
    if (!open || !templateId) {
      setResults(null);
      return;
    }
    const seq = (searchSeq.current += 1);
    setSearching(true);
    searchRecords({ templateId, q: debouncedQ, field: field || undefined, page, size: RECORD_PAGE_SIZE })
      .then((data) => {
        if (seq !== searchSeq.current) return;
        setResults({
          items: data?.items || [],
          page: data?.page ?? page,
          size: data?.size ?? RECORD_PAGE_SIZE,
          total: data?.total ?? (data?.items || []).length,
        });
        setError('');
      })
      .catch((err) => seq === searchSeq.current && setError(err.message))
      .finally(() => seq === searchSeq.current && setSearching(false));
  }, [open, templateId, debouncedQ, field, page]);

  const templateName = (id) => {
    const key = String(id);
    return (
      preview?.linked?.find((l) => String(l.templateId) === key)?.templateName ||
      (record && String(record.templateId) === key ? record.templateName : null) ||
      sources?.find((s) => String(s.templateId) === key)?.templateName ||
      templates?.find((t) => String(t.templateId) === key)?.name ||
      `Template ${id}`
    );
  };

  const use = async (item) => {
    setBusy(true);
    setError('');
    try {
      await onBeforeFill?.();
      const data = await getFillPreview(instance.id, item.recordId);
      const list = data?.fields || [];
      setRecord(item);
      setPreview(data);
      setChecked(Object.fromEntries(list.map((f) => [f.regionKey, !emptyOnly || !filled(f.currentValue)])));
      const linked = data?.linked || [];
      setLinkedChoice(Object.fromEntries(linked.map((l) => [l.templateId, l.recordId])));
      setLinkedRecords({});
      // Linked matches with alternatives: load each candidate for a short label.
      const wanted = linked.filter((l) => l.alternatives?.length).flatMap((l) => [l.recordId, ...l.alternatives]);
      if (wanted.length) {
        Promise.all(wanted.map((id) => getRecord(id).catch(() => null))).then((loaded) =>
          setLinkedRecords(Object.fromEntries(loaded.filter(Boolean).map((r) => [r.recordId, r])))
        );
      }
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const toggleEmptyOnly = (on) => {
    setEmptyOnly(on);
    setChecked((current) =>
      Object.fromEntries(
        (preview?.fields || []).map((f) => [f.regionKey, filled(f.currentValue) ? !on : current[f.regionKey]])
      )
    );
  };

  /** The value a field gets: from the chosen linked record when the user picked another match. */
  const valueOf = (f) => {
    const link = preview?.linked?.find((l) => String(l.templateId) === String(f.sourceTemplateId));
    if (!link) return f.value;
    const chosen = linkedChoice[link.templateId];
    if (chosen == null || chosen === link.recordId) return f.value;
    const alt = linkedRecords[chosen];
    return alt ? (alt.values?.[f.sourceField] ?? '') : f.value;
  };

  const previewFields = preview?.fields || [];
  const selectedKeys = previewFields.filter((f) => checked[f.regionKey]).map((f) => f.regionKey);
  const primaryName = record ? record.templateName || templateName(record.templateId) : '';

  const apply = async () => {
    if (!preview || selectedKeys.length === 0) return;
    setBusy(true);
    try {
      await onBeforeFill?.();
      const body = {
        recordId: preview.recordId ?? record.recordId,
        linkedRecordIds: Object.fromEntries(
          Object.entries(linkedChoice).map(([tid, rid]) => [String(tid), Number(rid)])
        ),
        fields: selectedKeys,
        overwrite: emptyOnly ? 'EMPTY_ONLY' : 'ALL',
      };
      const n = selectedKeys.length;
      const dto = await toast.promise(() => fillFromRecord(instance.id, body), {
        loading: 'Filling the document…',
        success: `${n} field${n === 1 ? '' : 's'} filled from ${primaryName}`,
        error: (err) => err.message,
      });
      onFilled?.(dto);
      onClose();
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const keys = useMemo(() => columnKeys(results?.items || [], fields), [results, fields]);
  const columns = [
    ...keys.map((key) => ({ key, label: key, render: (row) => String(row.values?.[key] ?? '') })),
    {
      key: '__use',
      label: '',
      srLabel: 'Use',
      render: (row) => (
        <Button size="sm" variant="secondary" onClick={() => use(row)} disabled={busy} aria-label={`Use ${recordLabel(row)}`}>
          Use
        </Button>
      ),
    },
  ];

  const inPreview = Boolean(preview);
  const footer = inPreview ? (
    <>
      <Button
        variant="secondary"
        onClick={() => {
          setPreview(null);
          setRecord(null);
        }}
        disabled={busy}
      >
        Back to results
      </Button>
      <Button onClick={apply} disabled={busy || selectedKeys.length === 0}>
        Apply
      </Button>
    </>
  ) : (
    <Button variant="secondary" onClick={onClose}>
      Close
    </Button>
  );

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      title="Fill from data"
      description="Find a person (a validated record) in the data flow; their values fill this document's fields."
      footer={footer}
    >
      {error && (
        <p role="alert" className="mb-3 rounded bg-danger-bg px-3 py-2 text-sm text-danger-ink">
          {error}
        </p>
      )}
      {!inPreview ? (
        <div className="space-y-3" data-testid="fill-search">
          {sources == null ? (
            <Spinner label="Loading data sources…" />
          ) : (
            <>
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <label className="block text-sm">
                  <span className="mb-1 block font-medium">Data source</span>
                  <select
                    className={inputClass}
                    value={choice}
                    onChange={(e) => {
                      setChoice(e.target.value);
                      setPage(0);
                    }}
                  >
                    {sources.map((s) => (
                      <option key={s.id} value={sourceValue(s.templateId)}>
                        {s.templateName} ({s.role === 'PRIMARY' ? 'primary' : 'linked'})
                      </option>
                    ))}
                    <option value={OTHER}>Other templates…</option>
                  </select>
                </label>
                {choice === OTHER && (
                  <label className="block text-sm">
                    <span className="mb-1 block font-medium">Template</span>
                    <select
                      className={inputClass}
                      value={otherTemplateId}
                      onChange={(e) => {
                        setOtherTemplateId(e.target.value);
                        setPage(0);
                      }}
                      disabled={!templates}
                    >
                      <option value="">Choose a template…</option>
                      {(templates || [])
                        .filter((t) => !sources.some((s) => String(s.templateId) === String(t.templateId)))
                        .map((t) => (
                          <option key={t.templateId} value={t.templateId}>
                            {t.name}
                          </option>
                        ))}
                    </select>
                  </label>
                )}
                <label className="block text-sm">
                  <span className="mb-1 block font-medium">Search records</span>
                  <input
                    type="search"
                    className={inputClass}
                    value={q}
                    placeholder="Name, ID number…"
                    disabled={!templateId}
                    onChange={(e) => setQ(e.target.value)}
                  />
                </label>
                <label className="block text-sm">
                  <span className="mb-1 block font-medium">Search in</span>
                  <select
                    className={inputClass}
                    value={field}
                    disabled={!templateId}
                    onChange={(e) => {
                      setField(e.target.value);
                      setPage(0);
                    }}
                  >
                    <option value="">All fields</option>
                    {fields.map((name) => (
                      <option key={name} value={name}>
                        {name}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
              {!templateId ? (
                <p className="text-sm text-muted">Choose a template to search its records.</p>
              ) : results == null ? (
                <Spinner label="Searching…" />
              ) : (
                <div aria-busy={searching || undefined}>
                  <DataTable
                    caption="Records"
                    columns={columns}
                    rows={results.items}
                    emptyMessage={debouncedQ ? 'No records match this search.' : 'No records yet for this template.'}
                    maxHeight="22rem"
                  />
                  <Pager
                    page={results.page}
                    size={results.size}
                    total={results.total}
                    onPage={setPage}
                    label="Records"
                    disabled={searching}
                  />
                </div>
              )}
            </>
          )}
        </div>
      ) : (
        <div className="space-y-3" data-testid="fill-preview">
          <p className="text-sm">
            From <strong>{primaryName}</strong>: {recordLabel(record)}
          </p>
          {preview.mappingSource === 'AUTO' && (
            <Callout variant="info">
              This template has no saved mapping for the document: fields were matched by name.
            </Callout>
          )}
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={emptyOnly} onChange={(e) => toggleEmptyOnly(e.target.checked)} />
            Fill empty fields only
          </label>
          {(preview.linked || []).map((link) => {
            const alternatives = link.alternatives || [];
            const selectId = `${ids}-linked-${link.templateId}`;
            return (
              <div key={link.templateId} className="rounded border border-line p-2 text-sm" data-testid={`linked-${link.templateId}`}>
                <p>
                  Also from <strong>{link.templateName}</strong> matched by <code>{link.matchedBy}</code>
                </p>
                {alternatives.length > 0 && (
                  <div className="mt-1">
                    <label htmlFor={selectId} className="mb-1 block text-xs text-muted">
                      Record from {link.templateName} ({alternatives.length + 1} matches)
                    </label>
                    <select
                      id={selectId}
                      className={inputClass}
                      value={String(linkedChoice[link.templateId] ?? link.recordId)}
                      onChange={(e) =>
                        setLinkedChoice((current) => ({ ...current, [link.templateId]: Number(e.target.value) }))
                      }
                    >
                      {[link.recordId, ...alternatives].map((rid) => (
                        <option key={rid} value={String(rid)}>
                          {linkedRecords[rid] ? recordLabel(linkedRecords[rid]) : `Record ${rid}`}
                          {rid === link.recordId ? ' (newest)' : ''}
                        </option>
                      ))}
                    </select>
                  </div>
                )}
              </div>
            );
          })}
          {previewFields.length === 0 ? (
            <p className="text-sm text-muted">This record has no values for this document’s fields.</p>
          ) : (
            <>
              <DataTable
                caption="Values to fill"
                maxHeight="22rem"
                rows={previewFields}
                columns={[
                  {
                    key: 'use',
                    label: '',
                    srLabel: 'Fill',
                    render: (f) => (
                      <input
                        type="checkbox"
                        aria-label={`Fill ${f.label || f.regionKey}`}
                        checked={Boolean(checked[f.regionKey])}
                        disabled={emptyOnly && filled(f.currentValue)}
                        onChange={(e) => setChecked((current) => ({ ...current, [f.regionKey]: e.target.checked }))}
                      />
                    ),
                  },
                  { key: 'label', label: 'Field', render: (f) => f.label || f.regionKey },
                  {
                    key: 'value',
                    label: 'Value',
                    render: (f) => <span data-testid={`fill-value-${f.regionKey}`}>{valueOf(f)}</span>,
                  },
                  {
                    key: 'from',
                    label: 'From',
                    render: (f) => (
                      <span className="text-muted">
                        {templateName(f.sourceTemplateId)}.{f.sourceField}
                        {f.sameName && (
                          <>
                            {' '}
                            <Tag tone="success">same name</Tag>
                          </>
                        )}
                      </span>
                    ),
                  },
                  {
                    key: 'current',
                    label: 'Current value',
                    render: (f) =>
                      filled(f.currentValue) ? (
                        <span>
                          {f.currentValue}
                          {checked[f.regionKey] && (
                            <>
                              {' '}
                              <Tag tone="warning">will be replaced</Tag>
                            </>
                          )}
                        </span>
                      ) : (
                        <span className="text-muted">empty</span>
                      ),
                  },
                ]}
              />
              <p className="text-xs text-muted" aria-live="polite">
                {selectedKeys.length} of {previewFields.length} field{previewFields.length === 1 ? '' : 's'} selected
              </p>
            </>
          )}
        </div>
      )}
    </Modal>
  );
}
