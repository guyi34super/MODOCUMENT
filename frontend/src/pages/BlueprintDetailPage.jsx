import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import {
  blueprintPagePath,
  downloadBlueprintFile,
  getBlueprint,
  listBlueprintVersions,
  saveOverlay,
  uploadEditImage,
} from '../api/doccreation';
import Modal from '../components/Modal';
import PageHeader from '../components/PageHeader';
import { Button, Callout, Card, ErrorBanner, Field, Spinner, Tag, inputClass } from '../components/ui';
import { useToast } from '../context/ToastContext';
import useUnsavedGuard from '../hooks/useUnsavedGuard';
import PageView from '../components/pdf/PageView';
import RegionBox from '../components/pdf/RegionBox';
import DrawLayer from '../components/pdf/DrawLayer';
import EditBox, { describeEdit } from '../components/pdf/EditBox';
import RegionPropertiesPanel, { keyProblem } from '../components/pdf/RegionPropertiesPanel';
import ThumbnailStrip from '../components/pdf/ThumbnailStrip';
import {
  EDIT_LABELS,
  KIND_COLORS,
  KIND_LABELS,
  LOW_CONFIDENCE,
  newEditId,
  overlapArea,
  uniqueKey,
} from '../components/pdf/geometry';

const TOOLS = [
  { value: 'select', label: 'Select', draw: null },
  { value: 'field', label: 'Add field', draw: 'Draw a new field: drag on the page, or press Enter to add one in the centre' },
  { value: 'remove', label: 'Remove content', draw: 'Draw a box over content to remove: drag on the page, or press Enter' },
  { value: 'replace', label: 'Edit text', draw: 'Draw a box over the text to replace: drag on the page, or press Enter' },
  { value: 'text', label: 'Add text', draw: 'Draw where the new text goes: drag on the page, or press Enter' },
  { value: 'image', label: 'Add image/logo', draw: 'Draw where the image goes: drag on the page, or press Enter' },
];

const VIEWS = [
  { value: 'original', label: 'Original' },
  { value: 'template', label: 'Template' },
  { value: 'overlay', label: 'Overlay' },
];

const REGION_FIELDS = [
  'key', 'label', 'group', 'kind', 'page', 'fx', 'fy', 'fw', 'fh', 'fontName', 'fontSize', 'color',
  'align', 'maxLines', 'acroFieldName', 'required', 'sortOrder', 'source', 'confidence',
];
const EDIT_FIELDS = [
  'id', 'kind', 'page', 'fx', 'fy', 'fw', 'fh', 'text', 'fontName', 'fontSize', 'color', 'imageKey',
  'source', 'confidence',
];

const pick = (object, fields) => Object.fromEntries(fields.map((f) => [f, object[f] ?? null]));

let uidCounter = 0;
const withUid = (region) => ({ ...region, _uid: `r${(uidCounter += 1)}` });

const isLow = (item) => item.confidence != null && item.confidence < LOW_CONFIDENCE;
const regionName = (r) => r.label?.trim() || `Unlabelled field (${r.key})`;

/** The review checklist: what to look at before the template is saved. */
function buildChecklist(regions, edits) {
  const items = [];
  for (const r of regions) {
    if (!r.label?.trim()) {
      items.push({
        id: `unlabelled-${r._uid}`,
        text: `Field “${r.key}” on page ${r.page + 1} has no label`,
        target: { type: 'region', uid: r._uid, page: r.page },
      });
    }
  }
  const counts = new Map();
  for (const r of regions) counts.set(r.key, (counts.get(r.key) || 0) + 1);
  for (const [key, count] of counts) {
    if (count > 1) {
      const first = regions.find((r) => r.key === key);
      items.push({
        id: `duplicate-${key}`,
        text: `Key “${key}” is used by ${count} fields`,
        target: { type: 'region', uid: first._uid, page: first.page },
      });
    }
  }
  for (let i = 0; i < regions.length; i += 1) {
    for (let j = i + 1; j < regions.length; j += 1) {
      if (overlapArea(regions[i], regions[j]) > 0) {
        items.push({
          id: `overlap-${regions[i]._uid}-${regions[j]._uid}`,
          text: `“${regionName(regions[i])}” and “${regionName(regions[j])}” overlap on page ${regions[i].page + 1}`,
          target: { type: 'region', uid: regions[i]._uid, page: regions[i].page },
        });
      }
    }
  }
  for (const r of regions) {
    if (isLow(r)) {
      items.push({
        id: `low-region-${r._uid}`,
        text: `Check field “${regionName(r)}” on page ${r.page + 1} (low confidence)`,
        target: { type: 'region', uid: r._uid, page: r.page },
      });
    }
  }
  for (const e of edits) {
    if (e.kind === 'REMOVE' && isLow(e)) {
      items.push({
        id: `low-edit-${e.id}`,
        text: `Check the removal${e.removedText ? ` of “${e.removedText}”` : ''} on page ${e.page + 1} (low confidence)`,
        target: { type: 'edit', id: e.id, page: e.page },
      });
    }
  }
  return items;
}

function VersionHistory({ versions, shownVersion, onView }) {
  if (!versions) return <Spinner />;
  return (
    <Card className="mb-4">
      <h2 className="mb-3 text-base font-medium">Version history</h2>
      <ul className="divide-y divide-line">
        {versions.map((v) => (
          <li key={v.version} className="flex flex-wrap items-center gap-3 py-2 text-sm">
            <span className="w-10 font-medium">v{v.version}</span>
            {v.current && <Tag tone="success">current</Tag>}
            <span className="text-muted">
              {typeof v.regionCount === 'number'
                ? `${v.regionCount} field${v.regionCount === 1 ? '' : 's'} · `
                : ''}
              {v.documentCount ?? 0} document{v.documentCount === 1 ? '' : 's'}
              {v.createdDate ? ` · ${new Date(v.createdDate).toLocaleString()}` : ''}
            </span>
            {v.note && <span className="italic text-muted">“{v.note}”</span>}
            <span className="flex-1" />
            {v.version !== shownVersion && (
              <Button size="sm" variant="secondary" onClick={() => onView(v)}>
                View v{v.version}
              </Button>
            )}
          </li>
        ))}
      </ul>
    </Card>
  );
}

/** Properties of a selected template edit: its text, or restore/delete it. */
function EditProperties({ edit, draft, readOnly, onChange, onRemove }) {
  const textual = edit.kind === 'REPLACE_TEXT' || edit.kind === 'ADD_TEXT';
  const low = isLow(edit);
  return (
    <div className="space-y-3" data-testid="edit-properties">
      <p className="text-sm font-medium">
        {EDIT_LABELS[edit.kind]} · page {edit.page + 1}
        {edit.source === 'DATA_REMOVAL' && <span className="font-normal text-muted"> · found in the data</span>}
      </p>
      {draft && edit.removedText && (
        <p className={`rounded px-2 py-1 text-xs ${low ? 'bg-amber-100 text-amber-900' : 'bg-pill'}`}>
          Removed: “{edit.removedText}”{low && ' — low confidence, please check'}
        </p>
      )}
      {textual && (
        <>
          <Field label="Text">
            <textarea
              className={inputClass}
              rows={3}
              value={edit.text || ''}
              readOnly={readOnly}
              onChange={(e) => onChange({ text: e.target.value })}
            />
          </Field>
          <Field label="Text size (pt)">
            <input
              type="number"
              min={4}
              max={72}
              step={0.5}
              className={inputClass}
              value={edit.fontSize ?? 10}
              readOnly={readOnly}
              onChange={(e) => onChange({ fontSize: Math.max(4, Number(e.target.value) || 10) })}
            />
          </Field>
        </>
      )}
      {!readOnly && (
        <Button variant={edit.kind === 'REMOVE' ? 'secondary' : 'danger'} size="sm" onClick={onRemove}>
          {edit.kind === 'REMOVE' ? 'Restore original content' : 'Delete this change'}
        </Button>
      )}
    </div>
  );
}

/** Asks for the text (Edit text / Add text) or the image (Add image/logo) of a drawn box. */
function EditContentDialog({ pending, blueprintId, onCancel, onDone }) {
  const toast = useToast();
  const [text, setText] = useState('');
  const [fontSize, setFontSize] = useState(10);
  const [file, setFile] = useState(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState('');
  const isImage = pending?.tool === 'image';
  const title = !pending
    ? ''
    : isImage
      ? 'Add an image or logo'
      : pending.tool === 'replace'
        ? 'Replace the text in this box'
        : 'Add text';

  useEffect(() => {
    setText('');
    setFile(null);
    setProblem('');
    setFontSize(10);
  }, [pending]);

  const submit = async (event) => {
    event.preventDefault();
    if (isImage) {
      if (!file) return;
      if (!/\.(png|jpe?g)$/i.test(file.name)) {
        setProblem('Use a PNG or JPEG image.');
        return;
      }
      setBusy(true);
      try {
        const { imageKey } = await toast.promise(() => uploadEditImage(blueprintId, file), {
          loading: 'Uploading the image…',
          success: 'Image added — save the template to apply it',
          error: (err) => err.message,
        });
        onDone({ kind: 'ADD_IMAGE', imageKey });
      } catch {
        // The error toast is already shown.
      } finally {
        setBusy(false);
      }
      return;
    }
    if (!text.trim()) return;
    onDone({
      kind: pending.tool === 'replace' ? 'REPLACE_TEXT' : 'ADD_TEXT',
      text,
      fontSize: Number(fontSize) || 10,
      color: '#000000',
    });
  };

  return (
    <Modal
      open={Boolean(pending)}
      onClose={onCancel}
      title={title}
      footer={
        <>
          <Button variant="secondary" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" form="edit-content-form" disabled={busy || (isImage ? !file : !text.trim())}>
            {isImage ? (busy ? 'Uploading…' : 'Add image') : pending?.tool === 'replace' ? 'Replace text' : 'Add text'}
          </Button>
        </>
      }
    >
      <form id="edit-content-form" onSubmit={submit} className="space-y-3">
        {isImage ? (
          <>
            <Field label="Image file" hint="PNG or JPEG; it is fitted into the box, keeping its proportions">
              <input
                type="file"
                accept=".png,.jpg,.jpeg,image/png,image/jpeg"
                className="block w-full text-sm"
                onChange={(e) => {
                  setProblem('');
                  setFile(e.target.files?.[0] || null);
                }}
              />
            </Field>
            {problem && (
              <p role="alert" className="text-sm text-danger-ink">
                {problem}
              </p>
            )}
          </>
        ) : (
          <>
            {pending?.tool === 'replace' && (
              <p className="text-muted">
                The original text inside the box is removed and your text is written in its place.
              </p>
            )}
            <Field label={pending?.tool === 'replace' ? 'Replacement text' : 'Text'}>
              <textarea className={inputClass} rows={3} value={text} onChange={(e) => setText(e.target.value)} />
            </Field>
            <Field label="Text size (pt)">
              <input
                type="number"
                min={4}
                max={72}
                step={0.5}
                className={inputClass}
                value={fontSize}
                onChange={(e) => setFontSize(e.target.value)}
              />
            </Field>
          </>
        )}
      </form>
    </Modal>
  );
}

export default function BlueprintDetailPage() {
  const { id } = useParams();
  const toast = useToast();
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedVersion = searchParams.get('version') ? Number(searchParams.get('version')) : undefined;

  const [blueprint, setBlueprint] = useState(null);
  const [regions, setRegions] = useState([]);
  const [edits, setEdits] = useState([]);
  const [dirty, setDirty] = useState(false);
  const [selection, setSelection] = useState(null);
  const [tool, setTool] = useState('select');
  const [view, setView] = useState('overlay');
  const [pageIndex, setPageIndex] = useState(0);
  const [pending, setPending] = useState(null);
  const [note, setNote] = useState('');
  const [saving, setSaving] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [versions, setVersions] = useState(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [error, setError] = useState('');
  const [focusTarget, setFocusTarget] = useState(null);
  const propertiesRef = useRef(null);

  const adopt = useCallback((bp) => {
    setBlueprint(bp);
    setRegions((bp.regions || []).map(withUid));
    setEdits(bp.edits || []);
    setDirty(false);
    setSelection(null);
    setTool('select');
    setPageIndex((index) => Math.min(index, Math.max(0, (bp.pages?.length || 1) - 1)));
  }, []);

  const load = useCallback(async () => {
    try {
      adopt(await getBlueprint(id, requestedVersion));
      setError('');
    } catch (err) {
      setError(err.message);
    }
  }, [id, requestedVersion, adopt]);

  const loadVersions = useCallback(async () => {
    try {
      setVersions(await listBlueprintVersions(id));
    } catch (err) {
      setError(err.message);
    }
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (historyOpen) loadVersions();
  }, [historyOpen, loadVersions]);

  // Jump-to / new box: move focus once the target is on screen.
  useEffect(() => {
    if (!focusTarget) return;
    const element =
      focusTarget === 'properties'
        ? propertiesRef.current?.querySelector('input, textarea, select, button')
        : document.getElementById(focusTarget);
    if (element) {
      element.focus();
      setFocusTarget(null);
    }
  });

  const isDraft = blueprint?.status === 'DRAFT';
  const readOnly = Boolean(
    blueprint && !isDraft && requestedVersion != null && blueprint.version !== blueprint.currentVersion
  );
  const overlayVisible = view !== 'template';
  const canEdit = Boolean(blueprint) && !readOnly && overlayVisible;
  const pages = blueprint?.pages || [];
  const page = pages[pageIndex];

  useUnsavedGuard(dirty && !readOnly, 'You have unsaved template changes. Leave without saving them?');

  const checklist = useMemo(() => buildChecklist(regions, edits), [regions, edits]);
  const keyBlocking = regions.some((r) => keyProblem(r.key, regions, r));

  const selectedRegion =
    selection?.type === 'region' ? regions.find((r) => r._uid === selection.uid) : null;
  const selectedEdit = selection?.type === 'edit' ? edits.find((e) => e.id === selection.id) : null;

  const select = (target, { focus = false } = {}) => {
    if (!target) {
      setSelection(null);
      return;
    }
    setSelection(target.type === 'region' ? { type: 'region', uid: target.uid } : { type: 'edit', id: target.id });
    if (target.page != null) setPageIndex(target.page);
    if (focus) {
      if (!overlayVisible) setView('overlay');
      setFocusTarget(target.type === 'region' ? `region-box-${target.uid}` : `edit-box-${target.id}`);
    }
  };

  const updateRegion = (uid, patch) => {
    setRegions((list) => list.map((r) => (r._uid === uid ? { ...r, ...patch } : r)));
    setDirty(true);
  };
  const deleteRegion = (uid) => {
    setRegions((list) => list.filter((r) => r._uid !== uid));
    setSelection(null);
    setDirty(true);
  };
  const updateEdit = (editId, patch) => {
    setEdits((list) => list.map((e) => (e.id === editId ? { ...e, ...patch } : e)));
    setDirty(true);
  };
  const deleteEdit = (editId) => {
    setEdits((list) => list.filter((e) => e.id !== editId));
    setSelection((current) => (current?.type === 'edit' && current.id === editId ? null : current));
    setDirty(true);
  };

  const addEdit = (box, fields) => {
    const edit = {
      id: newEditId(),
      page: pageIndex,
      ...box,
      text: null,
      fontName: null,
      fontSize: null,
      color: null,
      imageKey: null,
      source: 'USER',
      confidence: 1,
      removedText: null,
      ...fields,
    };
    setEdits((list) => [...list, edit]);
    setSelection({ type: 'edit', id: edit.id });
    setDirty(true);
    return edit;
  };

  const onDraw = (box) => {
    if (tool === 'field') {
      const taken = new Set(regions.map((r) => r.key));
      const region = withUid({
        key: uniqueKey('field', taken),
        label: '',
        group: null,
        kind: 'TEXT',
        page: pageIndex,
        ...box,
        fontName: 'Helvetica',
        fontSize: 0,
        color: '#000000',
        align: 'LEFT',
        maxLines: 1,
        acroFieldName: null,
        required: false,
        sortOrder: regions.reduce((max, r) => Math.max(max, r.sortOrder ?? 0), -1) + 1,
        source: 'MANUAL',
        confidence: 1,
        sampleValue: null,
      });
      setRegions((list) => [...list, region]);
      setSelection({ type: 'region', uid: region._uid });
      setDirty(true);
      setTool('select');
      setFocusTarget('properties');
      return;
    }
    if (tool === 'remove') {
      const edit = addEdit(box, { kind: 'REMOVE' });
      setTool('select');
      setFocusTarget(`edit-box-${edit.id}`);
      return;
    }
    setPending({ tool, box, page: pageIndex });
  };

  const finishPending = (fields) => {
    const edit = addEdit(pending.box, { ...fields, page: pending.page });
    setPending(null);
    setTool('select');
    setFocusTarget(`edit-box-${edit.id}`);
  };

  const save = async () => {
    if (!blueprint) return;
    setSaving(true);
    setError('');
    try {
      const saved = await saveOverlay(id, {
        baseVersion: blueprint.currentVersion ?? blueprint.version,
        regions: regions.map((r) => pick(r, REGION_FIELDS)),
        edits: edits.map((e) => pick(e, EDIT_FIELDS)),
        note: note.trim() || undefined,
        confirm: true,
      });
      toast.success(
        isDraft ? 'Template confirmed — it is ready for new documents' : `Saved as version ${saved.version}`
      );
      adopt(saved);
      setNote('');
      if (requestedVersion != null) setSearchParams({});
      if (historyOpen) loadVersions();
      else setVersions(null);
    } catch (err) {
      if (err.status === 409) setConflict(true);
      else setError(err.message);
    } finally {
      setSaving(false);
    }
  };

  const reloadAfterConflict = async () => {
    setConflict(false);
    await load();
    toast.success('Loaded the latest version — your unsaved changes were discarded');
  };

  const download = async (variant) => {
    try {
      await toast.promise(
        () =>
          downloadBlueprintFile(id, {
            variant,
            version: variant === 'template' ? blueprint?.version : undefined,
            fallbackName: `${blueprint?.name || 'template'}${variant === 'original' ? '-original' : ''}.pdf`,
          }),
        { loading: 'Preparing download…', success: 'Download started', error: (err) => err.message }
      );
    } catch {
      // The error toast is already shown.
    }
  };

  const showVersion = (v) => {
    if (dirty && !window.confirm('Discard your unsaved template changes?')) return;
    setSearchParams(v.current ? {} : { version: String(v.version) });
  };

  const pagePath =
    view === 'original'
      ? blueprintPagePath(id, pageIndex, { variant: 'original' })
      : blueprintPagePath(id, pageIndex, { variant: 'template', version: blueprint?.version });

  const fieldCounts = useMemo(() => {
    const counts = pages.map(() => 0);
    for (const r of regions) if (counts[r.page] != null) counts[r.page] += 1;
    return counts.map((n) => (n ? `${n} field${n === 1 ? '' : 's'}` : ''));
  }, [pages, regions]);

  const groupedFields = useMemo(() => {
    const byPage = new Map();
    const sorted = [...regions].sort(
      (a, b) => a.page - b.page || (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.fy - b.fy || a.fx - b.fx
    );
    for (const r of sorted) {
      if (!byPage.has(r.page)) byPage.set(r.page, []);
      byPage.get(r.page).push(r);
    }
    return [...byPage.entries()];
  }, [regions]);

  const activeTool = TOOLS.find((t) => t.value === tool);
  const versionLabel = blueprint
    ? readOnly
      ? ` · viewing version ${blueprint.version} of ${blueprint.currentVersion} (read-only)`
      : isDraft
        ? ' · draft, not saved yet'
        : ` · version ${blueprint.version}`
    : '';

  return (
    <div>
      <PageHeader
        sticky
        crumbs={[{ label: 'Blueprints', to: '/creation/blueprints' }, { label: blueprint?.name || `#${id}` }]}
        title={blueprint?.name || 'Template'}
        subtitle={
          blueprint
            ? `${pages.length} page${pages.length === 1 ? '' : 's'} · ${regions.length} field${
                regions.length === 1 ? '' : 's'
              }${versionLabel}`
            : undefined
        }
        actions={
          blueprint && (
            <div className="flex flex-wrap gap-2">
              {!readOnly && (
                <Button onClick={save} disabled={saving || keyBlocking} title={keyBlocking ? 'Fix the field keys first' : undefined}>
                  {saving ? 'Saving…' : isDraft ? 'Confirm template' : 'Save as new version'}
                </Button>
              )}
              {readOnly && <Button onClick={() => setSearchParams({})}>Back to current version</Button>}
              <Button variant="secondary" onClick={() => setHistoryOpen((open) => !open)}>
                {historyOpen ? 'Hide history' : 'Version history'}
              </Button>
              <Button variant="secondary" onClick={() => download('original')}>
                Download original PDF
              </Button>
              <Button variant="secondary" onClick={() => download('template')}>
                Download template PDF
              </Button>
            </div>
          )
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {historyOpen && <VersionHistory versions={versions} shownVersion={blueprint?.version} onView={showVersion} />}

      {!blueprint ? (
        <Spinner />
      ) : (
        <>
          <div className="mb-4 space-y-3">
            {readOnly ? (
              <Callout variant="info" title={`Version ${blueprint.version} (read-only)`}>
                Documents started on this version keep using it. New documents use version{' '}
                {blueprint.currentVersion}.
              </Callout>
            ) : isDraft ? (
              <Callout variant="info" title="Review the proposed template">
                {blueprint.flow === 'WITH_DATA'
                  ? 'The filled-in data was found and marked for removal (hatched boxes). Check each removal and field, then confirm.'
                  : 'The places to fill in were found on the page. Check each field, then confirm.'}{' '}
                Nothing is saved until you press “Confirm template”.
                {blueprint.detectedBy && (
                  <span className="ml-1 text-muted">
                    (found by {blueprint.detectedBy === 'CODE+AI' ? 'rules and AI' : 'rules'})
                  </span>
                )}
              </Callout>
            ) : null}
            {blueprint.warnings?.length > 0 && (
              <Callout variant="error" title="Warnings">
                <ul className="mt-1 list-inside list-disc">
                  {blueprint.warnings.map((warning) => (
                    <li key={warning}>{warning}</li>
                  ))}
                </ul>
              </Callout>
            )}
          </div>

          <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
            <div className="min-w-0">
              <div className="mb-3 flex flex-wrap items-center gap-3">
                <div role="group" aria-label="Page view" className="inline-flex overflow-hidden rounded border border-line">
                  {VIEWS.map((option) => (
                    <button
                      key={option.value}
                      type="button"
                      aria-pressed={view === option.value}
                      onClick={() => setView(option.value)}
                      className={`px-3 py-1 text-sm ${
                        view === option.value ? 'bg-accent text-white' : 'bg-white text-accent hover:bg-pill/50'
                      }`}
                    >
                      {option.label}
                    </button>
                  ))}
                </div>
                {!readOnly && (
                  <div role="toolbar" aria-label="Editing tools" className="flex flex-wrap gap-1">
                    {TOOLS.map((option) => (
                      <button
                        key={option.value}
                        type="button"
                        aria-pressed={tool === option.value}
                        disabled={!overlayVisible}
                        onClick={() => setTool(option.value)}
                        className={`rounded border px-2.5 py-1 text-xs disabled:opacity-50 ${
                          tool === option.value
                            ? 'border-accent bg-accent text-white'
                            : 'border-line bg-white text-accent hover:bg-pill/50'
                        }`}
                      >
                        {option.label}
                      </button>
                    ))}
                  </div>
                )}
              </div>
              {!overlayVisible && (
                <p className="mb-2 text-xs text-muted">
                  The saved template exactly as documents will use it. Switch to Overlay or Original to edit.
                </p>
              )}
              {activeTool?.draw && canEdit && (
                <p className="mb-2 text-xs text-muted" aria-live="polite">
                  {activeTool.draw}.
                </p>
              )}
              <ThumbnailStrip
                pages={pages}
                current={pageIndex}
                onSelect={(index) => setPageIndex(Math.min(Math.max(0, index), pages.length - 1))}
                pathFor={(index) => blueprintPagePath(id, index, { variant: 'template', version: blueprint.version, dpi: 30 })}
                badges={fieldCounts}
              />
              {page && (
                <PageView
                  path={pagePath}
                  page={page}
                  alt={`Page ${pageIndex + 1} of ${pages.length} (${view === 'original' ? 'original' : 'template'})`}
                >
                  {({ scale }) =>
                    overlayVisible && (
                      <>
                        {edits
                          .filter((e) => e.page === pageIndex)
                          .map((edit) => (
                            <EditBox
                              key={edit.id}
                              id={`edit-box-${edit.id}`}
                              edit={edit}
                              scale={scale}
                              showRemoved={isDraft && view === 'overlay'}
                              selected={selectedEdit?.id === edit.id}
                              editable={canEdit}
                              onSelect={() => select({ type: 'edit', id: edit.id })}
                              onChange={(box) => updateEdit(edit.id, box)}
                              onDelete={() => deleteEdit(edit.id)}
                            />
                          ))}
                        {regions
                          .filter((r) => r.page === pageIndex)
                          .map((region) => {
                            const low = isLow(region);
                            const color = KIND_COLORS[region.kind] || KIND_COLORS.TEXT;
                            return (
                              <RegionBox
                                key={region._uid}
                                id={`region-box-${region._uid}`}
                                box={region}
                                color={color}
                                tabText={region.label?.trim() || region.key}
                                label={`${regionName(region)}, ${KIND_LABELS[region.kind] || region.kind}${
                                  low ? ', low confidence' : ''
                                }`}
                                selected={selectedRegion?._uid === region._uid}
                                highlight={low}
                                editable={canEdit}
                                onSelect={() => select({ type: 'region', uid: region._uid })}
                                onChange={(box) => updateRegion(region._uid, box)}
                                onDelete={() => deleteRegion(region._uid)}
                                data-testid={`region-${region.key}`}
                                data-kind={region.kind}
                                data-low-confidence={low ? 'true' : undefined}
                                style={{ background: `${color}1f` }}
                              >
                                {isDraft && region.sampleValue && (
                                  <span className="pointer-events-none absolute inset-0 overflow-hidden px-0.5 text-[10px] leading-tight text-ink/70">
                                    {region.sampleValue}
                                  </span>
                                )}
                              </RegionBox>
                            );
                          })}
                        {canEdit && activeTool?.draw && (
                          <DrawLayer
                            label={activeTool.draw}
                            onDraw={onDraw}
                            color={tool === 'field' ? KIND_COLORS.TEXT : '#c5221f'}
                          />
                        )}
                      </>
                    )
                  }
                </PageView>
              )}
            </div>

            <aside className="space-y-4" aria-label="Template fields">
              <Card padding="compact">
                <h2 className="mb-2 text-sm font-medium">
                  Checklist{checklist.length > 0 ? ` (${checklist.length})` : ''}
                </h2>
                {checklist.length === 0 ? (
                  <p className="text-xs text-success-ink">Nothing to fix.</p>
                ) : (
                  <ul className="max-h-56 space-y-1 overflow-y-auto text-xs" data-testid="checklist">
                    {checklist.map((item) => (
                      <li key={item.id} className="flex items-start gap-2">
                        <span className="flex-1">{item.text}</span>
                        <button
                          type="button"
                          className="shrink-0 text-accent hover:underline"
                          aria-label={`Show: ${item.text}`}
                          onClick={() => select(item.target, { focus: true })}
                        >
                          Show
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </Card>

              {(selectedRegion || selectedEdit) && (
                <Card padding="compact">
                  <h2 className="mb-2 text-sm font-medium">
                    {selectedRegion ? `Field: ${regionName(selectedRegion)}` : 'Template change'}
                  </h2>
                  <div ref={propertiesRef}>
                    {selectedRegion ? (
                      <RegionPropertiesPanel
                        region={selectedRegion}
                        regions={regions}
                        draft={isDraft}
                        readOnly={readOnly}
                        onChange={(patch) => updateRegion(selectedRegion._uid, patch)}
                        onDelete={() => deleteRegion(selectedRegion._uid)}
                      />
                    ) : (
                      <EditProperties
                        edit={selectedEdit}
                        draft={isDraft}
                        readOnly={readOnly}
                        onChange={(patch) => updateEdit(selectedEdit.id, patch)}
                        onRemove={() => deleteEdit(selectedEdit.id)}
                      />
                    )}
                  </div>
                </Card>
              )}

              <Card padding="compact">
                <h2 className="mb-2 text-sm font-medium">Fields ({regions.length})</h2>
                {regions.length === 0 ? (
                  <p className="text-xs text-muted">No fields yet — use “Add field” and draw on the page.</p>
                ) : (
                  <div className="max-h-96 space-y-2 overflow-y-auto">
                    {groupedFields.map(([pageNo, list]) => (
                      <div key={pageNo}>
                        <h3 className="text-xs font-medium text-muted">Page {pageNo + 1}</h3>
                        <ul className="space-y-0.5" data-testid="field-list">
                          {list.map((region) => (
                            <li key={region._uid}>
                              <button
                                type="button"
                                aria-pressed={selectedRegion?._uid === region._uid}
                                onClick={() => select({ type: 'region', uid: region._uid, page: region.page })}
                                className={`flex w-full items-center gap-2 rounded px-2 py-1 text-left text-xs hover:bg-pill/50 ${
                                  selectedRegion?._uid === region._uid ? 'bg-pill' : ''
                                } ${isLow(region) ? 'ring-1 ring-amber-500' : ''}`}
                              >
                                <span
                                  aria-hidden="true"
                                  className="inline-block size-2.5 shrink-0 rounded-sm"
                                  style={{ background: KIND_COLORS[region.kind] }}
                                />
                                <span className="flex-1 truncate">{regionName(region)}</span>
                                <span className="text-muted">{KIND_LABELS[region.kind]}</span>
                                {isLow(region) && <Tag tone="warning">check</Tag>}
                              </button>
                            </li>
                          ))}
                        </ul>
                      </div>
                    ))}
                  </div>
                )}
              </Card>

              <Card padding="compact">
                <h2 className="mb-2 text-sm font-medium">Template changes ({edits.length})</h2>
                {edits.length === 0 ? (
                  <p className="text-xs text-muted">
                    No changes to the PDF. Use “Remove content”, “Edit text”, “Add text” or “Add image/logo”.
                  </p>
                ) : (
                  <ul className="max-h-72 space-y-1 overflow-y-auto text-xs" data-testid="edit-list">
                    {edits.map((edit) => (
                      <li
                        key={edit.id}
                        className={`flex items-center gap-2 rounded px-1 ${isLow(edit) ? 'bg-amber-50 ring-1 ring-amber-500' : ''}`}
                      >
                        <button
                          type="button"
                          aria-pressed={selectedEdit?.id === edit.id}
                          onClick={() => select({ type: 'edit', id: edit.id, page: edit.page })}
                          className="flex-1 truncate py-1 text-left hover:underline"
                        >
                          p.{edit.page + 1} · {describeEdit(edit)}
                          {isLow(edit) && ' (low confidence)'}
                        </button>
                        {!readOnly && (
                          <button
                            type="button"
                            className="shrink-0 text-accent hover:underline"
                            aria-label={`${edit.kind === 'REMOVE' ? 'Restore' : 'Delete'}: ${describeEdit(edit)} on page ${
                              edit.page + 1
                            }`}
                            onClick={() => deleteEdit(edit.id)}
                          >
                            {edit.kind === 'REMOVE' ? 'Restore' : 'Delete'}
                          </button>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
              </Card>

              {!readOnly && !isDraft && (
                <Card padding="compact">
                  <Field label="Note for this version" hint="Optional — e.g. “moved the signature box”">
                    <input className={inputClass} value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
                  </Field>
                </Card>
              )}
              {dirty && !readOnly && (
                <p className="text-xs text-amber-800" role="status">
                  Unsaved changes — {isDraft ? 'confirm the template' : 'save a new version'} to keep them.
                </p>
              )}
            </aside>
          </div>
        </>
      )}

      <EditContentDialog
        pending={pending}
        blueprintId={id}
        onCancel={() => {
          setPending(null);
          setTool('select');
        }}
        onDone={finishPending}
      />

      <Modal
        open={conflict}
        onClose={() => setConflict(false)}
        title="This template was changed elsewhere"
        footer={
          <>
            <Button variant="secondary" onClick={() => setConflict(false)}>
              Keep editing
            </Button>
            <Button onClick={reloadAfterConflict}>Reload the latest version</Button>
          </>
        }
      >
        <p>
          Someone saved a newer version while you were editing, so your changes were not saved.
          Reload to see the latest version (your unsaved changes are discarded), or keep editing to
          copy what you need first.
        </p>
      </Modal>
    </div>
  );
}
