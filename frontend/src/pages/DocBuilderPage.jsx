import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  blueprintPagePath,
  deleteImage,
  downloadInstance,
  generateInstance,
  getInstance,
  imageContentPath,
  placeImage,
  saveOverlay,
  submitInstance,
  upgradeInstance,
  uploadImages,
  validateInstance,
} from '../api/doccreation';
import PageHeader from '../components/PageHeader';
import Stepper from '../components/Stepper';
import { DownloadStep, SaveStep } from '../components/doc/BuilderSteps';
import AuthedImage from '../components/AuthedImage';
import Modal from '../components/Modal';
import { Badge, Button, Callout, Card, ErrorBanner, Spinner, Tag, inputClass } from '../components/ui';
import PhotoBox, { startPhotoDrag } from '../components/doc/PhotoBox';
import PreviewDialog from '../components/doc/PreviewDialog';
import RegionInput from '../components/doc/RegionInput';
import useAutosave from '../components/doc/useAutosave';
import ValidationPanel from '../components/doc/ValidationPanel';
import { FieldCheckChips, checksFor, checksId, compactText, rulesTitle, totalRules } from '../components/doc/fieldChecks';
import { PHOTO_KINDS, fieldLabel } from '../components/doc/fields';
import { NOT_VALIDATED_TEXT, downloadGate, isBlank, liveHints, serverIssues } from '../components/doc/validation';
import { useToast } from '../context/ToastContext';
import useUnsavedGuard from '../hooks/useUnsavedGuard';
import PageView from '../components/pdf/PageView';
import ThumbnailStrip from '../components/pdf/ThumbnailStrip';
import RegionBox, { boxStyle } from '../components/pdf/RegionBox';
import DrawLayer from '../components/pdf/DrawLayer';
import RegionPropertiesPanel, { fieldProblem } from '../components/pdf/RegionPropertiesPanel';
import EditorToolbar, { DeleteFieldsDialog, MarqueeLayer, SnapGuides } from '../components/pdf/EditorToolbar';
import FieldList, { reorderPage } from '../components/pdf/FieldList';
import useOverlayEditor from '../components/pdf/useOverlayEditor';
import useFieldRules from '../components/rules/useFieldRules';
import useRequiredToggle from '../components/rules/useRequiredToggle';
import { KIND_COLORS, KIND_LABELS, displaySize, estimateOverflow } from '../components/pdf/geometry';
import FillFromDataDialog, { FillFromDataPanel } from '../components/records/FillFromDataDialog';
import { newRegion, toRegionDto, withUid } from '../components/records/layoutDraft';

export { AUTOSAVE_MS } from '../components/doc/useAutosave';
const PHOTO_EXT = /\.(png|jpe?g|gif|bmp)$/i;
const OVERFLOW_TEXT = 'Will continue on an added page';
const DRAW_HINT = 'Draw a new field: drag on the page, or press Enter to add one in the centre';

/**
 * ws34: the builder's guided steps (data-templates contract §6), kept in
 * `?step=`. Without one the builder opens on Fix fields; a new document
 * opens on Choose data.
 */
export const BUILDER_STEPS = [
  { id: 'data', label: 'Choose data' },
  { id: 'fix', label: 'Fix fields' },
  { id: 'save', label: 'Save & validate' },
  { id: 'download', label: 'Download' },
];
const stepOf = (value) => (BUILDER_STEPS.some((s) => s.id === value) ? value : 'fix');

/** CSS px for a region's text: its own size, or a fit to the box ("auto"), scaled to the page. */
function fontPx(region, page, scale) {
  const heightPt = region.fh * displaySize(page).height;
  const auto = region.kind === 'MULTILINE' ? 10 : Math.min(12, Math.max(6, heightPt * 0.65));
  return Math.max(7, (region.fontSize > 0 ? region.fontSize : auto) * scale);
}

export default function DocBuilderPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const toast = useToast();
  const [searchParams, setSearchParams] = useSearchParams();
  const [step, setStep] = useState(() => stepOf(searchParams.get('step')));
  const fileInputRef = useRef(null);
  const [doc, setDoc] = useState(null);
  const [values, setValues] = useState({});
  const [error, setError] = useState('');
  const [pageIndex, setPageIndex] = useState(0);
  const [activeKey, setActiveKey] = useState(null);
  const [photos, setPhotos] = useState([]);
  const [uploading, setUploading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [fillOpen, setFillOpen] = useState(false);
  const [upgrading, setUpgrading] = useState(false);
  // "Edit layout" mode: a draft copy of the regions in the shared overlay editor.
  const [layoutMode, setLayoutMode] = useState(false);
  const [layoutTool, setLayoutTool] = useState('select');
  const [layoutDirty, setLayoutDirty] = useState(false);
  const [layoutSaving, setLayoutSaving] = useState(false);
  const [layoutConflict, setLayoutConflict] = useState(false);
  const [focusProperties, setFocusProperties] = useState(false);
  const propertiesRef = useRef(null);
  const layout = useOverlayEditor({
    page: pageIndex,
    enabled: layoutMode,
    onChange: () => setLayoutDirty(true),
    withUid,
  });
  const layoutRegions = layout.items;
  // The template's rules: live hints while filling, rule counts and Required while editing the layout.
  const fieldRules = useFieldRules(doc?.blueprintId, { enabled: Boolean(doc?.blueprintId) });
  const required = useRequiredToggle({
    editor: layout,
    fieldRules,
    blueprintId: doc?.blueprintId,
    enabled: layoutMode,
    // The rules live on the template page; leaving asks first when the layout is unsaved.
    onEditRules: (region) => {
      if (layoutDirty && !window.confirm('You have unsaved layout changes. Leave without saving them?')) return;
      navigate(`/creation/blueprints/${doc.blueprintId}?tab=rules&field=${encodeURIComponent(region.key)}`);
    },
    onError: (err) => setError(err.message),
  });

  // Validation (contract §4): Save → flush → validate. `edited` keys changed since the last validate:
  // the status shows Draft at once and their server messages give way to live hints.
  const [edited, setEdited] = useState(() => new Set());
  const [validating, setValidating] = useState(false);
  // The server refused the validate (403): this user may not edit the document.
  const [forbidden, setForbidden] = useState(false);
  const [goTo, setGoTo] = useState(null);

  // Autosave (components/doc/useAutosave): changed keys wait until typing pauses.
  const pendingRef = useRef(() => ({}));

  const adopt = useCallback((dto) => {
    if (!dto) return;
    setDoc(dto);
    setValues({ ...(dto.values || {}), ...pendingRef.current() });
  }, []);

  const { queue, flush, saveState, pendingValues } = useAutosave(id, {
    onSaved: (dto) => {
      adopt(dto);
      // The server's copy has the status after the change (a real change makes it a draft).
      if (Object.keys(pendingRef.current()).length === 0 && dto?.status && dto.status !== 'DRAFT') {
        setEdited(new Set());
      }
    },
    onError: (err) => setError(err.message),
  });
  pendingRef.current = pendingValues;

  const load = useCallback(async () => {
    try {
      adopt(await getInstance(id));
      setError('');
    } catch (err) {
      setError(err.message);
    }
  }, [id, adopt]);

  useEffect(() => {
    load();
  }, [load]);

  const setValue = (key, value) => {
    setValues((current) => ({ ...current, [key]: value }));
    setEdited((current) => (current.has(key) ? current : new Set(current).add(key)));
    queue(key, value);
  };

  const pages = doc?.pages || [];
  const regions = useMemo(
    () =>
      [...(doc?.regions || [])]
        // Each region's field properties: on the region, or in the document's `props` map.
        .map((r) => ({ ...r, props: r.props ?? doc?.props?.[r.key] ?? null }))
        .sort((a, b) => a.page - b.page || (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.fy - b.fy || a.fx - b.fx),
    [doc]
  );
  const images = doc?.images || [];
  const tray = images.filter((image) => !image.regionKey);
  const imageByRegion = new Map(images.filter((i) => i.regionKey).map((i) => [i.regionKey, i]));
  const photoRegions = regions.filter((r) => PHOTO_KINDS.has(r.kind));
  const locked = doc?.status === 'SUBMITTED' || doc?.status === 'RELEASED';
  const serverOverflow = new Set(doc?.overflow || []);
  const overflows = (region) =>
    serverOverflow.has(region.key) || estimateOverflow(region, values[region.key], pages[region.page]);

  const groups = useMemo(() => {
    const map = new Map();
    for (const region of regions) {
      const name = region.group?.trim() || 'Other fields';
      if (!map.has(name)) map.set(name, []);
      map.get(name).push(region);
    }
    return [...map.entries()];
  }, [regions]);

  const focusField = (region) => {
    setActiveKey(region.key);
    if (region.page !== pageIndex) setPageIndex(region.page);
  };

  // ------------------------------------------------------- validation

  const serverStatus = doc?.status;
  // Any edit makes the document a draft again (the server resets it too).
  const status = edited.size && (serverStatus === 'VALIDATED' || serverStatus === 'GENERATED') ? 'DRAFT' : serverStatus;
  const validation = doc?.validation || null;
  // Step 4 (and the Generate / Download / Submit shortcuts) open only for a document validated with the
  // current rules; an edit re-locks it at once (the status above is already DRAFT).
  const gate = downloadGate({ status, validation });
  const notValidated = gate.locked;
  const issuesByKey = useMemo(() => serverIssues(validation), [validation]);
  const rulesByKey = useMemo(() => {
    const map = new Map();
    for (const rule of fieldRules.data?.rules || []) {
      if (!map.has(rule.regionKey)) map.set(rule.regionKey, []);
      map.get(rule.regionKey).push(rule);
    }
    return map;
  }, [fieldRules.data]);

  /** What to show under a field: the server's errors and warnings, else (after an edit) live hints. */
  const fieldMessages = (region) => {
    const server = !edited.has(region.key) ? issuesByKey.get(region.key) : null;
    if (server && (server.errors.length || server.warnings.length)) {
      return { errors: server.errors, warnings: server.warnings, hints: [] };
    }
    // Hints only for what changed since the last Save: otherwise the server's result stands.
    const hints = edited.has(region.key)
      ? liveHints(region, values[region.key], {
          hasPhoto: imageByRegion.has(region.key),
          rules: rulesByKey.get(region.key) || [],
        })
      : [];
    return { errors: [], warnings: [], hints };
  };

  const issues = useMemo(() => {
    if (!validation?.checked) return [];
    const labelOf = (key) => fieldLabel(regions.find((r) => r.key === key) || { key });
    const list = [];
    (validation.errors || []).forEach((e, i) =>
      list.push({ id: `e${i}`, level: 'error', regionKey: e.regionKey, label: e.label || labelOf(e.regionKey), message: e.message })
    );
    (validation.warnings || []).forEach((w, i) =>
      list.push({ id: `w${i}`, level: 'warning', regionKey: w.regionKey, label: w.label || labelOf(w.regionKey), message: w.message })
    );
    return list;
  }, [validation, regions]);

  // What each field is checked against (contract §4.4): its rules, built-in checks and data-source mapping.
  const fieldChecks = doc?.fieldChecks;
  const rulesTotal = totalRules(fieldChecks);
  const requiredRegions = regions.filter(
    (r) =>
      r.required ||
      (rulesByKey.get(r.key) || []).some((x) => x.ruleType === 'REQUIRED') ||
      checksFor(fieldChecks, r.key).rules.some((x) => x.ruleType === 'REQUIRED')
  );
  const requiredFilled = requiredRegions.filter((r) => !isBlank(r, values[r.key], imageByRegion.has(r.key))).length;

  /** Shows a builder step and keeps it in the URL (`?step=`). */
  const selectStep = (next) => {
    setStep(next);
    setSearchParams(
      (params) => {
        const out = new URLSearchParams(params);
        out.set('step', next);
        return out;
      },
      { replace: true }
    );
  };

  /** "Go to field": shows its page (on the Fix fields step), then focuses its input in the Fields panel. */
  const goToField = (key) => {
    const region = regions.find((r) => r.key === key);
    if (!region) return;
    if (step !== 'fix') selectStep('fix');
    focusField(region);
    setGoTo(key);
  };

  useEffect(() => {
    if (!goTo) return;
    const element = document.getElementById(`field-input-${goTo}`) || document.getElementById(`field-panel-${goTo}`);
    if (element) {
      element.scrollIntoView?.({ block: 'center' });
      element.focus();
    }
    setGoTo(null);
  }, [goTo]);

  /** Save: send what waits, stop if that failed, then validate. */
  const saveAndValidate = async () => {
    setValidating(true);
    setError('');
    try {
      const flushed = await flush();
      if (!flushed) {
        setError('Your latest changes could not be saved, so the document was not validated. Check your connection and press Save again.');
        return;
      }
      const dto = await validateInstance(id);
      setEdited(new Set());
      adopt(dto);
      const result = dto?.validation;
      const errors = result?.errors?.length || 0;
      const warnings = result?.warnings?.length || 0;
      if (errors) {
        toast.error(`Saved as a draft — ${errors} issue${errors === 1 ? '' : 's'} to fix before generating`);
      } else {
        toast.success(`Saved and validated${warnings ? ` with ${warnings} warning${warnings === 1 ? '' : 's'}` : ''}`);
      }
    } catch (err) {
      if (err?.status === 403) setForbidden(true);
      else setError(err.message);
    } finally {
      setValidating(false);
    }
  };

  /** The text of a failed generate / download: a 409 NOT_VALIDATED asks to save first. */
  const failure = (err) => {
    if (err?.code === 'NOT_VALIDATED') {
      load();
      // ws34: the server says when the rules (or the mapping) changed since the validation.
      return /rules changed/i.test(err.message || '') ? err.message : NOT_VALIDATED_TEXT;
    }
    return err.message;
  };

  // ------------------------------------------------------------ photos

  const upload = async (autoFill) => {
    const chosen = photos.filter((file) => PHOTO_EXT.test(file.name));
    const skipped = photos.filter((file) => !PHOTO_EXT.test(file.name));
    if (skipped.length) {
      toast.error(`Not a photo (png, jpg, gif or bmp): ${skipped.map((f) => f.name).join(', ')}`);
    }
    if (!chosen.length) return;
    setUploading(true);
    try {
      await toast.promise(() => uploadImages(id, chosen, autoFill), {
        loading: `Uploading ${chosen.length} photo${chosen.length === 1 ? '' : 's'}…`,
        success: (result) => {
          const parts = [];
          const assigned = result?.assigned?.length || 0;
          const unassigned = result?.unassigned?.length || 0;
          const rejected = result?.rejected || [];
          if (assigned) parts.push(`${assigned} placed in photo boxes`);
          if (unassigned) parts.push(`${unassigned} in the tray`);
          if (rejected.length) {
            parts.push(`${rejected.length} rejected (${rejected.map((r) => `${r.name}: ${r.reason}`).join('; ')})`);
          }
          return parts.join(', ') || 'Nothing uploaded';
        },
        error: (err) => err.message,
      });
      setPhotos([]);
      if (fileInputRef.current) fileInputRef.current.value = '';
      load();
    } catch {
      // The error toast is already shown.
    } finally {
      setUploading(false);
    }
  };

  const place = async (imageId, regionKey) => {
    const target = regionKey ? photoRegions.find((r) => r.key === regionKey) : null;
    try {
      await toast.promise(() => placeImage(id, imageId, regionKey || null), {
        loading: regionKey ? 'Placing the photo…' : 'Moving the photo to the tray…',
        success: regionKey
          ? `Photo placed in “${target ? fieldLabel(target) : regionKey}”${
              imageByRegion.has(regionKey) ? ' (swapped)' : ''
            }`
          : 'Photo moved to the tray',
        error: (err) => err.message,
      });
      load();
    } catch {
      // The error toast is already shown.
    }
  };

  const removePhoto = async (image) => {
    try {
      await toast.promise(() => deleteImage(id, image.id), {
        loading: 'Removing the photo…',
        success: `Removed ${image.originalName}`,
        error: (err) => err.message,
      });
      load();
    } catch {
      // The error toast is already shown.
    }
  };

  // ------------------------------------------------------- generation

  const generate = async () => {
    setGenerating(true);
    try {
      await flush();
      await toast.promise(() => generateInstance(id), {
        loading: 'Generating the PDF…',
        success: (r) => {
          const added = r?.addedPages || 0;
          const continued = (r?.continuedKeys || [])
            .map((key) => fieldLabel(regions.find((reg) => reg.key === key) || { key }))
            .join(', ');
          return `PDF generated${
            added ? ` — ${added} page${added === 1 ? '' : 's'} added for text that did not fit${continued ? ` (${continued})` : ''}` : ''
          }${r?.exact === false ? ' · the exactness check did not pass' : ' · layout verified exact'}`;
        },
        error: failure,
      });
      load();
    } catch {
      // The error toast is already shown.
    } finally {
      setGenerating(false);
    }
  };

  // ws11: hand the generated document to a doc:release approver — the
  // server enforces that someone ELSE releases it (B8).
  const submitForRelease = async () => {
    setSubmitting(true);
    try {
      await toast.promise(() => submitInstance(id), {
        loading: 'Submitting for release…',
        success: 'Submitted — an approver (not you) can now release it',
        error: failure,
      });
      load();
    } catch {
      // The error toast is already shown.
    } finally {
      setSubmitting(false);
    }
  };

  const download = async () => {
    try {
      await flush();
      await toast.promise(() => downloadInstance(id, `${doc?.title || 'document'}.pdf`), {
        loading: 'Preparing the PDF…',
        success: 'Download started',
        error: failure,
      });
    } catch {
      // The error toast is already shown.
    }
  };

  const openPreview = async () => {
    await flush();
    setPreviewOpen(true);
  };

  // ------------------------------------------- template version / layout

  const droppedText = (dto) => {
    const dropped = dto?.droppedKeys || [];
    return dropped.length
      ? ` · values removed for fields no longer on the template: ${dropped.join(', ')}`
      : '';
  };

  const moveToCurrentVersion = async () => {
    setUpgrading(true);
    try {
      await flush();
      const dto = await toast.promise(() => upgradeInstance(id), {
        loading: 'Moving to the newest template version…',
        success: (result) => `Moved to template version ${result?.blueprintVersion ?? ''}${droppedText(result)}`.trim(),
        error: (err) => err.message,
      });
      adopt(dto);
    } catch {
      // The error toast is already shown.
    } finally {
      setUpgrading(false);
    }
  };

  const startLayout = async () => {
    await flush();
    layout.reset(regions.map(withUid));
    required.reset();
    setLayoutMode(true);
    setLayoutTool('select');
    setLayoutDirty(false);
  };

  const endLayout = () => {
    layout.reset([]);
    setLayoutMode(false);
    setLayoutTool('select');
    setLayoutDirty(false);
  };

  const updateLayoutRegion = (uid, patch) => layout.update(uid, patch, { chain: true });

  const drawLayoutRegion = (box) => {
    const region = newRegion(box, pageIndex, layoutRegions);
    layout.add(region);
    setLayoutTool('select');
    setFocusProperties(true);
  };

  /**
   * Saves the draft layout to the blueprint as a new version (the current
   * edits are resent unchanged), then moves this document onto it.
   */
  const saveLayout = async () => {
    if (!doc || !layoutMode) return;
    setLayoutSaving(true);
    setError('');
    const requiredChanges = required.takeChanges(layoutRegions);
    let saved;
    try {
      await flush();
      saved = await saveOverlay(doc.blueprintId, {
        baseVersion: doc.blueprintCurrentVersion,
        regions: layoutRegions.map(toRegionDto),
        edits: doc.blueprintEdits,
        note: `Layout adjusted from document "${doc.title}"`,
        confirm: true,
      });
    } catch (err) {
      if (err.status === 409) setLayoutConflict(true);
      else setError(err.message);
      setLayoutSaving(false);
      return;
    }
    if (requiredChanges.size) {
      try {
        await required.sync(requiredChanges);
      } catch (err) {
        setError(`The layout was saved, but the Required settings could not be saved as rules: ${err.message}`);
      }
    }
    try {
      const dto = await upgradeInstance(id);
      adopt(dto);
      endLayout();
      toast.success(
        `Layout saved to the template as version ${saved?.version ?? dto?.blueprintVersion}${droppedText(dto)}`
      );
    } catch (err) {
      endLayout();
      setError(`The layout was saved to the template, but this document could not be moved to it: ${err.message}`);
      load();
    } finally {
      setLayoutSaving(false);
    }
  };

  const reloadAfterLayoutConflict = async () => {
    setLayoutConflict(false);
    endLayout();
    await load();
    toast.success('Loaded the latest template version — your layout changes were discarded');
  };

  useUnsavedGuard(layoutDirty, 'You have unsaved layout changes. Leave without saving them?');

  // A newly drawn field: move focus to its properties once they are on screen.
  useEffect(() => {
    if (!focusProperties) return;
    const element = propertiesRef.current?.querySelector('input, textarea, select, button');
    if (element) {
      element.focus();
      setFocusProperties(false);
    }
  });

  /** The ids that describe a field's inputs (overflow note, help text, messages) and its messages. */
  const describe = (region) => {
    const messages = fieldMessages(region);
    const help = region.props?.helpText ? `field-help-${region.key}` : null;
    const any = messages.errors.length || messages.warnings.length || messages.hints.length;
    const msg = any ? `field-msg-${region.key}` : null;
    const overflow = !PHOTO_KINDS.has(region.kind) && overflows(region) ? `overflow-note-${region.key}` : null;
    return {
      messages,
      helpId: help,
      msgId: msg,
      describedBy: [overflow, help, msg].filter(Boolean).join(' ') || undefined,
      invalid: messages.errors.length > 0,
    };
  };

  /** Help text and messages under a field in the Fields panel. */
  const fieldNotes = (region, info) => (
    <>
      {info.helpId && (
        <p id={info.helpId} className="mt-0.5 text-xs text-muted">
          {region.props.helpText}
        </p>
      )}
      {info.msgId && (
        <ul id={info.msgId} className="mt-0.5 space-y-0.5 text-xs" data-testid={`field-messages-${region.key}`}>
          {info.messages.errors.map((m, i) => (
            <li key={`e${i}`} className="text-danger-ink">
              {m}
            </li>
          ))}
          {info.messages.warnings.map((m, i) => (
            <li key={`w${i}`} className="text-amber-800">
              Warning: {m}
            </li>
          ))}
          {info.messages.hints.map((m, i) => (
            <li key={`h${i}`} className="text-amber-800/90">
              {m}
            </li>
          ))}
        </ul>
      )}
    </>
  );

  const saveText = {
    idle: '',
    pending: 'Unsaved changes…',
    saving: 'Saving…',
    saved: 'Saved',
    error: 'Not saved — will retry on your next change',
  }[saveState];

  // A locked Download step (a deep link, or the rules changed under it) shows Save & validate instead.
  const current = step === 'download' && (gate.locked || layoutMode) ? 'save' : step;

  const page = pages[pageIndex];
  const version = doc?.blueprintVersion ?? 1;
  const currentVersion = doc?.blueprintCurrentVersion ?? version;
  const reviewerComment = doc?.reviewerComment ?? doc?.reviewComment;
  const outdated = currentVersion > version;
  // The layout tools need the blueprint's edits to resend them unchanged.
  const canLayout = Boolean(doc?.canEditLayout) && !locked && Array.isArray(doc?.blueprintEdits);
  const layoutSelectedRegion = layout.selectedItems.length === 1 ? layout.selectedItems[0] : null;
  const layoutMulti = layout.selectedItems.length > 1 ? layout.selectedItems : null;
  const layoutKeyProblem = layoutMode && layoutRegions.some((r) => fieldProblem(r, layoutRegions));
  const sourceRecords = useMemo(() => {
    const seen = new Set();
    return (doc?.sourceRecords || []).filter((r) => {
      const key = `${r.templateId}:${r.recordId}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }, [doc]);

  return (
    <div>
      <PageHeader
        sticky
        crumbs={[{ label: 'My Documents', to: '/creation/documents' }, { label: doc?.title || `#${id}` }]}
        title={doc?.title || 'Document'}
        subtitle={
          doc &&
          (doc.canEditRules ? (
            <>
              Template:{' '}
              <Link to={`/creation/blueprints/${doc.blueprintId}`} className="text-accent hover:underline">
                {doc.blueprintName}
              </Link>
              {' · '}
              <Link to={`/creation/blueprints/${doc.blueprintId}?tab=rules`} className="text-accent hover:underline">
                Field rules ({rulesTotal})
              </Link>
            </>
          ) : (
            `Template: ${doc.blueprintName}`
          ))
        }
        actions={
          doc && (
            <>
              <span role="status" aria-live="polite" className="min-w-16 text-xs text-muted" data-testid="save-state">
                {saveText}
              </span>
              {!locked && (
                <Button
                  onClick={saveAndValidate}
                  disabled={validating || layoutMode || forbidden}
                  aria-describedby={forbidden ? 'save-forbidden' : undefined}
                >
                  {validating ? 'Saving…' : 'Save'}
                </Button>
              )}
              {forbidden && !locked && (
                <span id="save-forbidden" role="status" className="self-center text-xs text-danger-ink">
                  You can&apos;t edit this document
                </span>
              )}
              <Button variant="secondary" onClick={openPreview}>
                Preview
              </Button>
              <Button
                variant="secondary"
                onClick={generate}
                disabled={generating || locked || notValidated}
                aria-describedby={notValidated && !locked ? 'gate-reason' : undefined}
                title={notValidated && !locked ? gate.reason : undefined}
              >
                {generating ? 'Generating…' : 'Generate'}
              </Button>
              {doc.status === 'GENERATED' && (
                <Button
                  variant="secondary"
                  onClick={submitForRelease}
                  disabled={submitting || notValidated}
                  aria-describedby={notValidated ? 'gate-reason' : undefined}
                  title={notValidated ? gate.reason : undefined}
                >
                  {submitting ? 'Submitting…' : 'Submit for release'}
                </Button>
              )}
              <Button
                variant="secondary"
                onClick={download}
                disabled={notValidated && !locked}
                aria-describedby={notValidated && !locked ? 'gate-reason' : undefined}
                title={notValidated && !locked ? gate.reason : undefined}
              >
                Download PDF
              </Button>
              {notValidated && !locked && (
                <span id="gate-reason" className="text-xs text-amber-800">
                  {gate.reason}
                </span>
              )}
            </>
          )
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {!doc ? (
        <Spinner />
      ) : (
        <>
          <div className="mb-4 flex flex-wrap items-center gap-2 text-xs text-muted">
            <Badge status={status} />
            {['GENERATED', 'SUBMITTED', 'RELEASED'].includes(status) &&
              validation?.checked &&
              (validation.errors || []).length === 0 && (
                <Tag tone="success" className="!text-xs" title="This document passed its checks">
                  Validated
                </Tag>
              )}
            <span>
              Built on template version {version}
              {currentVersion > version && ` (newer available: version ${currentVersion})`}
            </span>
            {doc.lastGeneratedDate && <span>· last generated {new Date(doc.lastGeneratedDate).toLocaleString()}</span>}
            {doc.status === 'SUBMITTED' && <span>· awaiting release by an approver</span>}
          </div>
          {reviewerComment && doc.status !== 'SUBMITTED' && (
            <div className="mb-4">
              <Callout
                variant={doc.status === 'RELEASED' ? 'info' : 'error'}
                title={doc.status === 'RELEASED' ? 'Reviewer comment' : 'Returned for rework'}
              >
                <p className="mt-1 text-sm">{reviewerComment}</p>
              </Callout>
            </div>
          )}
          {outdated && !locked && (
            <div className="mb-4" data-testid="newer-version">
              <Callout variant="info" title={`A newer template version ${currentVersion} exists`}>
                <p className="mt-1 text-sm">
                  This document uses version {version}. Moving keeps the values of the fields that still
                  exist on the new version{canLayout ? '; move before editing the layout' : ''}.
                </p>
                <div className="mt-2">
                  <Button size="sm" onClick={moveToCurrentVersion} disabled={upgrading || layoutMode}>
                    Move to version {currentVersion}
                  </Button>
                </div>
              </Callout>
            </div>
          )}

          <Stepper
            tabs
            label="Document steps"
            idPrefix="builder"
            current={current}
            onSelect={selectStep}
            panelId={(value) => `builder-panel-${value}`}
            steps={BUILDER_STEPS.map((s) => ({
              ...s,
              done:
                s.id === 'data'
                  ? sourceRecords.length > 0
                  : s.id === 'fix'
                    ? !gate.locked || (requiredRegions.length > 0 && requiredFilled === requiredRegions.length)
                    : s.id === 'save'
                      ? !gate.locked
                      : ['GENERATED', 'SUBMITTED', 'RELEASED'].includes(status) && !gate.locked,
              locked: s.id === 'download' && (gate.locked || layoutMode),
              reason: s.id === 'download' ? (layoutMode ? 'Finish editing the layout first' : gate.reason) : '',
              describedBy: s.id === 'download' && gate.locked && !layoutMode && !locked ? 'gate-reason' : undefined,
            }))}
          />

          {current === 'data' ? (
            <div role="tabpanel" id="builder-panel-data" aria-labelledby="builder-tab-data">
              {locked ? (
                <Callout variant="info" title="Locked">
                  This document is {String(doc.status).toLowerCase()}; its values can no longer change.
                </Callout>
              ) : (
                <FillFromDataPanel
                  instance={doc}
                  onBeforeFill={flush}
                  onFilled={adopt}
                  onApplied={() => selectStep('fix')}
                  onSkip={() => selectStep('fix')}
                />
              )}
            </div>
          ) : current === 'save' ? (
            <div role="tabpanel" id="builder-panel-save" aria-labelledby="builder-tab-save">
              <SaveStep
                validation={validation}
                checkedNow={edited.size === 0}
                validating={validating}
                disabled={locked || layoutMode || forbidden}
                gate={gate}
                issues={issues}
                onSave={saveAndValidate}
                onGoTo={goToField}
                onNext={() => selectStep('download')}
              />
            </div>
          ) : current === 'download' ? (
            <div role="tabpanel" id="builder-panel-download" aria-labelledby="builder-tab-download">
              <DownloadStep
                status={status}
                docLocked={locked}
                generating={generating}
                submitting={submitting}
                onGenerate={generate}
                onDownload={download}
                onSubmit={submitForRelease}
                onPreview={openPreview}
              />
            </div>
          ) : (
          <div
            className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_22rem]"
            role="tabpanel"
            id="builder-panel-fix"
            aria-labelledby="builder-tab-fix"
          >
            <div className="min-w-0">
              {canLayout && (
                <div className="mb-3 flex flex-wrap items-center gap-2" data-testid="layout-bar">
                  {!layoutMode ? (
                    <>
                      <Button
                        size="sm"
                        variant="secondary"
                        onClick={startLayout}
                        disabled={outdated}
                        aria-describedby={outdated ? 'layout-outdated-note' : undefined}
                      >
                        Edit layout
                      </Button>
                      {outdated && (
                        <span id="layout-outdated-note" className="text-xs text-muted">
                          Move to version {currentVersion} first to edit the layout.
                        </span>
                      )}
                    </>
                  ) : (
                    <>
                      <div role="toolbar" aria-label="Layout tools" className="flex flex-wrap gap-1">
                        {[
                          ['select', 'Select'],
                          ['field', 'Add field'],
                        ].map(([value, label]) => (
                          <button
                            key={value}
                            type="button"
                            aria-pressed={layoutTool === value}
                            onClick={() => setLayoutTool(value)}
                            className={`rounded border px-2.5 py-1 text-xs ${
                              layoutTool === value
                                ? 'border-accent bg-accent text-white'
                                : 'border-line bg-white text-accent hover:bg-pill/50'
                            }`}
                          >
                            {label}
                          </button>
                        ))}
                      </div>
                      <span className="flex-1" />
                      <Button
                        size="sm"
                        onClick={saveLayout}
                        disabled={layoutSaving || !layoutDirty || layoutKeyProblem}
                        title={layoutKeyProblem ? 'Fix the field problems first (keys, dropdown options)' : undefined}
                      >
                        {layoutSaving ? 'Saving layout…' : 'Save layout to template'}
                      </Button>
                      <Button size="sm" variant="secondary" onClick={endLayout} disabled={layoutSaving}>
                        Cancel
                      </Button>
                    </>
                  )}
                </div>
              )}
              {layoutMode && (
                <p className="mb-2 text-xs text-muted" aria-live="polite">
                  {layoutTool === 'field'
                    ? `${DRAW_HINT}.`
                    : 'Drag a field to move it or its corners to resize it; Shift+click or drag on empty space selects several. With the keyboard, select a field and use the arrow keys to move it (Alt finer, Shift coarser) or Ctrl+arrows to resize it.'}
                </p>
              )}
              {layoutMode && <EditorToolbar editor={layout} />}
              <ThumbnailStrip
                pages={pages}
                current={pageIndex}
                onSelect={(index) => setPageIndex(Math.min(Math.max(0, index), pages.length - 1))}
                pathFor={(index) =>
                  blueprintPagePath(doc.blueprintId, index, { variant: 'template', version, dpi: 30 })
                }
              />
              {page && (
                <PageView
                  path={blueprintPagePath(doc.blueprintId, pageIndex, { variant: 'template', version })}
                  page={page}
                  zoom={layoutMode ? layout.zoom : 1}
                  alt={`Page ${pageIndex + 1} of ${pages.length}`}
                >
                  {({ scale }) =>
                    layoutMode ? (
                      <>
                        {layoutTool === 'select' && (
                          <MarqueeLayer onSelectRect={layout.selectInRect} onClear={layout.clearSelection} />
                        )}
                        {layoutRegions
                          .filter((region) => region.page === pageIndex)
                          .map((region) => {
                            const color = KIND_COLORS[region.kind] || KIND_COLORS.TEXT;
                            return (
                              <RegionBox
                                key={region._uid}
                                id={`layout-box-${region._uid}`}
                                box={region}
                                color={color}
                                tabText={fieldLabel(region)}
                                label={`${fieldLabel(region)}, ${KIND_LABELS[region.kind] || region.kind}`}
                                selected={layout.isSelected(region._uid)}
                                handles={layout.selected.length <= 1}
                                editable
                                onSelect={(event) =>
                                  layout.select(region._uid, {
                                    additive: Boolean(event?.shiftKey),
                                    keep: event?.type === 'pointerdown',
                                  })
                                }
                                onTap={(event) => !event?.shiftKey && layout.select(region._uid)}
                                onChange={(box) => layout.dragEnd(region._uid, box)}
                                onDelete={() =>
                                  layout.requestDelete(layout.isSelected(region._uid) ? layout.selected : [region._uid])
                                }
                                onNudge={(event) => layout.nudgeKey(event, region._uid)}
                                snap={layout.snapFor(region._uid)}
                                onGuides={layout.setGuides}
                                data-testid={`layout-region-${region.key}`}
                                style={{ background: `${color}1f` }}
                              />
                            );
                          })}
                        <SnapGuides guides={layout.guides} />
                        {layoutTool === 'field' && (
                          <DrawLayer label={DRAW_HINT} onDraw={drawLayoutRegion} color={KIND_COLORS.TEXT} />
                        )}
                      </>
                    ) : (
                    regions
                      .filter((region) => region.page === pageIndex)
                      .map((region) => {
                        const overflow = overflows(region);
                        const info = describe(region);
                        return (
                          <div
                            key={region.key}
                            className="absolute"
                            style={boxStyle(region)}
                            data-testid={`field-${region.key}`}
                            data-kind={region.kind}
                            data-invalid={info.invalid ? 'true' : undefined}
                          >
                            {PHOTO_KINDS.has(region.kind) ? (
                              <PhotoBox
                                instanceId={id}
                                region={region}
                                image={imageByRegion.get(region.key)}
                                locked={locked}
                                onPlace={place}
                                active={activeKey === region.key}
                                onFocus={() => setActiveKey(region.key)}
                              />
                            ) : (
                              <RegionInput
                                region={region}
                                value={values[region.key]}
                                onChange={(value) => setValue(region.key, value)}
                                locked={locked}
                                onPage
                                onFocus={() => setActiveKey(region.key)}
                                invalid={info.invalid}
                                describedBy={info.describedBy}
                                title={rulesTitle(checksFor(fieldChecks, region.key))}
                                style={{
                                  fontSize: `${fontPx(region, page, scale)}px`,
                                  textAlign: (region.align || 'LEFT').toLowerCase(),
                                  outline:
                                    activeKey === region.key
                                      ? `2px solid ${KIND_COLORS.TEXT}`
                                      : info.invalid
                                        ? '2px solid #c5221f'
                                        : undefined,
                                }}
                              />
                            )}
                            {info.invalid && PHOTO_KINDS.has(region.kind) && (
                              <span
                                aria-hidden="true"
                                className="pointer-events-none absolute inset-0 border-2 border-danger-ink"
                                data-testid={`invalid-${region.key}`}
                              />
                            )}
                            {overflow && (
                              <span
                                className="pointer-events-none absolute left-0 top-full z-10 whitespace-nowrap rounded bg-amber-100 px-1 text-[10px] text-amber-900 shadow"
                                data-testid={`overflow-${region.key}`}
                              >
                                {OVERFLOW_TEXT}
                              </span>
                            )}
                          </div>
                        );
                      })
                    )
                  }
                </PageView>
              )}
            </div>

            <div className="space-y-4">
              {layoutMode ? (
                <>
                  <Card padding="compact">
                    <h2 className="mb-2 text-sm font-medium">
                      {layoutMulti
                        ? 'Several fields'
                        : layoutSelectedRegion
                          ? `Field: ${fieldLabel(layoutSelectedRegion)}`
                          : 'Field properties'}
                    </h2>
                    <div ref={propertiesRef}>
                      {layoutSelectedRegion || layoutMulti ? (
                        <RegionPropertiesPanel
                          region={layoutSelectedRegion}
                          regions={layoutRegions}
                          selection={layoutMulti}
                          onChangeMany={(patchFor) => layout.updateMany(layout.selected, patchFor)}
                          fonts={doc.fonts || []}
                          rules={layoutSelectedRegion ? required.rulesFor(layoutSelectedRegion) : null}
                          onChange={(patch) => updateLayoutRegion(layoutSelectedRegion._uid, patch)}
                          onDelete={() => layout.requestDelete([layoutSelectedRegion._uid])}
                        />
                      ) : (
                        <p className="text-xs text-muted">
                          Select a field on the page or in the list to change it, or use “Add field” to draw a
                          new one.
                        </p>
                      )}
                    </div>
                  </Card>
                  <Card padding="compact">
                    <h2 className="mb-2 text-sm font-medium">Layout fields ({layoutRegions.length})</h2>
                    <FieldList
                      regions={layoutRegions}
                      testId="layout-field-list"
                      isSelected={layout.isSelected}
                      onSelect={(region, event) => {
                        layout.select(region._uid, { additive: Boolean(event?.shiftKey) });
                        if (region.page !== pageIndex) setPageIndex(region.page);
                      }}
                      onReorder={(pageNo, uids) => layout.apply((list) => reorderPage(list, pageNo, uids))}
                      rulesCount={fieldRules.data ? fieldRules.countFor : undefined}
                      isRequired={required.isRequired}
                    />
                  </Card>
                  {layoutDirty && (
                    <p className="text-xs text-amber-800" role="status">
                      Unsaved layout changes — “Save layout to template” keeps them.
                    </p>
                  )}
                </>
              ) : (
              <>
              <ValidationPanel
                doc={doc}
                status={status}
                locked={locked}
                regions={regions}
                issues={issues}
                onGoTo={goToField}
                requiredFilled={requiredFilled}
                requiredTotal={requiredRegions.length}
              />

              <Card padding="compact">
                <h2 className="mb-2 text-sm font-medium">Data from the flow</h2>
                <Button size="sm" onClick={() => setFillOpen(true)} disabled={locked}>
                  Fill from data
                </Button>
                {locked && (
                  <p className="mt-1 text-xs text-muted">
                    Locked while the document is {String(doc.status).toLowerCase()}.
                  </p>
                )}
                {sourceRecords.length > 0 && (
                  <div className="mt-2 text-xs">
                    <span className="text-muted">Filled from:</span>
                    <ul className="mt-1 flex flex-wrap gap-1" aria-label="Filled from" data-testid="source-records">
                      {sourceRecords.map((r) => (
                        <li key={`${r.templateId}:${r.recordId}`}>
                          <Tag tone="info">
                            {r.templateName || `Template ${r.templateId}`} · record {r.recordId}
                            {r.role === 'LINKED' ? ' (linked)' : ''}
                          </Tag>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </Card>

              <Card padding="compact">
                <h2 className="mb-2 text-sm font-medium">Photos</h2>
                {!locked && (
                  <>
                    <label className="block text-sm">
                      <span className="mb-1 block text-xs text-muted">
                        Choose all your photos at once (png, jpg, gif, bmp)
                      </span>
                      <input
                        ref={fileInputRef}
                        type="file"
                        multiple
                        accept=".png,.jpg,.jpeg,.gif,.bmp"
                        aria-label="Choose photos"
                        className="block w-full text-xs text-muted file:mr-2 file:cursor-pointer file:rounded file:border-0 file:bg-pill file:px-2 file:py-1 file:text-xs file:font-medium file:text-accent"
                        onChange={(e) => setPhotos(Array.from(e.target.files || []))}
                        disabled={uploading}
                      />
                    </label>
                    <div className="mt-2 flex flex-wrap gap-2">
                      <Button size="sm" onClick={() => upload(true)} disabled={uploading || photos.length === 0}>
                        Auto-fill photos
                      </Button>
                      <Button
                        size="sm"
                        variant="secondary"
                        onClick={() => upload(false)}
                        disabled={uploading || photos.length === 0}
                      >
                        Upload to tray
                      </Button>
                    </div>
                    <p className="mt-1 text-xs text-muted">
                      Auto-fill matches file names to the photo boxes, then fills the rest in reading
                      order. Drag a photo onto any photo box, or use “Place in…”.
                    </p>
                  </>
                )}
                <h3 className="mb-1 mt-3 text-xs font-medium text-muted">Tray ({tray.length})</h3>
                {tray.length === 0 ? (
                  <p className="text-xs text-muted">No photos waiting.</p>
                ) : (
                  <ul className="space-y-2" data-testid="photo-tray">
                    {tray.map((image) => (
                      <li
                        key={image.id}
                        draggable={!locked}
                        onDragStart={(event) => startPhotoDrag(event, image)}
                        data-testid={`tray-photo-${image.id}`}
                        className="flex items-center gap-2 rounded border border-line p-1"
                      >
                        <AuthedImage
                          path={imageContentPath(id, image.id)}
                          alt={image.originalName}
                          className="h-12 w-12 shrink-0 rounded object-cover"
                        />
                        <div className="min-w-0 flex-1">
                          <p className="truncate text-xs" title={image.originalName}>
                            {image.originalName}
                          </p>
                          {!locked && (
                            <select
                              aria-label={`Place ${image.originalName} in…`}
                              className={`${inputClass} !py-0.5 text-xs`}
                              value=""
                              onChange={(e) => e.target.value && place(image.id, e.target.value)}
                              disabled={photoRegions.length === 0}
                            >
                              <option value="">Place in…</option>
                              {photoRegions.map((region) => (
                                <option key={region.key} value={region.key}>
                                  {fieldLabel(region)} (page {region.page + 1})
                                  {imageByRegion.has(region.key) ? ' — swap' : ''}
                                </option>
                              ))}
                            </select>
                          )}
                        </div>
                        {!locked && (
                          <button
                            type="button"
                            aria-label={`Remove ${image.originalName}`}
                            className="px-1 text-sm text-muted hover:text-danger-ink"
                            onClick={() => removePhoto(image)}
                          >
                            ✕
                          </button>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
              </Card>

              <section aria-labelledby="fields-heading" className="rounded-lg border border-line bg-white p-3">
                <h2 id="fields-heading" className="mb-2 text-sm font-medium">
                  Fields
                </h2>
                {regions.length === 0 && <p className="text-xs text-muted">This template has no fields.</p>}
                <div className="max-h-[70vh] space-y-3 overflow-y-auto pr-1">
                  {groups.map(([groupName, list]) => (
                    <fieldset key={groupName} className="space-y-2">
                      <legend className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">{groupName}</legend>
                      {list.map((region) => {
                        const overflow = overflows(region);
                        const noteId = `overflow-note-${region.key}`;
                        const label = fieldLabel(region);
                        const info = describe(region);
                        const checks = checksFor(fieldChecks, region.key);
                        // The field's checks in short under it, read as part of its description.
                        const panelDescribedBy =
                          [info.describedBy, compactText(checks) ? checksId(region.key) : null].filter(Boolean).join(' ') ||
                          undefined;
                        const chips = <FieldCheckChips fieldKey={region.key} checks={checks} />;
                        if (PHOTO_KINDS.has(region.kind)) {
                          const image = imageByRegion.get(region.key);
                          return (
                            <div
                              key={region.key}
                              id={`field-panel-${region.key}`}
                              tabIndex={-1}
                              aria-describedby={panelDescribedBy}
                              className={`rounded p-1 text-sm outline-none ${activeKey === region.key ? 'bg-pill' : ''} ${
                                info.invalid ? 'ring-1 ring-danger-ink' : ''
                              }`}
                            >
                              <p className="font-medium">
                                {label}
                                <span className="ml-1 text-xs font-normal text-muted">page {region.page + 1}</span>
                              </p>
                              {image ? (
                                <div className="mt-1 flex items-center gap-2">
                                  <span className="min-w-0 flex-1 truncate text-xs">{image.originalName}</span>
                                  {!locked && (
                                    <select
                                      aria-label={`Move ${image.originalName} to…`}
                                      className={`${inputClass} !w-36 !py-0.5 text-xs`}
                                      value=""
                                      onFocus={() => focusField(region)}
                                      onChange={(e) =>
                                        e.target.value &&
                                        place(image.id, e.target.value === '__tray' ? null : e.target.value)
                                      }
                                    >
                                      <option value="">Move to…</option>
                                      <option value="__tray">Back to the tray</option>
                                      {photoRegions
                                        .filter((r) => r.key !== region.key)
                                        .map((r) => (
                                          <option key={r.key} value={r.key}>
                                            {fieldLabel(r)}
                                            {imageByRegion.has(r.key) ? ' — swap' : ''}
                                          </option>
                                        ))}
                                    </select>
                                  )}
                                </div>
                              ) : (
                                <p className="text-xs text-muted">No photo yet.</p>
                              )}
                              {chips}
                              {fieldNotes(region, info)}
                            </div>
                          );
                        }
                        const input = (
                          <RegionInput
                            id={`field-input-${region.key}`}
                            region={region}
                            value={values[region.key]}
                            onChange={(value) => setValue(region.key, value)}
                            locked={locked}
                            onFocus={() => focusField(region)}
                            describedBy={panelDescribedBy}
                            invalid={info.invalid}
                          />
                        );
                        return (
                          <div
                            key={region.key}
                            className={`rounded p-1 ${activeKey === region.key ? 'bg-pill' : ''}`}
                          >
                            {region.kind === 'CHECKBOX' ? (
                              <label className="flex items-center gap-2 text-sm">
                                {input}
                                {label}
                                {region.required && <span className="text-danger-ink">*</span>}
                              </label>
                            ) : (
                              <label className="block text-sm">
                                <span className="mb-0.5 block">
                                  {label}
                                  {region.required && <span className="ml-0.5 text-danger-ink">*</span>}
                                  {/* A real space: inline spans alone make one word of the name ("Full namepage 1"). */}
                                  {' '}
                                  <span className="text-xs text-muted">page {region.page + 1}</span>
                                </span>
                                {input}
                              </label>
                            )}
                            {chips}
                            {overflow && (
                              <p id={noteId} className="mt-0.5">
                                <Tag tone="warning">{OVERFLOW_TEXT}</Tag>
                              </p>
                            )}
                            {fieldNotes(region, info)}
                          </div>
                        );
                      })}
                    </fieldset>
                  ))}
                </div>
              </section>
              </>
              )}
            </div>
          </div>
          )}
        </>
      )}

      <PreviewDialog open={previewOpen} onClose={() => setPreviewOpen(false)} instanceId={id} pages={pages} />

      {doc && (
        <FillFromDataDialog
          open={fillOpen && !locked}
          onClose={() => setFillOpen(false)}
          instance={doc}
          onBeforeFill={flush}
          onFilled={adopt}
        />
      )}

      <DeleteFieldsDialog editor={layout} nameOf={fieldLabel} />

      <Modal
        open={layoutConflict}
        onClose={() => setLayoutConflict(false)}
        title="The template was changed elsewhere"
        footer={
          <>
            <Button variant="secondary" onClick={() => setLayoutConflict(false)}>
              Keep editing
            </Button>
            <Button onClick={reloadAfterLayoutConflict}>Reload the latest version</Button>
          </>
        }
      >
        <p>
          Someone saved a newer version of this template while you were moving fields, so your layout was
          not saved. Reload to see the latest version (your layout changes are discarded), then move this
          document to it before editing the layout again.
        </p>
      </Modal>
    </div>
  );
}
