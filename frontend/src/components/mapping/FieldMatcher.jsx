import { useEffect, useId, useMemo, useState } from 'react';
import { isCompatible, saveDataMapping } from '../../api/dataTemplates';
import { useToast } from '../../context/ToastContext';
import PageView from '../pdf/PageView';
import { boxStyle } from '../pdf/RegionBox';
import { KIND_LABELS } from '../pdf/geometry';
import { Button, ErrorBanner, Tag, inputClass } from '../ui';

const draftOf = (mapping) =>
  Object.fromEntries((mapping?.rows || []).map((row) => [row.regionKey, row.mappedField || '']));

/** The badge of a row: how its current choice came about. */
export function matchBadge(row, value) {
  if (!value) return { tone: 'warning', text: 'not mapped' };
  const s = row.suggestion;
  if (s && s.field === value) {
    if (s.sameName) return { tone: 'success', text: 'same name' };
    return { tone: 'info', text: `suggested ${Number(s.score).toFixed(2)}` };
  }
  return { tone: 'neutral', text: 'chosen by you' };
}

/**
 * The "Map fields" matcher (data-templates contract §3, §6): one row per
 * PDF field (left: label, kind, page) with a data-field dropdown (right:
 * only the kinds it accepts), pre-filled from the saved mapping, a badge
 * per row ("same name", "suggested 0.85", amber "not mapped"), "x of y
 * mapped", "Accept all suggestions", a page preview that highlights the
 * focused row's box, and "Save mapping" → `onSaved(dto)`.
 *
 * `mapping`: DataMappingDto. `pages`: the template's PageInfo list and
 * `pagePath(index)` the image of a page, for the preview.
 */
export default function FieldMatcher({ blueprintId, mapping, pages = [], pagePath, readOnly = false, onSaved }) {
  const toast = useToast();
  const ids = useId();
  const [draft, setDraft] = useState(() => draftOf(mapping));
  const [focusKey, setFocusKey] = useState(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    setDraft(draftOf(mapping));
  }, [mapping]);

  const rows = mapping?.rows || [];
  const fields = mapping?.fields || [];
  const optionsFor = (row) => fields.filter((f) => isCompatible(row.kind, f.kind));
  const mappable = rows.filter((row) => optionsFor(row).length > 0);
  const mappedCount = mappable.filter((row) => draft[row.regionKey]).length;
  const dirty = rows.some((row) => (draft[row.regionKey] || '') !== (row.mappedField || ''));
  const acceptable = mappable.filter(
    (row) =>
      !draft[row.regionKey] &&
      row.suggestion?.field &&
      optionsFor(row).some((f) => f.name === row.suggestion.field)
  );
  const fieldLabel = (name) => {
    const f = fields.find((x) => x.name === name);
    return f ? f.label || f.name : name;
  };

  const focused = rows.find((row) => row.regionKey === focusKey) || rows[0] || null;
  const page = focused ? pages[focused.page] : null;

  const save = async () => {
    setSaving(true);
    setError('');
    try {
      const body = Object.fromEntries(Object.entries(draft).filter(([, v]) => v));
      const dto = await saveDataMapping(blueprintId, body);
      toast.success(`Mapping saved — ${dto?.mappedCount ?? mappedCount} of ${dto?.mappableCount ?? mappable.length} mapped`);
      onSaved?.(dto);
    } catch (err) {
      setError(err.message);
    } finally {
      setSaving(false);
    }
  };

  const summary = useMemo(
    () => `${mappedCount} of ${mappable.length} mapped`,
    [mappedCount, mappable.length]
  );

  return (
    <section aria-labelledby={`${ids}-heading`} className="space-y-3" data-testid="field-matcher">
      <div className="flex flex-wrap items-center gap-2">
        <h2 id={`${ids}-heading`} className="text-base font-medium">
          Match the PDF fields to {mapping?.dataTemplateName || 'the data template'}
        </h2>
        <Tag tone={mappable.length && mappedCount === mappable.length ? 'success' : 'warning'}>
          <span data-testid="mapped-count">{summary}</span>
        </Tag>
        <span className="flex-1" />
        {!readOnly && acceptable.length > 0 && (
          <Button
            variant="secondary"
            size="sm"
            onClick={() =>
              setDraft((d) => ({
                ...d,
                ...Object.fromEntries(acceptable.map((row) => [row.regionKey, row.suggestion.field])),
              }))
            }
          >
            Accept all suggestions ({acceptable.length})
          </Button>
        )}
        {!readOnly && (
          <Button size="sm" onClick={save} disabled={saving || !dirty}>
            {saving ? 'Saving…' : 'Save mapping'}
          </Button>
        )}
      </div>
      <p className="text-xs text-muted">
        Each PDF field gets its value — and its rules — from the data field it is mapped to. Only data fields of a
        fitting kind are offered.
        {dirty && <strong className="ml-1 text-amber-800">Unsaved mapping changes.</strong>}
      </p>
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_16rem]">
        <table className="w-full text-left text-sm">
          <caption className="sr-only">PDF fields and their data fields</caption>
          <thead className="text-xs text-muted">
            <tr>
              <th className="p-1 font-medium">PDF field</th>
              <th className="p-1 font-medium">Data field</th>
              <th className="p-1 font-medium">Match</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const options = optionsFor(row);
              const value = draft[row.regionKey] || '';
              const badge = matchBadge(row, value);
              const name = row.label || row.regionKey;
              const suggestionHint =
                !value && row.suggestion?.field && options.some((f) => f.name === row.suggestion.field)
                  ? `suggestion: ${fieldLabel(row.suggestion.field)}`
                  : '';
              return (
                <tr
                  key={row.regionKey}
                  data-testid={`match-row-${row.regionKey}`}
                  className={`border-t border-line align-top ${focused?.regionKey === row.regionKey ? 'bg-pill/40' : ''}`}
                  onMouseEnter={() => setFocusKey(row.regionKey)}
                >
                  <td className="p-1">
                    <span className="font-medium">{name}</span>
                    <span className="block text-xs text-muted">
                      {KIND_LABELS[row.kind] || row.kind} · page {(row.page ?? 0) + 1}
                    </span>
                  </td>
                  <td className="p-1">
                    {options.length === 0 ? (
                      <span className="text-xs text-muted">No data field of a fitting kind</span>
                    ) : (
                      <select
                        className={`${inputClass} !py-1`}
                        aria-label={`Data field for ${name}`}
                        value={value}
                        disabled={readOnly}
                        onFocus={() => setFocusKey(row.regionKey)}
                        onChange={(e) => setDraft((d) => ({ ...d, [row.regionKey]: e.target.value }))}
                      >
                        <option value="">Not mapped</option>
                        {options.map((f) => (
                          <option key={f.name} value={f.name}>
                            {f.label && f.label !== f.name ? `${f.label} (${f.name})` : f.name}
                          </option>
                        ))}
                      </select>
                    )}
                  </td>
                  <td className="p-1">
                    {options.length > 0 && <Tag tone={badge.tone}>{badge.text}</Tag>}
                    {suggestionHint && <span className="block text-xs text-muted">{suggestionHint}</span>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>

        {focused && page && (
          <figure className="space-y-1" data-testid="match-preview">
            <PageView
              path={pagePath?.(focused.page)}
              page={page}
              testId="match-page"
              alt={`Page ${(focused.page ?? 0) + 1} with ${focused.label || focused.regionKey} highlighted`}
            >
              <span
                aria-hidden="true"
                data-testid="match-highlight"
                data-key={focused.regionKey}
                className="absolute rounded-sm border-2 border-accent bg-accent/20"
                style={boxStyle(focused)}
              />
            </PageView>
            <figcaption className="text-xs text-muted">
              {focused.label || focused.regionKey} · page {(focused.page ?? 0) + 1}
            </figcaption>
          </figure>
        )}
      </div>
    </section>
  );
}
