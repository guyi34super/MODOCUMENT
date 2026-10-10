import { useEffect, useId, useRef, useState } from 'react';
import { updateField, updateTemplate } from '../api/templates';
import { isPendingChange } from '../api/changes';
import { Button, Card, Tag, inputClass } from '../components/ui';
import { useToast } from '../context/ToastContext';

/**
 * ws23 extraction settings of a template (TemplateEditorPage, AC-EXT-16):
 *
 * - the template's extraction mode — inherit the bank default (shown), AI
 *   with code fallback, code only or AI only;
 * - per field, the hints the code extractor uses: aliases, a label pattern,
 *   a value pattern and date formats.
 *
 * The server validates everything (SafeRegex for patterns, DateTimeFormatter
 * for date formats, the mode vocabulary) and answers 400 naming the hint;
 * those messages are shown next to the input they concern.
 */

/** ExtractionMode (docs/phase2/contracts.md §4) → label and what it does. */
export const EXTRACTION_MODES = [
  {
    value: 'AI_WITH_CODE_FALLBACK',
    label: 'AI with code fallback',
    help: 'The AI reads each document. When the AI is off, unavailable or fails, code extraction takes over and the job shows "Extracted by code".',
  },
  {
    value: 'CODE_ONLY',
    label: 'Code only',
    help: 'The AI is never called. Parsers and code extraction read the documents; a result below the confidence threshold is kept but flagged as low confidence.',
  },
  {
    value: 'AI_ONLY',
    label: 'AI only',
    help: 'The AI or nothing: if the AI cannot read a document, the upload fails instead of falling back to code.',
  },
];

export const modeLabel = (value) =>
  EXTRACTION_MODES.find((m) => m.value === value)?.label ?? value;

const HINT_KEYS = ['aliases', 'labelPattern', 'valuePattern', 'dateFormats'];

/**
 * Splits the server's 400 message ("labelPattern: … valuePattern: …") into
 * one message per hint; text naming no hint lands in `form`.
 */
export function hintErrors(message) {
  if (!message) return {};
  const found = [...message.matchAll(/\b(aliases|labelPattern|valuePattern|dateFormats):\s*/g)];
  if (found.length === 0) return { form: message };
  const errors = {};
  if (found[0].index > 0) errors.form = message.slice(0, found[0].index).trim();
  found.forEach((match, i) => {
    const start = match.index + match[0].length;
    const end = i + 1 < found.length ? found[i + 1].index : message.length;
    const text = message.slice(start, end).trim();
    errors[match[1]] = errors[match[1]] ? `${errors[match[1]]} ${text}` : text;
  });
  return errors;
}

/** One entry per line; blank lines dropped. */
const toList = (text) =>
  text
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);

const fromList = (list) => (Array.isArray(list) ? list.join('\n') : '');

function hintsSummary(field) {
  const parts = [];
  const aliases = field.aliases?.length ?? 0;
  if (aliases) parts.push(`${aliases} alias${aliases === 1 ? '' : 'es'}`);
  if (field.labelPattern) parts.push('label pattern');
  if (field.valuePattern) parts.push('value pattern');
  const formats = field.dateFormats?.length ?? 0;
  if (formats) parts.push(`${formats} date format${formats === 1 ? '' : 's'}`);
  return parts.length ? parts.join(' · ') : 'No hints — matched by its name';
}

/** The template-level mode select. */
function ModeSetting({ template, readOnly, onSaved }) {
  const toast = useToast();
  const id = useId();
  const stored = template.extractionMode || '';
  const [mode, setMode] = useState(stored);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [pending, setPending] = useState(null);

  useEffect(() => {
    setMode(template.extractionMode || '');
  }, [template.extractionMode]);

  const inherited = template.defaultExtractionMode;
  const effective = mode || inherited;
  const help = EXTRACTION_MODES.find((m) => m.value === effective)?.help;
  const dirty = mode !== stored;

  const save = async () => {
    setBusy(true);
    setError('');
    try {
      const result = await updateTemplate({
        templateId: template.templateId,
        name: template.name,
        description: template.description,
        extractionMode: mode || null,
      });
      if (isPendingChange(result)) {
        setPending(result.pendingChangeId);
        toast.success(`Extraction mode submitted for approval (change #${result.pendingChangeId})`);
      } else {
        setPending(null);
        onSaved?.({ extractionMode: mode || null });
        toast.success(
          mode ? `Extraction mode set to ${modeLabel(mode)}` : 'Extraction mode now follows the bank default'
        );
      }
    } catch (err) {
      setError(err.message || 'The extraction mode could not be saved.');
    } finally {
      setBusy(false);
    }
  };

  const describedBy = [`${id}-help`, error ? `${id}-error` : null].filter(Boolean).join(' ');
  return (
    <div>
      <label htmlFor={`${id}-mode`} className="mb-1 block text-sm font-medium text-ink">
        Extraction mode
      </label>
      <div className="flex flex-wrap items-center gap-2">
        <select
          id={`${id}-mode`}
          className={`${inputClass} w-72`}
          value={mode}
          disabled={readOnly || busy}
          aria-describedby={describedBy}
          aria-invalid={error ? true : undefined}
          onChange={(e) => {
            setMode(e.target.value);
            setError('');
          }}
        >
          <option value="">
            {inherited
              ? `Inherit bank default (${modeLabel(inherited)})`
              : 'Inherit bank default'}
          </option>
          {EXTRACTION_MODES.map((m) => (
            <option key={m.value} value={m.value}>
              {m.label}
            </option>
          ))}
        </select>
        {!readOnly && (
          <>
            <Button type="button" onClick={save} disabled={!dirty || busy}>
              {busy ? 'Saving…' : 'Save mode'}
            </Button>
            {dirty && (
              <Button
                type="button"
                variant="secondary"
                onClick={() => {
                  setMode(stored);
                  setError('');
                }}
              >
                Undo
              </Button>
            )}
          </>
        )}
        {!mode && <Tag title="Follows the bank's extraction.defaultMode setting">Inherited</Tag>}
        {pending && (
          <Tag tone="info" title={`Waiting for a second user's approval (change #${pending})`}>
            Pending approval
          </Tag>
        )}
      </div>
      <p id={`${id}-help`} className="mt-1 text-xs text-muted">
        {help}
        {!mode && inherited ? ' Changes to the bank default apply to this template too.' : ''}
      </p>
      {error && (
        <p id={`${id}-error`} role="alert" className="mt-1 text-xs text-danger-ink">
          {error}
        </p>
      )}
    </div>
  );
}

/** One hint input (or textarea) with its label, hint and inline error. */
function HintInput({ id, label, hint, error, multiline, value, onChange, readOnly, inputRef, placeholder }) {
  const describedBy = [`${id}-hint`, error ? `${id}-error` : null].filter(Boolean).join(' ');
  const props = {
    id,
    ref: inputRef,
    value,
    readOnly,
    placeholder,
    'aria-describedby': describedBy,
    'aria-invalid': error ? true : undefined,
    className: `${inputClass} font-mono ${error ? 'border-danger-ink' : ''}`,
    onChange: (e) => onChange(e.target.value),
    spellCheck: false,
  };
  return (
    <div>
      <label htmlFor={id} className="mb-1 block text-sm font-medium text-ink">
        {label}
      </label>
      {multiline ? <textarea rows={3} {...props} /> : <input type="text" {...props} />}
      <p id={`${id}-hint`} className="mt-1 text-xs text-muted">
        {hint}
      </p>
      {error && (
        <p id={`${id}-error`} role="alert" className="mt-1 text-xs text-danger-ink">
          {error}
        </p>
      )}
    </div>
  );
}

function draftOf(field) {
  return {
    aliases: fromList(field.aliases),
    labelPattern: field.labelPattern || '',
    valuePattern: field.valuePattern || '',
    dateFormats: fromList(field.dateFormats),
  };
}

/** A field's hints: a summary row that expands into the editor. */
function FieldHints({ field, typeName, readOnly, onSaved }) {
  const toast = useToast();
  const id = useId();
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState(() => draftOf(field));
  const [errors, setErrors] = useState({});
  const [busy, setBusy] = useState(false);
  const firstInput = useRef(null);
  const toggle = useRef(null);

  useEffect(() => {
    if (!open) setDraft(draftOf(field));
  }, [field, open]);

  useEffect(() => {
    if (open) firstInput.current?.focus();
  }, [open]);

  const set = (key) => (value) => {
    setDraft((d) => ({ ...d, [key]: value }));
    setErrors((e) => ({ ...e, [key]: undefined, form: undefined }));
  };

  const close = () => {
    setOpen(false);
    setErrors({});
    toggle.current?.focus();
  };

  const save = async (e) => {
    e.preventDefault();
    setBusy(true);
    setErrors({});
    try {
      const updated = await updateField(field.templateFieldsId, {
        templateFieldsId: field.templateFieldsId,
        templateId: field.templateId,
        fieldName: field.fieldName,
        templateDataTypeId: field.templateDataTypeId,
        piiKind: field.piiKind ?? null,
        aliases: toList(draft.aliases),
        labelPattern: draft.labelPattern.trim() || null,
        valuePattern: draft.valuePattern.trim() || null,
        dateFormats: toList(draft.dateFormats),
      });
      onSaved?.({ ...field, ...(updated || {}) });
      toast.success(`Extraction hints of "${field.fieldName}" saved`);
      close();
    } catch (err) {
      setErrors(hintErrors(err.message || 'The hints could not be saved.'));
    } finally {
      setBusy(false);
    }
  };

  const panelId = `${id}-panel`;
  const isDate = /date/i.test(typeName || '');
  return (
    <li className="py-3">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="min-w-32 text-sm font-medium text-ink">{field.fieldName}</span>
        <span className="flex-1 text-xs text-muted">{hintsSummary(field)}</span>
        <Button
          ref={toggle}
          type="button"
          size="sm"
          variant="secondary"
          aria-expanded={open}
          aria-controls={panelId}
          onClick={() => (open ? close() : setOpen(true))}
        >
          {open ? 'Close' : readOnly ? 'View hints' : 'Edit hints'}
          <span className="sr-only"> for {field.fieldName}</span>
        </Button>
      </div>
      {open && (
        <form id={panelId} onSubmit={save} className="mt-3 rounded-md border border-line bg-neutral-50 p-4" noValidate>
          <fieldset className="space-y-3" disabled={busy}>
            <legend className="sr-only">Extraction hints for {field.fieldName}</legend>
            {errors.form && (
              <p role="alert" className="text-xs text-danger-ink">
                {errors.form}
              </p>
            )}
            <HintInput
              id={`${id}-aliases`}
              inputRef={firstInput}
              label="Aliases"
              multiline
              readOnly={readOnly}
              value={draft.aliases}
              onChange={set('aliases')}
              placeholder={'Cust Name\nClient'}
              error={errors.aliases}
              hint="Other column headers or labels this field appears under, one per line. Matching ignores case."
            />
            <div className="grid gap-3 md:grid-cols-2">
              <HintInput
                id={`${id}-label`}
                label="Label pattern"
                readOnly={readOnly}
                value={draft.labelPattern}
                onChange={set('labelPattern')}
                placeholder="Cust(omer)?\s*(No|Number)"
                error={errors.labelPattern}
                hint="Regular expression for the field's label in label: value documents."
              />
              <HintInput
                id={`${id}-value`}
                label="Value pattern"
                readOnly={readOnly}
                value={draft.valuePattern}
                onChange={set('valuePattern')}
                placeholder="REF-(\d{4}-\d{4})"
                error={errors.valuePattern}
                hint="Regular expression for the value; with a capture group, group 1 is the value."
              />
            </div>
            <HintInput
              id={`${id}-dates`}
              label="Date formats"
              multiline
              readOnly={readOnly}
              value={draft.dateFormats}
              onChange={set('dateFormats')}
              placeholder={'dd/MM/yyyy\nd MMM yyyy'}
              error={errors.dateFormats}
              hint={`Date patterns tried before the built-in ones, one per line (dd day, MM month, MMM month name, yyyy year).${
                isDate ? '' : ' Used when this field holds dates.'
              }`}
            />
            {!readOnly && (
              <div className="flex gap-2">
                <Button type="submit" disabled={busy}>
                  {busy ? 'Saving…' : 'Save hints'}
                </Button>
                <Button type="button" variant="secondary" onClick={close}>
                  Cancel
                </Button>
              </div>
            )}
          </fieldset>
        </form>
      )}
    </li>
  );
}

/**
 * @param template   {templateId, name, description, extractionMode, defaultExtractionMode}
 *                   as the server holds it (name/description are re-sent unchanged)
 * @param fields     the template's saved fields (with their ws23 hints)
 * @param typeName   templateDataTypeId → data type name
 * @param readOnly   a shared template the caller may not change
 * @param onTemplateSaved  receives the saved patch ({extractionMode})
 * @param onFieldSaved     receives the updated field
 */
export default function TemplateExtractionSection({
  template,
  fields = [],
  typeName,
  readOnly = false,
  onTemplateSaved,
  onFieldSaved,
}) {
  const headingId = useId();
  return (
    <Card>
      <section aria-labelledby={headingId} data-testid="template-extraction">
        <h2 id={headingId} className="mb-1 text-lg font-normal">
          Extraction
        </h2>
        <p className="mb-4 text-sm text-muted">
          How uploads that use this template are read, and the hints code extraction uses to find
          each field.
        </p>
        <ModeSetting template={template} readOnly={readOnly} onSaved={onTemplateSaved} />

        <h3 className="mt-6 text-sm font-medium text-ink">Field hints</h3>
        <p className="text-xs text-muted">
          Help code extraction find fields whose headers or labels differ from the field name.
        </p>
        {fields.length === 0 ? (
          <p className="mt-2 text-sm text-muted">Add fields above to give them hints.</p>
        ) : (
          <ul className="divide-y divide-line/70" aria-label="Field extraction hints">
            {fields.map((field) => (
              <FieldHints
                key={field.templateFieldsId}
                field={field}
                typeName={typeName?.(field.templateDataTypeId)}
                readOnly={readOnly}
                onSaved={onFieldSaved}
              />
            ))}
          </ul>
        )}
      </section>
    </Card>
  );
}
