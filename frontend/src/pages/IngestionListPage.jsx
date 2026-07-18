import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { getTemplates } from '../api/cache';
import { createJobFromFile, listJobs } from '../api/ingestion';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Badge, Button, Callout, Card, ErrorBanner, Field, inputClass, Spinner } from '../components/ui';

export default function IngestionListPage() {
  const navigate = useNavigate();
  const [templates, setTemplates] = useState([]);
  const [jobs, setJobs] = useState(null);
  const [error, setError] = useState('');

  const [file, setFile] = useState(null);
  const [templateId, setTemplateId] = useState('');
  const [autoRun, setAutoRun] = useState(true);
  const [uploading, setUploading] = useState(false);

  const load = useCallback(async () => {
    try {
      const [tpl, jobList] = await Promise.all([getTemplates(), listJobs()]);
      setTemplates(tpl);
      setJobs(jobList.sort((a, b) => b.ingestionJobId - a.ingestionJobId));
    } catch (err) {
      setError(err.message);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const upload = async (e) => {
    e.preventDefault();
    if (!file || !templateId) return;
    setUploading(true);
    setError('');
    try {
      const job = await createJobFromFile(file, Number(templateId), autoRun);
      navigate(`/ingestion/${job.ingestionJobId}`);
    } catch (err) {
      setError(err.message);
    } finally {
      setUploading(false);
    }
  };

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Docs' }, { label: 'File Uploads' }]}
        title="File Uploads"
        subtitle="Upload a document, review the extracted table, validate and save"
        actions={<Button variant="secondary" onClick={load}>Refresh</Button>}
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      <Card className="mb-6">
        <h2 className="mb-1 text-lg font-normal">Upload a document</h2>
        <p className="mb-4 text-sm text-muted">
          Supported: xlsx, xls, csv, pdf, docx, doc and scanned images (png, jpg, tiff…). The
          pipeline parses the file, auto-maps columns to your template and validates every row.
        </p>
        <form onSubmit={upload} className="space-y-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="File">
              <input
                type="file"
                required
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
            </Field>
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={autoRun}
              onChange={(e) => setAutoRun(e.target.checked)}
            />
            Run the full pipeline immediately (parse → map → validate)
          </label>
          <Button type="submit" disabled={uploading || !file || !templateId}>
            {uploading ? 'Uploading & processing…' : 'Upload & process'}
          </Button>
          {uploading && (
            <Callout variant="info" title="Processing">
              Parsing, mapping and validating can take a moment for large or scanned files.
            </Callout>
          )}
        </form>
      </Card>

      <h2 className="mb-3 text-xl font-normal">Jobs</h2>
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
          emptyMessage="No ingestion jobs yet — upload a file above."
        />
      )}
    </div>
  );
}
