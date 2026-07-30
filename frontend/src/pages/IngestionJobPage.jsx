import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
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
import useUnsavedGuard from '../hooks/useUnsavedGuard';

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

/**
 * Which wizard panes a job can show — answered by the server (`reachableSteps`
 * on the job payload), not re-derived here. A step you have not finished does
 * not open the next one: mapping is finished when every template field has a
 * source column, validation when it has run with no failing rows. Keeping the
 * rule in one place is deliberate; a local copy drifting from the backend's is
 * what once made the Export pill offer a step the API then refused.
 */
export function canVisitStep(step, job) {
  if (!job) return step === 'mapping';
  if (step === 'mapping') return true;
  return (job.reachableSteps || ['MAPPING']).includes(step.toUpperCase());
}

/** The sentence to show wherever a step is closed — pills, buttons, panes. */
export function stepBlockReason(step, job) {
  if (!job || canVisitStep(step, job)) return '';
  const unmapped = job.unmappedFields || [];
  if (unmapped.length > 0) {
    return `Map every template field first — ${unmapped.length} still to map: ${unmapped.join(', ')}.`;
  }
  if (!job.mappedRows?.columns?.length) {
    return 'Map at least one column to a template field first.';
  }
  const errors = job.validationErrorCount ?? 0;
  if (step === 'export' && errors > 0) {
    return errors === 1
      ? '1 row still fails validation. Fix it on the Validation step before exporting.'
      : `${errors} rows still fail validation. Fix them on the Validation step before exporting.`;
  }
  return 'Run validation on the mapped rows first.';
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
    <ol className="flex flex-wrap items-center gap-1 text-sm">
      {WIZARD_STEPS.map((step, i) => {
        // The step being worked on stays enabled even when an edit reset the
        // job's stage — otherwise a chat edit greys out the pane you are in.
        const enabled = step === activeStep || canVisitStep(step, job);
        const blockedReason = stepBlockReason(step, job);
        const isActive = step === activeStep;
        const isDone =
          (step === 'mapping' && job?.mappedRows) ||
          (step === 'validation' && job?.validationResults) ||
          (step === 'export' && job?.exportInfo && Object.keys(job.exportInfo).length > 0);
        return (
          <li key={step} className="flex items-center gap-1">
            {i > 0 && <span className="text-muted">›</span>}
            <button
              type="button"
              disabled={!enabled}
              onClick={() => enabled && onSelect(step)}
              // A locked pill that says nothing is just a dead end.
              title={blockedReason || undefined}
              className={`rounded-full px-3 py-1.5 font-medium ${
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

/**
 * Sticky wizard chrome: step pills, job status, and the contextual actions for
 * the active step only — always visible, so the fix-and-save loops never
 * require scrolling to reach a button.
 */
function WizardToolbar({
  job,
  activeStep,
  onSelectStep,
  busyAction,
  isTerminal,
  mappingDraft,
  onSaveMapping,
  onDiscardMapping,
  onRemap,
  onContinueValidation,
  rowsDirty,
  dirtyCount,
  onSaveRows,
  onDiscardRows,
  onRevalidate,
  onContinueExport,
  canExport,
  onCancel,
}) {
  const validationRun = job.currentStep === 'VALIDATED' || job.currentStep === 'EXPORTED';
  const unmappedCount = job.unmappedFields?.length ?? 0;
  const errorCount = job.validationErrorCount ?? 0;
  return (
    <div className="sticky top-14 z-30 -mx-4 mb-5 border-b border-line bg-white/95 px-4 py-2 backdrop-blur sm:-mx-6 sm:px-6 lg:-mx-8 lg:px-8">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <WizardStepIndicator activeStep={activeStep} job={job} onSelect={onSelectStep} />
        <Badge status={job.status} />
        <span
          className="hidden max-w-48 truncate text-xs text-muted lg:inline"
          title={job.fileName}
        >
          {job.fileName}
        </span>

        <div className="ml-auto flex flex-wrap items-center gap-2">
          {activeStep === 'mapping' && (
            <>
              {mappingDraft && (
                <>
                  <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
                    Unsaved mapping
                  </span>
                  <Button onClick={onSaveMapping} disabled={Boolean(busyAction)}>
                    {busyAction === 'mapping' ? 'Saving…' : 'Save mapping'}
                  </Button>
                  <Button variant="secondary" onClick={onDiscardMapping}>
                    Discard changes
                  </Button>
                </>
              )}
              <Button
                variant="secondary"
                onClick={onRemap}
                disabled={Boolean(busyAction) || isTerminal}
              >
                {busyAction === 'remap' ? 'Remapping…' : 'Re-map automatically'}
              </Button>
              {unmappedCount > 0 && (
                <span
                  className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800"
                  title={job.unmappedFields.join(', ')}
                >
                  {unmappedCount} field{unmappedCount === 1 ? '' : 's'} still to map
                </span>
              )}
              {validationRun && !mappingDraft && unmappedCount === 0 ? (
                <Button variant="secondary" onClick={() => onSelectStep('validation')}>
                  Go to validation
                </Button>
              ) : (
                <Button
                  onClick={onContinueValidation}
                  disabled={
                    Boolean(busyAction) ||
                    Boolean(mappingDraft) ||
                    isTerminal ||
                    unmappedCount > 0 ||
                    job.currentStep !== 'MAPPED'
                  }
                  title={
                    isTerminal
                      ? 'This job is cancelled'
                      : mappingDraft
                        ? 'Save or discard mapping changes first'
                        : unmappedCount > 0
                          ? `Every template field needs a source column — still to map: ${job.unmappedFields.join(', ')}`
                          : job.currentStep !== 'MAPPED'
                            ? 'Resume processing first'
                            : undefined
                  }
                >
                  {busyAction === 'validate' ? 'Validating…' : 'Continue to validation'}
                </Button>
              )}
            </>
          )}

          {activeStep === 'validation' && (
            <>
              {rowsDirty && (
                <>
                  <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
                    {dirtyCount} unsaved edit{dirtyCount === 1 ? '' : 's'}
                  </span>
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
                disabled={Boolean(busyAction) || rowsDirty || isTerminal}
              >
                {busyAction === 'revalidate' ? 'Validating…' : 'Re-validate'}
              </Button>
              {errorCount > 0 && (
                <span className="rounded-full bg-danger-bg px-2 py-0.5 text-xs font-medium text-danger-ink">
                  {errorCount} error{errorCount === 1 ? '' : 's'} to fix
                </span>
              )}
              <Button
                onClick={onContinueExport}
                disabled={!canExport || Boolean(busyAction) || rowsDirty || isTerminal}
                title={
                  isTerminal
                    ? 'This job is cancelled'
                    : rowsDirty
                      ? 'Save or discard your cell edits first'
                      : stepBlockReason('export', job) || undefined
                }
              >
                Continue to export
              </Button>
            </>
          )}

          {!isTerminal && (
            <button
              type="button"
              onClick={onCancel}
              disabled={Boolean(busyAction)}
              className="ml-1 cursor-pointer text-xs text-danger-ink underline-offset-2 hover:underline disabled:opacity-50"
            >
              Cancel job
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

function MappingPanel({ parsedTable, mapping, templateFields, onMappingChange }) {
  const [filter, setFilter] = useState('');
  const sourceColumns = parsedTable?.columns ?? [];
  const firstParsedRow = parsedTable?.rows?.[0] ?? {};

  if (!sourceColumns.length) {
    return <p className="text-sm text-muted">No source columns to map.</p>;
  }

  const mappedCount = sourceColumns.filter((c) => mapping[c]).length;
  const mappedTargets = new Set(Object.values(mapping).filter(Boolean));
  const unmappedFields = templateFields.filter((f) => !mappedTargets.has(f.fieldName));
  const visible = filter
    ? sourceColumns.filter((c) => c.toLowerCase().includes(filter.toLowerCase()))
    : sourceColumns;

  return (
    <div className="max-h-[55vh] overflow-y-auto rounded-md border border-line">
      <div className="sticky top-0 z-10 flex flex-wrap items-center gap-3 border-b border-line bg-white px-3 py-2">
        <h3 className="text-sm font-medium text-muted">Source columns</h3>
        <span className="rounded-full bg-neutral-100 px-2 py-0.5 text-xs text-muted">
          {mappedCount} of {sourceColumns.length} mapped
        </span>
        <input
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder="Filter columns…"
          aria-label="Filter columns"
          className={`${inputClass} ml-auto w-44`}
        />
      </div>
      {/* Not trivia: every one of these has to be mapped before the job can
          move on, so the row reads as the remaining work. */}
      {unmappedFields.length > 0 ? (
        <div className="flex flex-wrap items-center gap-1 border-b border-line bg-amber-50 px-3 py-1.5 text-xs">
          <span className="font-medium text-amber-800">
            Still to map ({unmappedFields.length}):
          </span>
          {unmappedFields.map((f) => (
            <span
              key={f.templateFieldsId}
              className="rounded-full bg-amber-100 px-2 py-0.5 font-medium text-amber-800"
            >
              {f.fieldName}
            </span>
          ))}
        </div>
      ) : (
        <div className="border-b border-line bg-success-bg px-3 py-1.5 text-xs font-medium text-success-ink">
          ✓ Every template field has a source column.
        </div>
      )}
      <ul className="divide-y divide-line/60">
        {visible.map((col) => {
          const preview = firstParsedRow[col];
          return (
            <li
              key={col}
              className="grid grid-cols-[minmax(0,1fr)_auto_minmax(11rem,14rem)_minmax(0,1.2fr)] items-center gap-3 px-3 py-1.5"
            >
              <span className="flex min-w-0 items-center gap-2">
                <span
                  aria-hidden
                  className={`size-2 shrink-0 rounded-full ${
                    mapping[col] ? 'bg-success-ink' : 'bg-neutral-300'
                  }`}
                />
                <span className="truncate font-mono text-xs" title={col}>
                  {col}
                </span>
              </span>
              <span aria-hidden className="text-muted">→</span>
              <select
                className={inputClass}
                value={mapping[col] ?? ''}
                aria-label={`Map ${col}`}
                onChange={(e) => onMappingChange(col, e.target.value || null)}
              >
                <option value="">(unmapped)</option>
                {templateFields.map((f) => (
                  <option key={f.templateFieldsId} value={f.fieldName}>
                    {f.fieldName}
                  </option>
                ))}
              </select>
              <span
                className="hidden min-w-0 truncate text-xs text-muted md:inline"
                title={preview != null ? String(preview) : undefined}
              >
                {preview != null && preview !== '' ? String(preview) : ''}
              </span>
            </li>
          );
        })}
        {visible.length === 0 && (
          <li className="px-3 py-3 text-sm text-muted">No columns match "{filter}".</li>
        )}
      </ul>
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

// Compact single-line error marker: the value keeps its row at normal height;
// the full message lives in the tooltip and the ErrorInspector strip above the
// table, so it stays readable even while the cell is being edited.
function ValidationErrorCell({ err, value }) {
  const displayValue = value === '' || value == null ? '(empty)' : String(value);
  return (
    <span
      className="inline-flex max-w-full items-center gap-1 rounded bg-danger-bg px-1 font-mono text-xs text-danger-ink"
      title={`Fix: ${err.errorMessage}${
        err.ruleType ? ` · Rule: ${validationErrorRuleLabel(err.ruleType)}` : ''
      }`}
    >
      <span aria-hidden className="text-[10px]">⚠</span>
      <span className="truncate underline decoration-danger-ink/70 decoration-wavy">
        {displayValue}
      </span>
    </span>
  );
}

// Fixed-height one-liner keeping the active error's message visible while the
// cell editor is open (the compact cells only carry a tooltip).
function ErrorInspector({ activeErr, pageFirstErr }) {
  const err = activeErr ?? pageFirstErr;
  return (
    <div className="mb-2 flex min-h-7 items-center gap-2 overflow-hidden rounded border border-danger-ink/30 bg-danger-bg/60 px-2 py-1 text-xs">
      {err ? (
        <>
          <span className="shrink-0 font-medium text-danger-ink">
            Row {err.row} · {err.fieldName}
          </span>
          <span className="truncate text-danger-ink" title={err.errorMessage}>
            Fix: {err.errorMessage}
          </span>
          {err.ruleType && (
            <span className="shrink-0 text-muted">
              Rule: {validationErrorRuleLabel(err.ruleType)}
            </span>
          )}
        </>
      ) : (
        <span className="text-muted">No errors on this page — use Next error.</span>
      )}
    </div>
  );
}

function EditableCell({
  value,
  err,
  dirty,
  editing,
  column,
  rowNumber,
  onStartEdit,
  onCommit,
  onCancel,
}) {
  // Keyboard paths mark the commit handled so the fallback onBlur (fired by
  // the focus move that follows) can't commit a second time.
  const handledRef = useRef(false);
  const buttonRef = useRef(null);
  const wasEditing = useRef(false);
  useEffect(() => {
    if (editing) handledRef.current = false;
  }, [editing]);
  useEffect(() => {
    // Committing used to leave focus on the removed input, i.e. on <body>:
    // the keyboard user lost their place in the table entirely. Only reclaim it
    // when it actually fell through — Enter and Tab hand it to the next cell.
    const lost = !document.activeElement || document.activeElement === document.body;
    if (wasEditing.current && !editing && lost) buttonRef.current?.focus();
    wasEditing.current = editing;
  }, [editing]);

  if (editing) {
    return (
      <input
        autoFocus
        defaultValue={value ?? ''}
        className={`${inputClass} w-full min-w-28`}
        onBlur={(e) => {
          if (handledRef.current) return;
          onCommit(e.target.value, null);
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            handledRef.current = true;
            onCommit(e.currentTarget.value, 'nextError');
          } else if (e.key === 'Tab') {
            e.preventDefault();
            handledRef.current = true;
            onCommit(e.currentTarget.value, e.shiftKey ? 'left' : 'right');
          } else if (e.key === 'Escape') {
            handledRef.current = true;
            onCancel();
          }
        }}
      />
    );
  }
  const valueText = value === '' || value == null ? 'empty' : String(value);
  const problem = err?.errorMessage ? ` ${err.errorMessage}` : '';
  const description = `${column}, row ${rowNumber}: ${valueText}.${problem} Edit`;
  return (
    <div
      ref={buttonRef}
      role="button"
      tabIndex={0}
      // Announce which cell this is and what is wrong with it — "Click to edit"
      // told a screen-reader user nothing about where they were.
      aria-label={description}
      title="Click to edit"
      onClick={onStartEdit}
      onKeyDown={(e) => {
        // A role="button" must answer Space as well as Enter.
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onStartEdit();
        }
      }}
      className={`min-h-5 min-w-28 cursor-text rounded ${dirty ? 'bg-pill/60 px-1 outline outline-1 outline-accent' : ''}`}
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
  const [wrapHint, setWrapHint] = useState(false);

  // Errored cells in document order (row asc, then column order) for the
  // Enter-to-next-error traversal.
  const errorCells = useMemo(() => {
    const columns = mappedRows?.columns ?? [];
    const colIdx = (c) => columns.indexOf(c);
    return (validationResults || [])
      .filter((r) => r.errorMessage)
      .sort((a, b) => a.row - b.row || colIdx(a.fieldName) - colIdx(b.fieldName))
      .map((r) => ({ rowIdx: r.row - 1, col: r.fieldName }));
  }, [validationResults, mappedRows]);

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

  const changePage = (nextPage) => {
    setWrapHint(false);
    onPageChange(nextPage);
  };

  const jumpToNextError = () => {
    const next = errorRows.find((row) => row > pageEndRow);
    const target = next ?? errorRows[0];
    if (target == null) return;
    setWrapHint(next == null);
    onPageChange(pageForRow(target, pageSize));
  };

  // Commit the edited value, then move the editor: Enter → next errored cell
  // (wrapping, jumping pages), Tab/Shift+Tab → adjacent column, blur → close.
  const commitAndMove = (from, value, dir) => {
    onEditCell(from.rowIdx, from.col, value);
    let next = null;
    if (dir === 'nextError' && errorCells.length) {
      const columns = mappedRows.columns;
      const colIdx = (c) => columns.indexOf(c);
      const i = errorCells.findIndex(
        (c) => c.rowIdx > from.rowIdx || (c.rowIdx === from.rowIdx && colIdx(c.col) > colIdx(from.col))
      );
      next = errorCells[i === -1 ? 0 : i];
      if (next.rowIdx === from.rowIdx && next.col === from.col) next = null; // lone error: close
    } else if (dir === 'right' || dir === 'left') {
      const columns = mappedRows.columns;
      const j = columns.indexOf(from.col) + (dir === 'right' ? 1 : -1);
      if (j >= 0 && j < columns.length) next = { rowIdx: from.rowIdx, col: columns[j] };
    }
    if (next) {
      const targetPage = pageForRow(next.rowIdx + 1, pageSize);
      if (targetPage !== safePage) changePage(targetPage);
      setEditingCell(next);
    } else {
      setEditingCell(null);
    }
  };

  const activeErr = editingCell
    ? errorMap.get(`${editingCell.rowIdx + 1}-${editingCell.col}`)
    : null;
  let pageFirstErr = null;
  for (let rowNum = start + 1; rowNum <= end && !pageFirstErr; rowNum += 1) {
    for (const col of mappedRows.columns) {
      const found = errorMap.get(`${rowNum}-${col}`);
      if (found) {
        pageFirstErr = found;
        break;
      }
    }
  }

  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center gap-3 text-sm">
        <label className="flex items-center gap-2">
          <span className="text-muted">Rows per page</span>
          <select
            className={`${inputClass} w-20`}
            value={pageSize}
            onChange={(e) => {
              setWrapHint(false);
              onPageSizeChange(Number(e.target.value));
            }}
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
            onClick={() => changePage(Math.max(0, safePage - 1))}
            disabled={safePage === 0}
          >
            Previous
          </Button>
          <Button
            variant="secondary"
            onClick={() => changePage(Math.min(totalPages - 1, safePage + 1))}
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
        {wrapHint && (
          <span aria-live="polite" className="text-xs text-muted">
            Wrapped to first error
          </span>
        )}
      </div>

      {errorRows.length > 0 && <ErrorInspector activeErr={activeErr} pageFirstErr={pageFirstErr} />}

      <DataTable
        maxHeight="min(55vh, 640px)"
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
                  column={col}
                  rowNumber={rowIdx + 1}
                  onStartEdit={() => setEditingCell({ rowIdx, col })}
                  onCommit={(v, dir) => commitAndMove({ rowIdx, col }, v, dir)}
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

function MappingStep({ job, mapping, templateFields, busyAction, onMappingChange, onResume }) {
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
        <MappingPanel
          parsedTable={job.parsedTable}
          mapping={mapping}
          templateFields={templateFields}
          onMappingChange={onMappingChange}
        />
      )}

      {!job.parsedTable && job.mapping && (
        <p className="text-sm text-muted">Parsed columns are not available for this job.</p>
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
  onRunValidation,
  rowEdits,
  onEditCell,
  rowsDirty,
}) {
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(10);

  const handlePageSizeChange = (size) => {
    setPageSize(size);
    setPage(0);
  };

  // Keep the page in range when the page size or the row set changes.
  useEffect(() => {
    const totalPages = Math.max(1, Math.ceil((job.mappedRows?.rows?.length ?? 0) / pageSize));
    setPage((p) => Math.min(p, totalPages - 1));
  }, [pageSize, job.mappedRows]);

  return (
    <>
      <Card>
        <h2 className="mb-1 text-lg font-normal">Validation</h2>
        <p className="mb-4 text-sm text-muted">
          Rows are checked against your template rules. Edit cells directly or fix issues with the
          AI assistant, then re-validate.
        </p>

        {/* The table stays on screen whether or not validation has run: after an
            edit (yours or the assistant's) the results are cleared, and hiding
            the data at that exact moment is how the fix-then-check loop used to
            dead-end. */}
        {!hasValidationResults ? (
          <div className="mb-3">
            <Callout variant="info" title="Validation has not run on these rows">
              Check the mapped rows against your template rules to see what still needs fixing.
              <div className="mt-2">
                <Button onClick={onRunValidation} disabled={Boolean(busyAction) || rowsDirty}>
                  {busyAction === 'validate' ? 'Validating…' : 'Run validation'}
                </Button>
              </div>
            </Callout>
          </div>
        ) : !job.mappedRows?.columns?.length ? (
          <div className="mb-3">
            <Callout variant="info" title="No mapped data to check">
              Nothing was mapped to a template field, so there is nothing to validate. Go back to
              Mapping and point at least one source column at a field.
            </Callout>
          </div>
        ) : validationErrors.length === 0 ? (
          <div className="mb-3">
            <Callout variant="success" title="All rows valid">
              Every mapped row passed the template&apos;s validation rules.
            </Callout>
          </div>
        ) : (
          <div className="mb-3">
            <Callout
              variant="error"
              title={`${validationErrors.length} validation error${validationErrors.length > 1 ? 's' : ''}`}
            >
              Fix the highlighted cells below or use the AI assistant, then re-validate.
              Export stays locked until every row passes.
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

      </Card>

      <ValidationChatDrawer
        open={chatOpen}
        onOpenChange={onChatOpenChange}
        messages={chatMessages}
        onSend={onChatSend}
        busy={busyAction === 'chat-edit'}
        disabledNote={
          rowsDirty ? 'Save or discard your table edits to use the assistant.' : ''
        }
        showQuickPrompts={validationErrors.length > 0}
      />
    </>
  );
}

function ExportStep({
  canExport,
  blockedReason,
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
  onGoToBlockingStep,
  blockingStepLabel,
}) {
  return (
    <Card>
      <h2 className="mb-1 text-lg font-normal">Export</h2>
      {!canExport ? (
        // Naming the blocker and offering the step that owns it beats a generic
        // "complete validation" the user cannot act on.
        <div className="mb-3">
          <Callout variant="error" title="Export is locked">
            {blockedReason || 'This job is not ready to export.'}
            {onGoToBlockingStep && (
              <div className="mt-2">
                <Button onClick={onGoToBlockingStep}>Go to {blockingStepLabel}</Button>
              </div>
            )}
          </Callout>
        </div>
      ) : (
        <p className="mb-3 text-sm text-muted">
          Download the validated data as a file or push it to an external database.
        </p>
      )}

      {exportInfo && Object.keys(exportInfo).length > 0 && (
        <div className="mb-4">
          <Callout variant="success" title="Export complete">
            <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-0.5 text-sm">
              <dt className="text-muted">Type</dt>
              <dd>{exportInfo.type === 'database' ? 'Database' : 'File download'}</dd>
              {exportInfo.fileName && (
                <>
                  <dt className="text-muted">File</dt>
                  <dd className="truncate font-mono text-xs" title={exportInfo.fileName}>
                    {exportInfo.fileName}
                  </dd>
                </>
              )}
              {exportInfo.format && (
                <>
                  <dt className="text-muted">Format</dt>
                  <dd>{exportInfo.format}</dd>
                </>
              )}
              {exportInfo.target && (
                <>
                  <dt className="text-muted">Target</dt>
                  <dd className="truncate font-mono text-xs" title={exportInfo.target}>
                    {exportInfo.target}
                  </dd>
                </>
              )}
              {exportInfo.tableName && (
                <>
                  <dt className="text-muted">Table</dt>
                  <dd>{exportInfo.tableName}</dd>
                </>
              )}
              {exportInfo.rowCount != null && (
                <>
                  <dt className="text-muted">Rows</dt>
                  <dd>{exportInfo.rowCount}</dd>
                </>
              )}
            </dl>
            <div className="mt-3">
              <Link to="/ingestion" className="text-sm font-medium text-accent hover:underline">
                Process another file →
              </Link>
            </div>
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
          <option value="tsv">TSV</option>
          <option value="xml">XML</option>
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
  // Date.now() collides when a reply lands in the same millisecond as the
  // question, and React then reuses the wrong bubble.
  const nextChatId = useRef(1);
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

  const [reloadKey, setReloadKey] = useState(0);
  useEffect(() => {
    let active = true;
    // Clear the previous job before fetching: rendering A's table under B's id
    // showed the wrong data and let A's wizard step be checkpointed onto B.
    setJob(null);
    setMappingDraft(null);
    setRowEdits(null);
    setChatMessages([]);
    getJob(id)
      .then((loaded) => {
        if (!active) return null;
        setJob(loaded);
        setError('');
        // Field names drive every mapping select, so their absence is worth
        // saying out loud rather than silently emptying the dropdowns.
        return getAllFields(loaded.templateId)
          .then((fields) => {
            if (active) setTemplateFields(fields);
          })
          .catch((err) =>
            setError(`Template fields could not be loaded: ${err.message}`)
          );
      })
      .catch((err) => {
        if (active) setError(err.message);
      });
    return () => {
      active = false;
    };
  }, [id, reloadKey]);

  const lastStepWrite = useRef(0);
  const clampedJobId = useRef(null);
  useEffect(() => {
    if (!job || clampedJobId.current === id) return;
    clampedJobId.current = id;
    const requested = searchParams.get('step');
    const target =
      requested && WIZARD_STEPS.includes(requested) && canVisitStep(requested, job)
        ? requested
        : restoredWizardStep(job);
    // Write the resolved step into the URL once, when the job first loads:
    // from then on the URL is the only thing that decides which pane is shown,
    // so a save or a chat edit that resets the job's stage can no longer throw
    // the user back to Mapping mid-task. Invalid deep-links are clamped here
    // too, and only here — re-clamping on every job update would do the same
    // yanking in reverse.
    if (target !== requested) setSearchParams({ step: target }, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [job, id]);

  const setStep = (step, jobSnapshot = job) => {
    if (!canVisitStep(step, jobSnapshot)) return;
    setSearchParams({ step });
    // Checkpoint so reopening the job resumes on this step. The response is
    // ignored (applying it would clobber local drafts) but a failure is not:
    // silently losing the resume point is exactly the bug this replaced.
    // Only the newest request may report anything — two quick step changes can
    // otherwise land out of order and blame the wrong one.
    const seq = ++lastStepWrite.current;
    setWizardStep(id, step).catch((err) => {
      if (seq !== lastStepWrite.current) return;
      console.warn('Could not save the resume point for job', id, err);
      setError(`This step could not be saved as the resume point: ${err.message}`);
    });
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
      setStep('validation', updated);
    }
  };

  const handleRemap = () => {
    if (
      mappingDraft &&
      !window.confirm('Re-mapping will discard your unsaved mapping changes. Continue?')
    ) {
      return;
    }
    runAction('remap', () => remapJob(id));
  };

  const handleCancel = () => {
    if (!window.confirm('Cancel this job? This cannot be undone.')) return;
    runAction('cancel', () => cancelJob(id));
  };

  useUnsavedGuard(
    Boolean(mappingDraft) || rowsDirty,
    mappingDraft
      ? 'Your mapping changes have not been saved. Leave and discard them?'
      : 'Your cell edits have not been saved. Leave and discard them?'
  );

  const mapping = mappingDraft ?? job?.mapping ?? {};
  const validationErrors = (job?.validationResults || []).filter((r) => r.errorMessage);
  const isTerminal = job?.status === 'CANCELLED';
  // One expression for "may this job export": the same server answer that
  // decides the Export pill, minus cancelled jobs.
  const canExport = !isTerminal && canVisitStep('export', job);
  const dirtyCount = rowEdits ? Object.keys(rowEdits).length : 0;

  if (!job) {
    return (
      <div>
        <PageHeader
          crumbs={[{ label: 'Upload & Process', to: '/ingestion' }, { label: `Job #${id}` }]}
          title={`Job #${id}`}
        />
        {error ? (
          <Callout variant="error" title="This job could not be loaded">
            {error}
            <div className="mt-2 flex gap-2">
              <Button onClick={() => setReloadKey((k) => k + 1)}>Try again</Button>
              <Link
                to="/ingestion"
                className="inline-flex items-center rounded border border-line px-4 py-1.5 text-sm text-accent hover:bg-pill/50"
              >
                Back to uploads
              </Link>
            </div>
          </Callout>
        ) : (
          <>
            <Spinner label="Loading job…" />
            <Card>
              <div className="h-40 animate-pulse rounded bg-neutral-100" />
            </Card>
          </>
        )}
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Upload & Process', to: '/ingestion' }, { label: `Job #${id}` }]}
        title={`Job #${id}`}
        subtitle={job.fileName || ''}
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      <WizardToolbar
        job={job}
        activeStep={activeStep}
        onSelectStep={setStep}
        busyAction={busyAction}
        isTerminal={isTerminal}
        mappingDraft={mappingDraft}
        onSaveMapping={() => runAction('mapping', () => updateMapping(id, mappingDraft))}
        onDiscardMapping={() => setMappingDraft(null)}
        onRemap={handleRemap}
        onContinueValidation={async () => {
          const updated = await runAction('validate', () => resumeJob(id, 'VALIDATED'));
          if (updated) setStep('validation', updated);
        }}
        rowsDirty={rowsDirty}
        dirtyCount={dirtyCount}
        onSaveRows={saveRowEdits}
        onDiscardRows={() => setRowEdits(null)}
        onRevalidate={() => runAction('revalidate', () => revalidateJob(id))}
        onContinueExport={() => setStep('export')}
        canExport={canExport}
        onCancel={handleCancel}
      />

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
          templateFields={templateFields}
          busyAction={busyAction}
          onMappingChange={(source, target) => {
            const next = { ...mapping, [source]: target };
            const original = job.mapping || {};
            const columns = new Set([...Object.keys(next), ...Object.keys(original)]);
            const changed = [...columns].some(
              (col) => (next[col] ?? null) !== (original[col] ?? null)
            );
            // Undoing your own edits must clear "Unsaved mapping" — otherwise
            // Continue stays disabled with nothing left to save.
            setMappingDraft(changed ? next : null);
          }}
          onResume={() => runAction('resume', () => resumeJob(id, 'MAPPED'))}
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
              id: `user-${nextChatId.current++}`,
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
                  id: `assistant-${nextChatId.current++}`,
                  role: 'assistant',
                  text:
                    'Applied your changes. Use Re-validate in the toolbar above to check ' +
                    'the rows again.',
                  ts: Date.now(),
                },
              ]);
            } catch (err) {
              const errText = err?.message || 'Something went wrong applying that edit.';
              setChatMessages((msgs) => [
                ...msgs,
                {
                  id: `assistant-${nextChatId.current++}`,
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
          onRunValidation={async () => {
            const updated = await runAction('validate', () => resumeJob(id, 'VALIDATED'));
            if (updated) setStep('validation', updated);
          }}
          rowEdits={rowEdits}
          onEditCell={editRowCell}
          rowsDirty={rowsDirty}
        />
      )}

      {activeStep === 'export' && (
        <ExportStep
          canExport={canExport}
          blockedReason={
            isTerminal ? 'This job is cancelled.' : stepBlockReason('export', job)
          }
          blockingStepLabel={
            (job.unmappedFields?.length ?? 0) > 0 ? 'mapping' : 'validation'
          }
          onGoToBlockingStep={
            isTerminal
              ? undefined
              : () => setStep((job.unmappedFields?.length ?? 0) > 0 ? 'mapping' : 'validation')
          }
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
              // The server records the export; refresh so the summary shows.
              // setJob (not applyJob) so local drafts can never be clobbered.
              getJob(id).then(setJob).catch(() => {});
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
