import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  downloadBlueprintFile,
  getBlueprint,
  updateSectionKeywords,
} from '../api/doccreation';
import PageHeader from '../components/PageHeader';
import { Button, Callout, Card, ErrorBanner, Spinner, inputClass } from '../components/ui';
import { useToast } from '../context/ToastContext';

/** One section row: title, level badge and an editable keyword pill list. */
function SectionCard({ blueprintId, section, onSaved }) {
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
        {keywords.map((keyword) => (
          <span key={keyword} className="inline-flex items-center gap-1 rounded-full bg-pill px-2.5 py-0.5 text-xs">
            {keyword}
            <button
              type="button"
              aria-label={`Remove keyword ${keyword}`}
              className="text-muted hover:text-danger-600"
              onClick={() => removeKeyword(keyword)}
            >
              ×
            </button>
          </span>
        ))}
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
        {dirty && (
          <Button size="sm" onClick={save} disabled={saving}>
            {saving ? 'Saving…' : 'Save keywords'}
          </Button>
        )}
      </div>
    </Card>
  );
}

export default function BlueprintDetailPage() {
  const { id } = useParams();
  const toast = useToast();
  const [blueprint, setBlueprint] = useState(null);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      setBlueprint(await getBlueprint(id));
      setError('');
    } catch (err) {
      setError(err.message);
    }
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

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

  return (
    <div>
      <PageHeader
        sticky
        crumbs={[{ label: 'Blueprints', to: '/creation/blueprints' }, { label: blueprint?.name || `#${id}` }]}
        title={blueprint?.name || 'Blueprint'}
        subtitle={
          blueprint
            ? `${blueprint.sections.length} section${blueprint.sections.length === 1 ? '' : 's'} · flow ${blueprint.flowKey}${blueprint.companyId ? ` · company ${blueprint.companyId}` : ''}`
            : undefined
        }
        actions={<Button variant="secondary" onClick={downloadOriginal}>Download original</Button>}
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {!blueprint ? (
        <Spinner />
      ) : (
        <>
          <div className="mb-4">
            <Callout variant="info" title="How keywords work">
              When images are bulk-uploaded to a document, their file names are matched against
              each section's keywords ("roof" claims roof_east.jpg). Unmatched images wait in the
              Unsorted tray instead of being lost. Tune the keywords here.
            </Callout>
          </div>
          {blueprint.sections.map((section) => (
            <SectionCard
              key={section.sectionId}
              blueprintId={blueprint.blueprintId}
              section={section}
            />
          ))}
        </>
      )}
    </div>
  );
}
