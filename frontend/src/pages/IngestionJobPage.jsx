import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { downloadFile } from '../api/client';
import { getAllFields } from '../api/templates';
import {
  cancelJob,
  chatEdit,
  exportFilePath,
  exportToDatabase,
  getJob,
  remapJob,
  resumeJob,
  revalidateJob,
  updateMapping,
} from '../api/ingestion';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Badge, Button, Callout, Card, ErrorBanner, Field, inputClass, Spinner } from '../components/ui';
import { useToast } from '../context/ToastContext';

const STEPS = ['UPLOADED', 'PARSED', 'MAPPED', 'VALIDATED', 'EXPORTED'];

const ACTION_MESSAGES = {
  resume: { loading: 'Resuming pipeline…', success: 'Pipeline resumed' },
  remap: { loading: 'Re-mapping columns…', success: 'Columns re-mapped' },
  revalidate: { loading: 'Re-validating rows…', success: 'Rows re-validated' },
  cancel: { loading: 'Cancelling job…', success: 'Job cancelled' },
  mapping: { loading: 'Saving mapping…', success: 'Mapping saved' },
  'chat-edit': { loading: 'Applying chat edit…', success: 'Chat edit applied' },
  'db-export': { loading: 'Exporting to database…', success: 'Exported to database' },
};

function StepIndicator({ currentStep }) {
  const currentIndex = STEPS.indexOf(currentStep);
  return (
    <ol className="flex flex-wrap items-center gap-1 text-xs">
      {STEPS.map((step, i) => (
        <li key={step} className="flex items-center gap-1">
          {i > 0 && <span className="text-line">──</span>}
          <span
            className={`rounded-full px-2.5 py-1 font-medium ${
              i < currentIndex
                ? 'bg-success-bg text-success-ink'
                : i === currentIndex
                  ? 'bg-accent text-white'
                  : 'bg-neutral-100 text-muted'
            }`}
          >
            {step}
          </span>
        </li>
      ))}
    </ol>
  );
}

function TablePreview({ table, maxRows = 15 }) {
  if (!table || !table.columns?.length) return <p className="text-sm text-muted">No table data.</p>;
  const rows = table.rows.slice(0, maxRows);
  return (
    <>
      <DataTable
        columns={table.columns.map((col) => ({ key: col, label: col, render: (row) => String(row[col] ?? '') }))}
        rows={rows}
      />
      {table.rows.length > maxRows && (
        <p className="mt-2 text-xs text-muted">
          Showing {maxRows} of {table.rows.length} rows.
        </p>
      )}
    </>
  );
}

export default function IngestionJobPage() {
  const { id } = useParams();
  const toast = useToast();
  const [job, setJob] = useState(null);
  const [templateFields, setTemplateFields] = useState([]);
  const [error, setError] = useState('');
  const [busyAction, setBusyAction] = useState('');
  const [mappingDraft, setMappingDraft] = useState(null);
  const [instruction, setInstruction] = useState('');
  const [exportFormat, setExportFormat] = useState('csv');
  const [showDbExport, setShowDbExport] = useState(false);
  const [dbForm, setDbForm] = useState({
    jdbcUrl: '',
    username: '',
    password: '',
    tableName: '',
    createTableIfMissing: true,
  });

  const applyJob = useCallback((updated) => {
    setJob(updated);
    setMappingDraft(null);
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

  const runAction = async (name, fn) => {
    setBusyAction(name);
    try {
      applyJob(await toast.promise(fn, ACTION_MESSAGES[name] || { loading: 'Working…' }));
    } catch {
      // The error toast is already shown.
    } finally {
      setBusyAction('');
    }
  };

  const saveMapping = () => runAction('mapping', () => updateMapping(id, mappingDraft));

  const sendChatEdit = async (e) => {
    e.preventDefault();
    if (!instruction.trim()) return;
    await runAction('chat-edit', () => chatEdit(id, instruction.trim()));
    setInstruction('');
  };

  const download = async () => {
    setBusyAction('download');
    try {
      await toast.promise(downloadFile(exportFilePath(id, exportFormat), `job-${id}.${exportFormat}`), {
        loading: 'Preparing download…',
        success: 'File downloaded',
      });
    } catch {
      // The error toast is already shown.
    } finally {
      setBusyAction('');
    }
  };

  const runDbExport = (e) => {
    e.preventDefault();
    runAction('db-export', () => exportToDatabase(id, dbForm));
  };

  if (!job) return error ? <ErrorBanner message={error} /> : <Spinner label="Loading job…" />;

  const mapping = mappingDraft ?? job.mapping ?? {};
  const validationErrors = (job.validationResults || []).filter((r) => r.errorMessage);
  const isTerminal = job.status === 'CANCELLED';
  const canExport = job.currentStep === 'VALIDATED' || job.currentStep === 'EXPORTED';

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Docs' }, { label: 'File Uploads', to: '/ingestion' }, { label: `Job #${id}` }]}
        title={`Job #${id} — ${job.fileName || ''}`}
        subtitle="Parse → map → validate → save"
        actions={
          <>
            <Button
              variant="secondary"
              onClick={() => runAction('resume', () => resumeJob(id))}
              disabled={Boolean(busyAction) || isTerminal || job.status === 'COMPLETED'}
            >
              {busyAction === 'resume' ? 'Resuming…' : 'Resume'}
            </Button>
            <Button
              variant="secondary"
              onClick={() => runAction('remap', () => remapJob(id))}
              disabled={Boolean(busyAction) || isTerminal}
            >
              {busyAction === 'remap' ? 'Remapping…' : 'Re-map'}
            </Button>
            <Button
              variant="secondary"
              onClick={() => runAction('revalidate', () => revalidateJob(id))}
              disabled={Boolean(busyAction) || isTerminal}
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
        <StepIndicator currentStep={job.currentStep} />
        <Badge status={job.status} />
      </div>

      {job.errorMessage && (
        <div className="mb-5">
          <Callout variant="error" title="Pipeline error">
            {job.errorMessage} — fix the cause and press Resume to continue from the last good step.
          </Callout>
        </div>
      )}

      {job.exportInfo && Object.keys(job.exportInfo).length > 0 && (
        <div className="mb-5">
          <Callout variant="success" title="Exported">
            <code className="text-xs">{JSON.stringify(job.exportInfo)}</code>
          </Callout>
        </div>
      )}

      {job.parsedTable && (
        <Card className="mb-5">
          <h2 className="mb-3 text-lg font-normal">1. Parsed table</h2>
          <TablePreview table={job.parsedTable} />
        </Card>
      )}

      {job.mapping && (
        <Card className="mb-5">
          <h2 className="mb-1 text-lg font-normal">2. Column mapping</h2>
          <p className="mb-3 text-sm text-muted">
            Source column → template field. Change any mapping and save; validation is cleared and
            must be re-run.
          </p>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            {Object.entries(mapping).map(([source, target]) => (
              <div key={source} className="flex items-center gap-2 text-sm">
                <span className="w-1/2 truncate font-mono text-xs" title={source}>
                  {source}
                </span>
                <span className="text-muted">→</span>
                <select
                  className={`${inputClass} flex-1`}
                  value={target ?? ''}
                  onChange={(e) =>
                    setMappingDraft({ ...mapping, [source]: e.target.value || null })
                  }
                >
                  <option value="">(unmapped)</option>
                  {templateFields.map((f) => (
                    <option key={f.templateFieldsId} value={f.fieldName}>
                      {f.fieldName}
                    </option>
                  ))}
                </select>
              </div>
            ))}
          </div>
          {mappingDraft && (
            <div className="mt-3 flex gap-2">
              <Button onClick={saveMapping} disabled={Boolean(busyAction)}>
                {busyAction === 'mapping' ? 'Saving…' : 'Save mapping'}
              </Button>
              <Button variant="secondary" onClick={() => setMappingDraft(null)}>
                Discard changes
              </Button>
            </div>
          )}
        </Card>
      )}

      {job.mappedRows && (
        <Card className="mb-5">
          <h2 className="mb-3 text-lg font-normal">3. Mapped rows</h2>
          <TablePreview table={job.mappedRows} />
          <form onSubmit={sendChatEdit} className="mt-4 flex gap-2">
            <input
              className={inputClass}
              placeholder='Edit with AI, e.g. "trim whitespace in all name columns"'
              value={instruction}
              onChange={(e) => setInstruction(e.target.value)}
            />
            <Button type="submit" variant="secondary" disabled={Boolean(busyAction)}>
              {busyAction === 'chat-edit' ? 'Applying…' : 'Apply'}
            </Button>
          </form>
        </Card>
      )}

      {job.validationResults && (
        <Card className="mb-5">
          <h2 className="mb-3 text-lg font-normal">4. Validation</h2>
          {validationErrors.length === 0 ? (
            <Callout variant="success" title="All rows valid">
              Every mapped row passed the template's validation rules.
            </Callout>
          ) : (
            <>
              <div className="mb-3">
                <Callout variant="error" title={`${validationErrors.length} validation error${validationErrors.length > 1 ? 's' : ''}`}>
                  Fix the data (AI edit above or re-map) and re-validate.
                </Callout>
              </div>
              <DataTable
                columns={[
                  { key: 'row', label: 'Row' },
                  { key: 'fieldName', label: 'Field' },
                  { key: 'rawValue', label: 'Value' },
                  { key: 'errorMessage', label: 'Error' },
                ]}
                rows={validationErrors.slice(0, 50)}
              />
              {validationErrors.length > 50 && (
                <p className="mt-2 text-xs text-muted">Showing 50 of {validationErrors.length} errors.</p>
              )}
            </>
          )}
        </Card>
      )}

      <Card>
        <h2 className="mb-1 text-lg font-normal">5. Save / export</h2>
        {!canExport && (
          <p className="mb-3 text-sm text-muted">
            Available once the job reaches the VALIDATED step.
          </p>
        )}
        <div className="flex flex-wrap items-center gap-2">
          <select
            className={`${inputClass} w-28`}
            value={exportFormat}
            onChange={(e) => setExportFormat(e.target.value)}
            disabled={!canExport}
          >
            <option value="csv">CSV</option>
            <option value="json">JSON</option>
            <option value="xlsx">XLSX</option>
          </select>
          <Button onClick={download} disabled={!canExport || Boolean(busyAction)}>
            {busyAction === 'download' ? 'Preparing…' : 'Download file'}
          </Button>
          <Button variant="secondary" onClick={() => setShowDbExport((v) => !v)} disabled={!canExport}>
            {showDbExport ? 'Hide database export' : 'Export to database…'}
          </Button>
        </div>

        {showDbExport && (
          <form onSubmit={runDbExport} className="mt-4 space-y-3 border-t border-line pt-4">
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Field label="JDBC URL">
                <input
                  required
                  className={inputClass}
                  placeholder="jdbc:sqlserver://host;databaseName=…"
                  value={dbForm.jdbcUrl}
                  onChange={(e) => setDbForm((f) => ({ ...f, jdbcUrl: e.target.value }))}
                />
              </Field>
              <Field label="Table name">
                <input
                  required
                  className={inputClass}
                  value={dbForm.tableName}
                  onChange={(e) => setDbForm((f) => ({ ...f, tableName: e.target.value }))}
                />
              </Field>
              <Field label="Username">
                <input
                  className={inputClass}
                  value={dbForm.username}
                  onChange={(e) => setDbForm((f) => ({ ...f, username: e.target.value }))}
                />
              </Field>
              <Field label="Password" hint="Used once for the export; never stored.">
                <input
                  type="password"
                  className={inputClass}
                  value={dbForm.password}
                  onChange={(e) => setDbForm((f) => ({ ...f, password: e.target.value }))}
                />
              </Field>
            </div>
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={dbForm.createTableIfMissing}
                onChange={(e) => setDbForm((f) => ({ ...f, createTableIfMissing: e.target.checked }))}
              />
              Create the table if it doesn't exist
            </label>
            <Button type="submit" disabled={Boolean(busyAction)}>
              {busyAction === 'db-export' ? 'Exporting…' : 'Run database export'}
            </Button>
          </form>
        )}
      </Card>
    </div>
  );
}
