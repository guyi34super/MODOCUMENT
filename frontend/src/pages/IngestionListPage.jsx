import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { getTemplates } from '../api/cache';
import { createJobFromFile, getSupportedTypes, listJobs } from '../api/ingestion';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Badge, Button, Card, ErrorBanner, Field, inputClass, Spinner } from '../components/ui';
import { useToast } from '../context/ToastContext';

export default function IngestionListPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [templates, setTemplates] = useState([]);
  const [jobs, setJobs] = useState(null);
  const [error, setError] = useState('');
  const [refreshing, setRefreshing] = useState(false);
  // Asked for once: the picker must offer exactly what this build can parse
  // (docling formats appear only when a docling endpoint is configured).
  const [supported, setSupported] = useState(null);

  const [file, setFile] = useState(null);
  const [templateId, setTemplateId] = useState('');
  const [autoRun, setAutoRun] = useState(true);
  const [uploading, setUploading] = useState(false);
  // null = "not decided by the user yet": auto-open only for first-time users.
  const [showUpload, setShowUpload] = useState(null);
  const uploadOpen = showUpload ?? (jobs !== null && jobs.length === 0);

  const load = useCallback(async () => {
    setRefreshing(true);
    try {
      const [tpl, jobList] = await Promise.all([getTemplates(), listJobs()]);
      setTemplates(tpl);
      setJobs(jobList.sort((a, b) => b.ingestionJobId - a.ingestionJobId));
      // A stale banner outliving the problem reads as a broken page.
      setError('');
    } catch (err) {
      setError(err.message);
    } finally {
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    let active = true;
    getSupportedTypes()
      .then((types) => {
        if (active) setSupported(types);
      })
      .catch(() => {
        /* the upload itself still validates the type */
      });
    return () => {
      active = false;
    };
  }, []);

  const upload = async (e) => {
    e.preventDefault();
    if (!file || !templateId) return;
    setUploading(true);
    try {
      const job = await toast.promise(
        () => createJobFromFile(file, Number(templateId), autoRun, 'MAPPED', autoRun),
        {
          loading: autoRun ? 'Processing document…' : 'Uploading file…',
          success: autoRun ? 'Processing complete' : 'File uploaded',
          error: (err) => err.message,
        }
      );
      navigate(`/ingestion/${job.ingestionJobId}?step=mapping`, { state: { job } });
    } catch {
      // The error toast is already shown.
    } finally {
      setUploading(false);
    }
  };

  return (
    <div>
      <PageHeader
        sticky
        crumbs={[{ label: 'Upload & Process' }]}
        title="Upload & Process"
        subtitle="Upload a document, review the extracted table, validate and export"
        actions={
          <>
            <Button variant="secondary" onClick={load} disabled={refreshing}>
              {refreshing ? 'Refreshing…' : 'Refresh'}
            </Button>
            <Button onClick={() => setShowUpload(!uploadOpen)}>
              {uploadOpen ? 'Close upload' : 'Upload a document'}
            </Button>
          </>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {uploadOpen && (
      <Card className="mb-6">
        <h2 className="mb-1 text-lg font-normal">Upload a document</h2>
        <p className="mb-4 text-sm text-muted">
          {supported?.description
            ? `Accepts ${supported.description}.`
            : 'Accepts spreadsheets, csv, pdf, Word documents and scanned images.'}
        </p>
        <form onSubmit={upload} className="space-y-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="File">
              <input
                type="file"
                required
                accept={supported?.accept || '.csv,.xlsx,.xls,.pdf,.docx,.doc,.png,.jpg,.jpeg,.tif,.tiff,.bmp'}
                className="block w-full text-sm text-muted file:mr-3 file:cursor-pointer file:rounded file:border-0 file:bg-pill file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-accent"
                onChange={(e) => setFile(e.target.files[0] || null)}
              />
            </Field>
            <Field label="Template">
              <select
                required
                className={inputClass}
                value={templateId}
                onChange={(e) => setTemplateId(e.target.value)}
              >
                <option value="">Select a template…</option>
                {templates.map((t) => (
                  <option key={t.templateId} value={t.templateId}>
                    {t.name}
                  </option>
                ))}
              </select>
              {templates.length === 0 && (
                <p className="mt-1 text-xs text-muted">
                  No templates yet —{' '}
                  <Link to="/templates/new" className="text-accent hover:underline">
                    design one first (step 1)
                  </Link>
                  .
                </p>
              )}
            </Field>
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={autoRun}
              onChange={(e) => setAutoRun(e.target.checked)}
            />
            Start processing right away (parse and map automatically)
          </label>
          <Button type="submit" disabled={uploading || !file || !templateId}>
            {uploading ? 'Uploading & processing…' : 'Upload & process'}
          </Button>
        </form>
      </Card>
      )}

      <h2 className="mb-3 text-base font-medium">Jobs</h2>
      {!jobs ? (
        <Spinner />
      ) : (
        <DataTable
          columns={[
            {
              key: 'ingestionJobId',
              label: 'ID',
              render: (row) => (
                <Link to={`/ingestion/${row.ingestionJobId}`} className="text-accent hover:underline">
                  #{row.ingestionJobId}
                </Link>
              ),
            },
            { key: 'fileName', label: 'File' },
            {
              key: 'templateId',
              label: 'Template',
              render: (row) =>
                templates.find((t) => t.templateId === row.templateId)?.name ?? row.templateId,
            },
            { key: 'currentStep', label: 'Step', render: (row) => <Badge status={row.currentStep} /> },
            { key: 'status', label: 'Status', render: (row) => <Badge status={row.status} /> },
            {
              key: 'validationErrorCount',
              label: 'Errors',
              render: (row) =>
                row.validationErrorCount == null ? '—' : String(row.validationErrorCount),
            },
          ]}
          rows={jobs}
          maxHeight="65vh"
          emptyMessage="No jobs yet — click 'Upload a document' to get started."
          onRowClick={(row) =>
            navigate(`/ingestion/${row.ingestionJobId}`, { state: { job: row } })
          }
        />
      )}
    </div>
  );
}
