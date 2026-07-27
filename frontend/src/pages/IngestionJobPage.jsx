import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { downloadFile } from '../api/client';
import { getAllFields } from '../api/templates';
import { validationErrorRuleLabel } from '../api/validationRules';
import {
  cancelJob,
  chatEdit,
  exportFilePath,
  exportToDatabase,
  getJob,
  remapJob,
  resumeJob,
  revalidateJob,
  setWizardStep,
  updateMapping,
  updateRows,
} from '../api/ingestion';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import ValidationChatDrawer from '../components/ValidationChatDrawer';
import { Badge, Button, Callout, Card, ErrorBanner, Field, inputClass, Spinner } from '../components/ui';
import { useToast } from '../context/ToastContext';

const WIZARD_STEPS = ['mapping', 'validation', 'export'];

const ACTION_MESSAGES = {
  resume: { loading: 'Resuming pipeline…', success: 'Pipeline resumed' },
  remap: { loading: 'Re-mapping columns…', success: 'Columns re-mapped' },
  revalidate: { loading: 'Re-validating rows…', success: 'Rows re-validated' },
  cancel: { loading: 'Cancelling job…', success: 'Job cancelled' },
  mapping: { loading: 'Saving mapping…', success: 'Mapping saved' },
  'chat-edit': { loading: 'Applying chat edit…', success: 'Chat edit applied' },
  'db-export': { loading: 'Exporting to database…', success: 'Exported to database' },
  validate: { loading: 'Running validation…', success: 'Validation complete' },
  rows: { loading: 'Saving edits…', success: 'Edits saved & re-validated' },
};

function defaultWizardStep(currentStep) {
  if (currentStep === 'EXPORTED') return 'export';
  if (currentStep === 'VALIDATED') return 'validation';
  return 'mapping';
}

function canVisitStep(step, job) {
  if (!job) return step === 'mapping';
  if (step === 'mapping') return true;
  if (step === 'validation') {
    return job.currentStep === 'VALIDATED' || job.currentStep === 'EXPORTED';
  }
  if (step === 'export') {
    return Boolean(job.mappedRows);
  }
  return false;
}

// The step checkpointed on the job wins over inference from currentStep, so a
// reopened job resumes where the user left off (including the client-only
// export step). Stale checkpoints degrade to the inferred step.
function restoredWizardStep(job) {
  const persisted = job?.wizardStep?.toLowerCase();
  if (persisted && WIZARD_STEPS.includes(persisted) && canVisitStep(persisted, job)) {
    return persisted;
  }
  return defaultWizardStep(job?.currentStep);
}

function WizardStepIndicator({ activeStep, job, onSelect }) {
  const labels = { mapping: 'Mapping', validation: 'Validation', export: 'Export' };
  return (
    <ol className="flex flex-wrap items-center gap-1 text-xs">
      {WIZARD_STEPS.map((step, i) => {
        const enabled = canVisitStep(step, job);
        const isActive = step === activeStep;
        const isDone =
          (step === 'mapping' && job?.mappedRows) ||
          (step === 'validation' && job?.validationResults) ||
          (step === 'export' && job?.exportInfo && Object.keys(job.exportInfo).length > 0);
        return (
          <li key={step} className="flex items-center gap-1">
            {i > 0 && <span className="text-line">──</span>}
            <button
              type="button"
              disabled={!enabled}
              onClick={() => enabled && onSelect(step)}
              className={`rounded-full px-2.5 py-1 font-medium ${
                isActive
                  ? 'bg-accent text-white'
                  : isDone
                    ? 'bg-success-bg text-success-ink'
                    : enabled
                      ? 'bg-neutral-100 text-ink hover:bg-pill'
                      : 'bg-neutral-100 text-muted'
              } ${enabled ? 'cursor-pointer' : 'cursor-not-allowed opacity-60'}`}
            >
              {isDone && !isActive ? '✓ ' : ''}
              {labels[step]}
            </button>
          </li>
        );
      })}
    </ol>
  );
}

function MappingPanel({ parsedTable, mappedRows, mapping, templateFields, onMappingChange }) {
  const sourceColumns = parsedTable?.columns ?? [];
  const firstParsedRow = parsedTable?.rows?.[0] ?? {};
  const firstMappedRow = mappedRows?.rows?.[0] ?? {};

  const reverseMapping = useMemo(() => {
    const rev = {};
    for (const [source, target] of Object.entries(mapping)) {
      if (target) rev[target] = source;
    }
    return rev;
  }, [mapping]);

  if (!sourceColumns.length) {
    return <p className="text-sm text-muted">No source columns to map.</p>;
  }

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <div className="min-w-0 rounded-md border border-line p-3">
        <h3 className="mb-3 text-sm font-medium text-muted">Source columns</h3>
        <div className="space-y-3">
          {sourceColumns.map((col) => (
            <div key={col} className="space-y-1">
              <div className="flex items-center gap-2 text-sm">
                <span className="w-1/3 truncate font-mono text-xs" title={col}>
                  {col}
                </span>
                <select
                  className={`${inputClass} flex-1`}
                  value={mapping[col] ?? ''}
                  onChange={(e) => onMappingChange(col, e.target.value || null)}
                >
                  <option value="">(unmapped)</option>
                  {templateFields.map((f) => (
                    <option key={f.templateFieldsId} value={f.fieldName}>
                      {f.fieldName}
                    </option>
                  ))}
                </select>
              </div>
              {firstParsedRow[col] != null && firstParsedRow[col] !== '' && (
                <p className="truncate pl-1 text-xs text-muted" title={String(firstParsedRow[col])}>
                  Preview: {String(firstParsedRow[col])}
                </p>
              )}
            </div>
          ))}
        </div>
      </div>

      <div className="min-w-0 rounded-md border border-line p-3">
        <h3 className="mb-3 text-sm font-medium text-muted">Template fields</h3>
        <div className="space-y-3">
          {templateFields.map((field) => {
            const sourceCol = reverseMapping[field.fieldName];
            const mapped = Boolean(sourceCol);
            return (
              <div key={field.templateFieldsId} className="space-y-1">
                <div className="flex items-center gap-2 text-sm">
                  <span className="font-medium">{field.fieldName}</span>
                  <span
                    className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${
                      mapped ? 'bg-success-bg text-success-ink' : 'bg-neutral-100 text-muted'
                    }`}
                  >
                    {mapped ? 'Mapped' : 'Unmapped'}
                  </span>
                </div>
                <p className="text-xs text-muted">
                  {mapped ? (
                    <>
                      Mapped from <span className="font-mono">{sourceCol}</span>
                    </>
                  ) : (
                    'Not mapped'
                  )}
                </p>
                {mapped && firstMappedRow[field.fieldName] != null && firstMappedRow[field.fieldName] !== '' && (
                  <p
                    className="truncate text-xs text-muted"
                    title={String(firstMappedRow[field.fieldName])}
                  >
                    Preview: {String(firstMappedRow[field.fieldName])}
                  </p>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

const PAGE_SIZE_OPTIONS = [5, 10, 15, 20];

function buildValidationErrorMap(validationResults) {
  const map = new Map();
  for (const result of validationResults || []) {
    if (result.errorMessage) {
      map.set(`${result.row}-${result.fieldName}`, result);
    }
  }
  return map;
}

function getSortedErrorRows(validationResults) {
  return [
    ...new Set(
      (validationResults || [])
        .filter((r) => r.errorMessage)
        .map((r) => r.row)
    ),
  ].sort((a, b) => a - b);
}

function pageForRow(row, pageSize) {
  return Math.floor((row - 1) / pageSize);
}

function ValidationErrorCell({ err, value }) {
  const displayValue = value === '' || value == null ? '(empty)' : String(value);
  return (
    <div
      className="rounded border border-danger-ink bg-danger-bg px-1.5 py-1"
      title={err.errorMessage}
    >
      <div className="font-mono text-xs">{displayValue}</div>
      <div className="mt-1 text-xs text-danger-ink">Fix: {err.errorMessage}</div>
      {err.ruleType && (
        <div className="text-xs text-muted">
          Rule: {validationErrorRuleLabel(err.ruleType)}
        </div>
      )}
    </div>
  );
}

function EditableCell({ value, err, dirty, editing, onStartEdit, onCommit, onCancel }) {
  if (editing) {
    return (
      <input
        autoFocus
        defaultValue={value ?? ''}
        className={`${inputClass} w-full min-w-28`}
        onBlur={(e) => onCommit(e.target.value)}
        onKeyDown={(e) => {
          // Commit lives solely in onBlur so Enter can't double-commit;
          // Escape unmounts the input, so no blur fires after it.
          if (e.key === 'Enter') e.currentTarget.blur();
          if (e.key === 'Escape') onCancel();
        }}
      />
    );
  }
  return (
    <div
      role="button"
      tabIndex={0}
      title="Click to edit"
      onClick={onStartEdit}
      onKeyDown={(e) => e.key === 'Enter' && onStartEdit()}
      className={`min-h-5 cursor-text rounded ${dirty ? 'bg-pill/60 px-1 outline outline-1 outline-accent' : ''}`}
    >
      {err ? (
        <ValidationErrorCell err={err} value={value} />
      ) : value === '' || value == null ? (
        ' '
      ) : (
        String(value)
      )}
    </div>
  );
}

function ValidatedDataTable({
  mappedRows,
  validationResults,
  page,
  pageSize,
  onPageChange,
  onPageSizeChange,
  rowEdits,
  onEditCell,
}) {
  const [editingCell, setEditingCell] = useState(null);

  if (!mappedRows?.columns?.length) {
    return <p className="text-sm text-muted">No mapped data.</p>;
  }

  const allRows = mappedRows.rows;
  const totalRows = allRows.length;
  const totalPages = Math.max(1, Math.ceil(totalRows / pageSize));
  const safePage = Math.min(page, totalPages - 1);
  const start = safePage * pageSize;
  const end = Math.min(start + pageSize, totalRows);
  const rows = allRows.slice(start, end);
  const errorMap = buildValidationErrorMap(validationResults);
  const errorRows = getSortedErrorRows(validationResults);
  const pageEndRow = end;

  const jumpToNextError = () => {
    const next = errorRows.find((row) => row > pageEndRow) ?? errorRows[0];
    if (next != null) {
      onPageChange(pageForRow(next, pageSize));
    }
  };

  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center gap-3 text-sm">
        <label className="flex items-center gap-2">
          <span className="text-muted">Rows per page</span>
          <select
            className={`${inputClass} w-20`}
            value={pageSize}
            onChange={(e) => onPageSizeChange(Number(e.target.value))}
          >
            {PAGE_SIZE_OPTIONS.map((size) => (
              <option key={size} value={size}>
                {size}
              </option>
            ))}
          </select>
        </label>
        <div className="flex items-center gap-2">
          <Button
            variant="secondary"
            onClick={() => onPageChange(Math.max(0, safePage - 1))}
            disabled={safePage === 0}
          >
            Previous
          </Button>
          <Button
            variant="secondary"
            onClick={() => onPageChange(Math.min(totalPages - 1, safePage + 1))}
            disabled={safePage >= totalPages - 1}
          >
            Next
          </Button>
        </div>
        <span className="text-muted">
          Rows {totalRows === 0 ? 0 : start + 1}–{end} of {totalRows}
        </span>
        <span className="text-muted">
          Page {safePage + 1} of {totalPages}
        </span>
        {errorRows.length > 0 && (
          <Button variant="secondary" onClick={jumpToNextError}>
            Next error
          </Button>
        )}
      </div>

      <DataTable
        columns={[
          {
            key: '_row',
            label: 'Row',
            render: (_row, i) => start + i + 1,
          },
          ...mappedRows.columns.map((col) => ({
            key: col,
            label: col,
            render: (row, i) => {
              const rowIdx = start + i; // absolute 0-based index — drafts survive paging
              const draftValue = rowEdits?.[`${rowIdx}-${col}`];
              const err = errorMap.get(`${rowIdx + 1}-${col}`);
              const editing = editingCell?.rowIdx === rowIdx && editingCell?.col === col;
              return (
                <EditableCell
                  value={draftValue !== undefined ? draftValue : row[col]}
                  err={err}
                  dirty={draftValue !== undefined}
                  editing={editing}
                  onStartEdit={() => setEditingCell({ rowIdx, col })}
                  onCommit={(v) => {
                    setEditingCell(null);
                    onEditCell(rowIdx, col, v);
                  }}
                  onCancel={() => setEditingCell(null)}
                />
              );
            },
          })),
        ]}
        rows={rows}
      />
    </div>
  );
}

function MappingStep({
  job,
  mapping,
  mappingDraft,
  templateFields,
  busyAction,
  onMappingChange,
  onSaveMapping,
  onDiscardMapping,
  onRemap,
  onResume,
  onContinue,
}) {
  const hasDraft = Boolean(mappingDraft);
  const needsProcessing = job.currentStep === 'UPLOADED' || job.currentStep === 'PARSED';
  return (
    <Card>
      <h2 className="mb-1 text-lg font-normal">Column mapping</h2>
      <p className="mb-4 text-sm text-muted">
        Review source columns mapped to template fields. Adjust mappings, then continue to validation.
      </p>

      {needsProcessing && (
        <div className="mb-5">
          <Callout variant="info" title="Processing not finished">
            This upload hasn&apos;t been {job.currentStep === 'UPLOADED' ? 'parsed' : 'mapped'} yet.
            Resume the pipeline to {job.currentStep === 'UPLOADED' ? 'parse the file and ' : ''}
            auto-map its columns to your template.
            <div className="mt-2">
              <Button onClick={onResume} disabled={Boolean(busyAction) || job.status === 'CANCELLED'}>
                {busyAction === 'resume' ? 'Resuming…' : 'Resume processing'}
              </Button>
            </div>
          </Callout>
        </div>
      )}

      {job.parsedTable && (
        <div className="mb-5">
          <MappingPanel
            parsedTable={job.parsedTable}
            mappedRows={job.mappedRows}
            mapping={mapping}
            templateFields={templateFields}
            onMappingChange={onMappingChange}
          />
        </div>
      )}

      {!job.parsedTable && job.mapping && (
        <p className="mb-4 text-sm text-muted">Parsed columns are not available for this job.</p>
      )}

      <div className="mt-4 flex flex-wrap gap-2">
        {hasDraft && (
          <>
            <Button onClick={onSaveMapping} disabled={Boolean(busyAction)}>
              {busyAction === 'mapping' ? 'Saving…' : 'Save mapping'}
            </Button>
            <Button variant="secondary" onClick={onDiscardMapping}>
              Discard changes
            </Button>
          </>
        )}
        <Button variant="secondary" onClick={onRemap} disabled={Boolean(busyAction)}>
          {busyAction === 'remap' ? 'Remapping…' : 'Re-map automatically'}
        </Button>
        <Button
          onClick={onContinue}
          disabled={Boolean(busyAction) || hasDraft || job.currentStep !== 'MAPPED'}
        >
          {busyAction === 'validate' ? 'Validating…' : 'Continue to validation'}
        </Button>
      </div>
      {hasDraft && (
        <p className="mt-2 text-xs text-muted">Save or discard mapping changes before continuing.</p>
      )}
    </Card>
  );
}

function ValidationStep({
  job,
  hasValidationResults,
  validationErrors,
  chatMessages,
  chatOpen,
  onChatOpenChange,
  onChatSend,
  busyAction,
  onRevalidate,
  onContinue,
  canExport,
  rowEdits,
  onEditCell,
  rowsDirty,
  onSaveRows,
  onDiscardRows,
}) {
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(10);

  const handlePageSizeChange = (size) => {
    setPageSize(size);
    setPage(0);
  };

  return (
    <>
      <Card>
        <h2 className="mb-1 text-lg font-normal">Validation</h2>
        <p className="mb-4 text-sm text-muted">
          Rows are checked against your template rules. Fix issues with the AI assistant, then
          re-validate.
        </p>

        {!hasValidationResults ? (
          <Callout variant="error" title="Validation not run yet">
            Go back to mapping and click Continue to validation.
          </Callout>
        ) : (
          <>
            {validationErrors.length === 0 ? (
              <Callout variant="success" title="All rows valid">
                Every mapped row passed the template&apos;s validation rules.
              </Callout>
            ) : (
              <div className="mb-3">
                <Callout
                  variant="error"
                  title={`${validationErrors.length} validation error${validationErrors.length > 1 ? 's' : ''}`}
                >
                  Fix the highlighted cells below or use the AI assistant, then re-validate.
                </Callout>
              </div>
            )}

            {job.mappedRows && (
              <div className="mb-5">
                <h3 className="mb-2 text-sm font-medium text-muted">Mapped data</h3>
                <ValidatedDataTable
                  mappedRows={job.mappedRows}
                  validationResults={job.validationResults ?? []}
                  page={page}
                  pageSize={pageSize}
                  onPageChange={setPage}
                  onPageSizeChange={handlePageSizeChange}
                  rowEdits={rowEdits}
                  onEditCell={onEditCell}
                />
              </div>
            )}
          </>
        )}

        <div className="mt-4 flex flex-wrap gap-2">
          {rowsDirty && (
            <>
              <Button onClick={onSaveRows} disabled={Boolean(busyAction)}>
                {busyAction === 'rows' ? 'Saving…' : 'Save changes'}
              </Button>
              <Button variant="secondary" onClick={onDiscardRows} disabled={Boolean(busyAction)}>
                Discard changes
              </Button>
            </>
          )}
          <Button
            variant="secondary"
            onClick={onRevalidate}
            disabled={Boolean(busyAction) || rowsDirty}
          >
            {busyAction === 'revalidate' ? 'Validating…' : 'Re-validate'}
          </Button>
          <Button onClick={onContinue} disabled={!canExport || Boolean(busyAction) || rowsDirty}>
            Continue to export
          </Button>
        </div>
        {rowsDirty && (
          <p className="mt-2 text-xs text-muted">Save or discard row changes before continuing.</p>
        )}
      </Card>

      <ValidationChatDrawer
        open={chatOpen}
        onOpenChange={onChatOpenChange}
        messages={chatMessages}
        onSend={onChatSend}
        busy={busyAction === 'chat-edit' || rowsDirty}
        showQuickPrompts={validationErrors.length > 0}
      />
    </>
  );
}

function ExportStep({
  canExport,
  exportFormat,
  showDbExport,
  dbForm,
  busyAction,
  exportInfo,
  onFormatChange,
  onDownload,
  onToggleDbExport,
  onDbFormChange,
  onDbExport,
}) {
  return (
    <Card>
      <h2 className="mb-1 text-lg font-normal">Export</h2>
      {!canExport ? (
        <p className="mb-3 text-sm text-muted">Complete validation before exporting.</p>
      ) : (
        <p className="mb-3 text-sm text-muted">
          Download the validated data as a file or push it to an external database.
        </p>
      )}

      {exportInfo && Object.keys(exportInfo).length > 0 && (
        <div className="mb-4">
          <Callout variant="success" title="Exported">
            <code className="text-xs">{JSON.stringify(exportInfo)}</code>
          </Callout>
        </div>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <select
          className={`${inputClass} w-28`}
          value={exportFormat}
          onChange={(e) => onFormatChange(e.target.value)}
          disabled={!canExport}
        >
          <option value="csv">CSV</option>
          <option value="json">JSON</option>
          <option value="xlsx">XLSX</option>
        </select>
        <Button onClick={onDownload} disabled={!canExport || Boolean(busyAction)}>
          {busyAction === 'download' ? 'Preparing…' : 'Download file'}
        </Button>
        <Button variant="secondary" onClick={onToggleDbExport} disabled={!canExport}>
          {showDbExport ? 'Hide database export' : 'Export to database…'}
        </Button>
      </div>

      {showDbExport && (
        <form onSubmit={onDbExport} className="mt-4 space-y-3 border-t border-line pt-4">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label="JDBC URL">
              <input
                required
                className={inputClass}
                placeholder="jdbc:sqlserver://host;databaseName=…"
                value={dbForm.jdbcUrl}
                onChange={(e) => onDbFormChange({ jdbcUrl: e.target.value })}
              />
            </Field>
            <Field label="Table name">
              <input
                required
                className={inputClass}
                value={dbForm.tableName}
                onChange={(e) => onDbFormChange({ tableName: e.target.value })}
              />
            </Field>
            <Field label="Username">
              <input
                className={inputClass}
                value={dbForm.username}
                onChange={(e) => onDbFormChange({ username: e.target.value })}
              />
            </Field>
            <Field label="Password" hint="Used once for the export; never stored.">
              <input
                type="password"
                className={inputClass}
                value={dbForm.password}
                onChange={(e) => onDbFormChange({ password: e.target.value })}
              />
            </Field>
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={dbForm.createTableIfMissing}
              onChange={(e) => onDbFormChange({ createTableIfMissing: e.target.checked })}
            />
            Create the table if it doesn&apos;t exist
          </label>
          <Button type="submit" disabled={Boolean(busyAction)}>
            {busyAction === 'db-export' ? 'Exporting…' : 'Run database export'}
          </Button>
        </form>
      )}
    </Card>
  );
}

export default function IngestionJobPage() {
  const { id } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const toast = useToast();
  const [job, setJob] = useState(null);
  const [templateFields, setTemplateFields] = useState([]);
  const [error, setError] = useState('');
  const [busyAction, setBusyAction] = useState('');
  const [mappingDraft, setMappingDraft] = useState(null);
  // Sparse draft of directly-edited cells: { "<absRowIdx>-<col>": value }.
  // Keyed by absolute row index so edits survive pagination and step flips.
  const [rowEdits, setRowEdits] = useState(null);
  const [chatMessages, setChatMessages] = useState([]);
  const [chatOpen, setChatOpen] = useState(false);
  const [exportFormat, setExportFormat] = useState('csv');
  const [showDbExport, setShowDbExport] = useState(false);
  const [dbForm, setDbForm] = useState({
    jdbcUrl: '',
    username: '',
    password: '',
    tableName: '',
    createTableIfMissing: true,
  });

  const activeStep = useMemo(() => {
    const requested = searchParams.get('step');
    if (requested && WIZARD_STEPS.includes(requested)) return requested;
    return job ? restoredWizardStep(job) : 'mapping';
  }, [searchParams, job]);

  const applyJob = useCallback((updated) => {
    setJob(updated);
    setMappingDraft(null);
    setRowEdits(null);
  }, []);

  useEffect(() => {
    getJob(id)
      .then((loaded) => {
        setJob(loaded);
        return getAllFields().then((fields) =>
          setTemplateFields(fields.filter((f) => f.templateId === loaded.templateId))
        );
      })
      .catch((err) => setError(err.message));
  }, [id]);

  const clampedJobId = useRef(null);
  useEffect(() => {
    if (!job || clampedJobId.current === id) return;
    clampedJobId.current = id;
    const requested = searchParams.get('step');
    if (requested && WIZARD_STEPS.includes(requested) && !canVisitStep(requested, job)) {
      setSearchParams({ step: restoredWizardStep(job) });
    }
    // Only clamp invalid deep-links when the job payload first loads — job
    // updates mid-session (e.g. a chat edit resetting currentStep) must not
    // yank the user off the step they are working on.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [job, id]);

  const setStep = (step, jobSnapshot = job) => {
    if (canVisitStep(step, jobSnapshot)) {
      setSearchParams({ step });
      // Best-effort checkpoint so reopening the job resumes on this step.
      // The response is ignored: applying it would clobber local drafts.
      setWizardStep(id, step).catch(() => {});
    }
  };

  const runAction = async (name, fn) => {
    setBusyAction(name);
    try {
      const result = await toast.promise(fn, ACTION_MESSAGES[name] || { loading: 'Working…' });
      applyJob(result);
      return result;
    } catch {
      // The error toast is already shown.
      return null;
    } finally {
      setBusyAction('');
    }
  };

  const rowsDirty = Boolean(rowEdits && Object.keys(rowEdits).length > 0);

  const editRowCell = (rowIdx, col, value) => {
    setRowEdits((prev) => {
      const next = { ...(prev || {}) };
      const key = `${rowIdx}-${col}`;
      if (value === String(job?.mappedRows?.rows?.[rowIdx]?.[col] ?? '')) {
        delete next[key]; // reverting to the original un-dirties the cell
      } else {
        next[key] = value;
      }
      return Object.keys(next).length ? next : null;
    });
  };

  const saveRowEdits = async () => {
    const { columns, rows } = job.mappedRows;
    const editedRows = rows.map((row, i) => {
      const out = { ...row };
      for (const col of columns) {
        const v = rowEdits?.[`${i}-${col}`];
        if (v !== undefined) out[col] = v;
      }
      return out;
    });
    // If revalidate fails mid-chain the draft stays dirty and saving again
    // simply re-PUTs the same rows — self-healing, no special handling.
    const updated = await runAction('rows', async () => {
      await updateRows(id, editedRows);
      return revalidateJob(id);
    });
    if (updated) {
      // updateRows clamps the persisted wizard step to MAPPING server-side;
      // restore the resume checkpoint now that the job is validated again.
      setWizardStep(id, 'validation').catch(() => {});
    }
  };

  const mapping = mappingDraft ?? job?.mapping ?? {};
  const validationErrors = (job?.validationResults || []).filter((r) => r.errorMessage);
  const isTerminal = job?.status === 'CANCELLED';
  const canExport = job?.currentStep === 'VALIDATED' || job?.currentStep === 'EXPORTED';

  if (!job) return error ? <ErrorBanner message={error} /> : <Spinner label="Loading job…" />;

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Upload & Process', to: '/ingestion' }, { label: `Job #${id}` }]}
        title={`Job #${id} — ${job.fileName || ''}`}
        subtitle="Mapping → validation → export"
        actions={
          <>
            <Button
              variant="secondary"
              onClick={() => runAction('resume', () => resumeJob(id, 'MAPPED'))}
              disabled={Boolean(busyAction) || isTerminal || activeStep !== 'mapping'}
            >
              {busyAction === 'resume' ? 'Resuming…' : 'Resume parse/map'}
            </Button>
            <Button
              variant="secondary"
              onClick={() => runAction('remap', () => remapJob(id))}
              disabled={Boolean(busyAction) || isTerminal || activeStep !== 'mapping'}
            >
              {busyAction === 'remap' ? 'Remapping…' : 'Re-map'}
            </Button>
            <Button
              variant="secondary"
              onClick={() => runAction('revalidate', () => revalidateJob(id))}
              disabled={Boolean(busyAction) || isTerminal || activeStep !== 'validation' || rowsDirty}
            >
              {busyAction === 'revalidate' ? 'Validating…' : 'Re-validate'}
            </Button>
            <Button
              variant="danger"
              onClick={() => runAction('cancel', () => cancelJob(id))}
              disabled={Boolean(busyAction) || isTerminal}
            >
              Cancel
            </Button>
          </>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      <div className="mb-5 flex flex-wrap items-center gap-4">
        <WizardStepIndicator activeStep={activeStep} job={job} onSelect={setStep} />
        <Badge status={job.status} />
      </div>

      {job.errorMessage && (
        <div className="mb-5">
          <Callout variant="error" title="Pipeline error">
            {job.errorMessage} — fix the cause and use Resume or Re-map to continue.
          </Callout>
        </div>
      )}

      {activeStep === 'mapping' && (
        <MappingStep
          job={job}
          mapping={mapping}
          mappingDraft={mappingDraft}
          templateFields={templateFields}
          busyAction={busyAction}
          onMappingChange={(source, target) =>
            setMappingDraft({ ...mapping, [source]: target })
          }
          onSaveMapping={() => runAction('mapping', () => updateMapping(id, mappingDraft))}
          onDiscardMapping={() => setMappingDraft(null)}
          onRemap={() => runAction('remap', () => remapJob(id))}
          onResume={() => runAction('resume', () => resumeJob(id, 'MAPPED'))}
          onContinue={async () => {
            const updated = await runAction('validate', () => resumeJob(id, 'VALIDATED'));
            if (updated) setStep('validation', updated);
          }}
        />
      )}

      {activeStep === 'validation' && (
        <ValidationStep
          job={job}
          hasValidationResults={Boolean(job.validationResults)}
          validationErrors={validationErrors}
          chatMessages={chatMessages}
          chatOpen={chatOpen}
          onChatOpenChange={setChatOpen}
          onChatSend={async (instruction) => {
            const userMsg = {
              id: `user-${Date.now()}`,
              role: 'user',
              text: instruction,
              ts: Date.now(),
            };
            setChatMessages((msgs) => [...msgs, userMsg]);
            setBusyAction('chat-edit');
            try {
              const result = await toast.promise(
                chatEdit(id, instruction),
                ACTION_MESSAGES['chat-edit']
              );
              applyJob(result);
              setChatMessages((msgs) => [
                ...msgs,
                {
                  id: `assistant-${Date.now()}`,
                  role: 'assistant',
                  text: 'Applied your changes. Click Re-validate to refresh validation errors.',
                  ts: Date.now(),
                },
              ]);
            } catch (err) {
              const errText = err?.message || 'Something went wrong applying that edit.';
              setChatMessages((msgs) => [
                ...msgs,
                {
                  id: `assistant-${Date.now()}`,
                  role: 'assistant',
                  text: errText,
                  ts: Date.now(),
                },
              ]);
            } finally {
              setBusyAction('');
            }
          }}
          busyAction={busyAction}
          onRevalidate={() => runAction('revalidate', () => revalidateJob(id))}
          onContinue={() => setStep('export')}
          canExport={canExport}
          rowEdits={rowEdits}
          onEditCell={editRowCell}
          rowsDirty={rowsDirty}
          onSaveRows={saveRowEdits}
          onDiscardRows={() => setRowEdits(null)}
        />
      )}

      {activeStep === 'export' && (
        <ExportStep
          canExport={canExport}
          exportFormat={exportFormat}
          showDbExport={showDbExport}
          dbForm={dbForm}
          busyAction={busyAction}
          exportInfo={job.exportInfo}
          onFormatChange={setExportFormat}
          onDownload={async () => {
            setBusyAction('download');
            try {
              await toast.promise(
                downloadFile(exportFilePath(id, exportFormat), `job-${id}.${exportFormat}`),
                { loading: 'Preparing download…', success: 'File downloaded' }
              );
            } catch {
              // The error toast is already shown.
            } finally {
              setBusyAction('');
            }
          }}
          onToggleDbExport={() => setShowDbExport((v) => !v)}
          onDbFormChange={(patch) => setDbForm((f) => ({ ...f, ...patch }))}
          onDbExport={(e) => {
            e.preventDefault();
            runAction('db-export', () => exportToDatabase(id, dbForm));
          }}
        />
      )}
    </div>
  );
}
