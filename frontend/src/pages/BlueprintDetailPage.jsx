import { useCallback, useEffect, useMemo, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import {
  downloadBlueprintFile,
  getBlueprint,
  getBlueprintParagraphs,
  listBlueprintVersions,
  resectionBlueprint,
  saveBlueprintSections,
  updateSectionKeywords,
} from '../api/doccreation';
import ConfirmDialog from '../components/ConfirmDialog';
import Modal from '../components/Modal';
import PageHeader from '../components/PageHeader';
import { Button, Callout, Card, ErrorBanner, Field, Spinner, Tag, inputClass } from '../components/ui';
import { useToast } from '../context/ToastContext';

const SOURCE_LABELS = {
  AI: { text: 'Sections found by AI', tone: 'success' },
  HEURISTIC: {
    text: 'Sections found by the rule-based parser (AI unavailable or found nothing)',
    tone: 'warning',
  },
  MANUAL: { text: 'Sections edited by hand', tone: 'info' },
};

const LEVELS = [1, 2, 3, 4, 5, 6, 7, 8, 9];

function SourceTag({ source }) {
  const label = SOURCE_LABELS[source];
  if (!label) return null;
  return <Tag tone={label.tone}>{label.text}</Tag>;
}

/** One section row: title, level badge and an editable keyword pill list (read-only on old versions). */
function SectionCard({ blueprintId, section, readOnly, onSaved }) {
  const toast = useToast();
  const [keywords, setKeywords] = useState(section.keywords);
  const [draft, setDraft] = useState('');
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);

  const addDraft = () => {
    const value = draft.trim().toLowerCase();
    if (!value || keywords.includes(value)) {
      setDraft('');
      return;
    }
    setKeywords([...keywords, value]);
    setDraft('');
    setDirty(true);
  };

  const removeKeyword = (keyword) => {
    setKeywords(keywords.filter((k) => k !== keyword));
    setDirty(true);
  };

  const save = async () => {
    setSaving(true);
    try {
      const updated = await toast.promise(
        () => updateSectionKeywords(blueprintId, section.sectionId, keywords),
        { loading: 'Saving keywords…', success: 'Keywords saved', error: (err) => err.message }
      );
      setKeywords(updated.keywords);
      setDirty(false);
      onSaved?.(updated);
    } catch {
      // The error toast is already shown.
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card className="mb-3">
      <div className="flex flex-wrap items-center gap-2" style={{ paddingLeft: `${Math.max(0, section.headingLevel - 1) * 16}px` }}>
        <span className="rounded bg-pill px-2 py-0.5 text-xs text-muted">
          {section.orderIndex + 1}
        </span>
        <h3 className="text-base font-medium">{section.title}</h3>
        {section.headingLevel > 0 && (
          <span className="text-xs text-muted">H{section.headingLevel}</span>
        )}
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        {keywords.length === 0 && readOnly && <span className="text-xs text-muted">No keywords</span>}
        {keywords.map((keyword) => (
          <span key={keyword} className="inline-flex items-center gap-1 rounded-full bg-pill px-2.5 py-0.5 text-xs">
            {keyword}
            {!readOnly && (
              <button
                type="button"
                aria-label={`Remove keyword ${keyword}`}
                className="text-muted hover:text-danger-600"
                onClick={() => removeKeyword(keyword)}
              >
                ×
              </button>
            )}
          </span>
        ))}
        {!readOnly && (
          <input
            className={`${inputClass} !w-44 text-xs`}
            value={draft}
            placeholder="Add keyword, press Enter"
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                addDraft();
              }
            }}
          />
        )}
        {dirty && (
          <Button size="sm" onClick={save} disabled={saving}>
            {saving ? 'Saving…' : 'Save keywords'}
          </Button>
        )}
      </div>
    </Card>
  );
}

/** Pick the paragraph a new section starts at; paragraphs already used as a start are disabled. */
function ParagraphPicker({ open, paragraphs, usedAnchors, onPick, onClose }) {
  const [query, setQuery] = useState('');
  const [headingsOnly, setHeadingsOnly] = useState(false);
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (paragraphs || []).filter(
      (p) => (!headingsOnly || p.headingCandidate) && (!q || p.text.toLowerCase().includes(q))
    );
  }, [paragraphs, query, headingsOnly]);

  return (
    <Modal open={open} onClose={onClose} title="Add a section at a paragraph" size="lg">
      <p className="mb-3 text-sm text-muted">
        A section starts at the paragraph you pick and runs until the next section. Likely
        headings are marked.
      </p>
      <div className="mb-3 flex flex-wrap items-center gap-3">
        <input
          className={`${inputClass} !w-72`}
          value={query}
          placeholder="Search paragraphs"
          aria-label="Search paragraphs"
          onChange={(e) => setQuery(e.target.value)}
        />
        <label className="flex items-center gap-1.5 text-sm">
          <input type="checkbox" checked={headingsOnly} onChange={(e) => setHeadingsOnly(e.target.checked)} />
          Likely headings only
        </label>
      </div>
      {!paragraphs ? (
        <Spinner />
      ) : (
        <ul className="max-h-96 space-y-1 overflow-y-auto">
          {shown.length === 0 && <li className="text-sm text-muted">No paragraphs match.</li>}
          {shown.map((p) => {
            const used = usedAnchors.has(p.index);
            return (
              <li key={p.index}>
                <button
                  type="button"
                  disabled={used}
                  onClick={() => onPick(p)}
                  className="flex w-full items-start gap-2 rounded px-2 py-1.5 text-left text-sm hover:bg-pill/50 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  <span className="shrink-0 tabular-nums text-xs text-muted">¶{p.index}</span>
                  <span className="flex-1">{p.text}</span>
                  {p.headingCandidate && <Tag tone="info">heading?</Tag>}
                  {used && <Tag>already a section</Tag>}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </Modal>
  );
}

let draftKey = 0;
const toDraft = (section) => ({
  key: `d${(draftKey += 1)}`,
  title: section.title,
  headingLevel: Math.max(1, section.headingLevel || 1),
  anchorParagraphIndex: section.anchorParagraphIndex,
  anchorText: section.anchorText,
  keywords: section.keywords || [],
});

/** The structural editor: rename, re-level, add at a paragraph, delete, merge with next. */
function SectionEditor({ blueprint, onSaved, onCancel }) {
  const toast = useToast();
  const [drafts, setDrafts] = useState(() => blueprint.sections.map(toDraft));
  const [note, setNote] = useState('');
  const [paragraphs, setParagraphs] = useState(null);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const openPicker = async () => {
    setPickerOpen(true);
    if (!paragraphs) {
      try {
        setParagraphs(await getBlueprintParagraphs(blueprint.blueprintId));
      } catch (err) {
        setPickerOpen(false);
        setError(err.message);
      }
    }
  };

  const update = (key, changes) =>
    setDrafts((list) => list.map((d) => (d.key === key ? { ...d, ...changes } : d)));
  const remove = (key) => setDrafts((list) => list.filter((d) => d.key !== key));
  const mergeWithNext = (index) =>
    setDrafts((list) => {
      const next = list[index + 1];
      if (!next) return list;
      const merged = { ...list[index], keywords: [...new Set([...list[index].keywords, ...next.keywords])] };
      return [...list.slice(0, index), merged, ...list.slice(index + 2)];
    });
  const addAt = (paragraph) => {
    const title = paragraph.text.length > 120 ? `${paragraph.text.slice(0, 120)}…` : paragraph.text;
    setDrafts((list) =>
      [
        ...list,
        toDraft({ title, headingLevel: 1, anchorParagraphIndex: paragraph.index, anchorText: paragraph.text }),
      ].sort((a, b) => a.anchorParagraphIndex - b.anchorParagraphIndex)
    );
    setPickerOpen(false);
  };

  const usedAnchors = useMemo(() => new Set(drafts.map((d) => d.anchorParagraphIndex)), [drafts]);
  const blankTitle = drafts.some((d) => !d.title.trim());

  const save = async () => {
    setSaving(true);
    setError('');
    try {
      const saved = await toast.promise(
        () =>
          saveBlueprintSections(
            blueprint.blueprintId,
            blueprint.currentVersion,
            drafts.map((d) => ({
              title: d.title.trim(),
              headingLevel: d.headingLevel,
              anchorParagraphIndex: d.anchorParagraphIndex,
              keywords: d.keywords,
            })),
            note.trim() || undefined
          ),
        { loading: 'Saving a new version…', success: (r) => `Saved as version ${r.version}`, error: (err) => err.message }
      );
      onSaved(saved);
    } catch (err) {
      setError(err.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div>
      <ErrorBanner message={error} onDismiss={() => setError('')} />
      <div className="mb-4">
        <Callout variant="info" title="Editing the section structure">
          Saving creates version {blueprint.currentVersion + 1}. Documents already started keep
          the version they were built on; new documents use the new version. Deleting a section
          moves its content into the section above it.
        </Callout>
      </div>
      {drafts.length === 0 && (
        <p className="mb-3 text-sm text-danger-ink">A template needs at least one section.</p>
      )}
      {drafts.map((d, index) => (
        <Card key={d.key} className="mb-3" padding="compact">
          <div className="flex flex-wrap items-center gap-2">
            <span className="rounded bg-pill px-2 py-0.5 text-xs text-muted">{index + 1}</span>
            <input
              className={`${inputClass} !w-auto flex-1 min-w-56`}
              value={d.title}
              aria-label={`Title of section ${index + 1}`}
              maxLength={500}
              onChange={(e) => update(d.key, { title: e.target.value })}
            />
            <select
              className={`${inputClass} !w-20`}
              value={d.headingLevel}
              aria-label={`Level of section ${index + 1}`}
              onChange={(e) => update(d.key, { headingLevel: Number(e.target.value) })}
            >
              {LEVELS.map((level) => (
                <option key={level} value={level}>
                  H{level}
                </option>
              ))}
            </select>
            <Button
              size="sm"
              variant="secondary"
              disabled={index === drafts.length - 1}
              title="Fold the next section into this one"
              onClick={() => mergeWithNext(index)}
            >
              Merge with next
            </Button>
            <Button size="sm" variant="danger" onClick={() => remove(d.key)}>
              Delete
            </Button>
          </div>
          <p className="mt-2 truncate text-xs text-muted" title={d.anchorText || ''}>
            Starts at ¶{d.anchorParagraphIndex}: {d.anchorText || '—'}
          </p>
        </Card>
      ))}
      <div className="mb-6">
        <Button variant="secondary" onClick={openPicker}>
          + Add section at a paragraph
        </Button>
      </div>
      <Card className="sticky bottom-0">
        <div className="flex flex-wrap items-end gap-3">
          <div className="min-w-64 flex-1">
            <Field label="Note for this version" hint="Optional — e.g. “split Scope into two parts”">
              <input className={inputClass} value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
            </Field>
          </div>
          <Button variant="secondary" onClick={onCancel} disabled={saving}>
            Cancel
          </Button>
          <Button onClick={save} disabled={saving || drafts.length === 0 || blankTitle}>
            {saving ? 'Saving…' : `Save as version ${blueprint.currentVersion + 1}`}
          </Button>
        </div>
      </Card>
      <ParagraphPicker
        open={pickerOpen}
        paragraphs={paragraphs}
        usedAnchors={usedAnchors}
        onPick={addAt}
        onClose={() => setPickerOpen(false)}
      />
    </div>
  );
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
            <SourceTag source={v.sectionSource} />
            <span className="text-muted">
              {v.sectionCount} section{v.sectionCount === 1 ? '' : 's'} · {v.documentCount} document
              {v.documentCount === 1 ? '' : 's'}
              {v.createdDate ? ` · ${new Date(v.createdDate).toLocaleString()}` : ''}
            </span>
            {v.note && <span className="italic text-muted">“{v.note}”</span>}
            <span className="flex-1" />
            {v.version !== shownVersion && (
              <Button size="sm" variant="secondary" onClick={() => onView(v)}>
                View
              </Button>
            )}
          </li>
        ))}
      </ul>
    </Card>
  );
}

export default function BlueprintDetailPage() {
  const { id } = useParams();
  const toast = useToast();
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedVersion = searchParams.get('version') ? Number(searchParams.get('version')) : undefined;
  const [blueprint, setBlueprint] = useState(null);
  const [versions, setVersions] = useState(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const [confirmResection, setConfirmResection] = useState(false);
  const [resectioning, setResectioning] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      setBlueprint(await getBlueprint(id, requestedVersion));
      setError('');
    } catch (err) {
      setError(err.message);
    }
  }, [id, requestedVersion]);

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

  const readOnly = blueprint ? blueprint.version !== blueprint.currentVersion : true;

  const showVersion = (v) => {
    setEditing(false);
    setSearchParams(v.current ? {} : { version: String(v.version) });
  };

  const afterNewVersion = (saved) => {
    setEditing(false);
    setBlueprint(saved);
    setSearchParams({});
    if (historyOpen) loadVersions();
    else setVersions(null);
  };

  const resection = async () => {
    setResectioning(true);
    try {
      const saved = await toast.promise(() => resectionBlueprint(id, blueprint.currentVersion), {
        loading: 'Re-running the AI section breakdown…',
        success: (r) => `Saved as version ${r.version} (${r.sections.length} sections)`,
        error: (err) => err.message,
      });
      afterNewVersion(saved);
    } catch {
      // The error toast is already shown.
    } finally {
      setResectioning(false);
      setConfirmResection(false);
    }
  };

  const downloadOriginal = async () => {
    try {
      await toast.promise(
        () => downloadBlueprintFile(id, `${blueprint?.name || 'blueprint'}.docx`),
        { loading: 'Preparing download…', success: 'Download started', error: (err) => err.message }
      );
    } catch {
      // The error toast is already shown.
    }
  };

  const versionLabel = blueprint
    ? readOnly
      ? ` · viewing version ${blueprint.version} of ${blueprint.currentVersion} (read-only)`
      : ` · version ${blueprint.version}`
    : '';

  return (
    <div>
      <PageHeader
        sticky
        crumbs={[{ label: 'Blueprints', to: '/creation/blueprints' }, { label: blueprint?.name || `#${id}` }]}
        title={blueprint?.name || 'Blueprint'}
        subtitle={
          blueprint
            ? `${blueprint.sections.length} section${blueprint.sections.length === 1 ? '' : 's'}${versionLabel} · flow ${blueprint.flowKey}${blueprint.companyId ? ` · company ${blueprint.companyId}` : ''}`
            : undefined
        }
        actions={
          <div className="flex flex-wrap gap-2">
            {blueprint && !readOnly && !editing && (
              <>
                <Button onClick={() => setEditing(true)}>Edit sections</Button>
                <Button variant="secondary" onClick={() => setConfirmResection(true)}>
                  Re-run AI sectioning
                </Button>
              </>
            )}
            {readOnly && blueprint && (
              <Button onClick={() => setSearchParams({})}>Back to current version</Button>
            )}
            <Button variant="secondary" onClick={() => setHistoryOpen((open) => !open)}>
              {historyOpen ? 'Hide history' : 'Version history'}
            </Button>
            <Button variant="secondary" onClick={downloadOriginal}>Download original</Button>
          </div>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {historyOpen && (
        <VersionHistory versions={versions} shownVersion={blueprint?.version} onView={showVersion} />
      )}

      {!blueprint ? (
        <Spinner />
      ) : editing ? (
        <SectionEditor blueprint={blueprint} onSaved={afterNewVersion} onCancel={() => setEditing(false)} />
      ) : (
        <>
          {blueprint.sectionSource && (
            <div className="mb-3">
              <SourceTag source={blueprint.sectionSource} />
            </div>
          )}
          <div className="mb-4">
            {readOnly ? (
              <Callout variant="info" title={`Version ${blueprint.version} (read-only)`}>
                Documents started on this version keep using it. New documents use version{' '}
                {blueprint.currentVersion}.
              </Callout>
            ) : (
              <Callout variant="info" title="How keywords work">
                When images are bulk-uploaded to a document, their file names are matched against
                each section's keywords ("roof" claims roof_east.jpg). Unmatched images wait in the
                Unsorted tray instead of being lost. Tune the keywords here.
              </Callout>
            )}
          </div>
          {blueprint.sections.map((section) => (
            <SectionCard
              key={section.sectionId}
              blueprintId={blueprint.blueprintId}
              section={section}
              readOnly={readOnly}
            />
          ))}
        </>
      )}

      <ConfirmDialog
        open={confirmResection}
        title="Re-run AI sectioning"
        confirmLabel="Re-run and save"
        busy={resectioning}
        onConfirm={resection}
        onCancel={() => setConfirmResection(false)}
      >
        <p>
          The AI breaks the stored document into sections again and the result is saved as
          version {blueprint ? blueprint.currentVersion + 1 : ''}. Keyword changes on the current
          version are not carried over. Documents already started keep their version.
        </p>
      </ConfirmDialog>
    </div>
  );
}
