import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { getTemplates } from '../api/cache';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Badge, Button, Callout, Card, ErrorBanner, Spinner } from '../components/ui';

function countBy(list, key) {
  return list.reduce((acc, item) => {
    const value = item[key] || 'UNKNOWN';
    acc[value] = (acc[value] || 0) + 1;
    return acc;
  }, {});
}

function StepCard({ step, title, description, stat, to, cta, highlight }) {
  return (
    <div
      className={`flex min-w-52 flex-1 flex-col rounded-lg border bg-white p-5 ${
        highlight ? 'border-accent shadow-sm' : 'border-line'
      }`}
    >
      <div className="mb-2 flex items-center gap-2">
        <span
          className={`flex size-6 items-center justify-center rounded-full text-xs font-semibold ${
            highlight ? 'bg-accent text-white' : 'bg-pill text-accent'
          }`}
        >
          {step}
        </span>
        <h3 className="text-sm font-medium">{title}</h3>
        {highlight && (
          <span className="ml-auto rounded-full bg-pill px-2 py-0.5 text-[11px] font-semibold text-accent">
            Start here
          </span>
        )}
      </div>
      <p className="mb-1 text-sm text-muted">{description}</p>
      <p className="mb-4 text-xs text-muted">{stat}</p>
      <Link
        to={to}
        className={`mt-auto self-start rounded px-3 py-1.5 text-sm ${
          highlight
            ? 'bg-accent text-white hover:bg-accent-hover'
            : 'border border-line text-accent hover:bg-pill/50'
        }`}
      >
        {cta}
      </Link>
    </div>
  );
}

const OPEN_JOB = (job) =>
  job.status !== 'CANCELLED' && job.status !== 'COMPLETED' && job.currentStep !== 'EXPORTED';

export default function DashboardPage() {
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [templates, fields, ingestionJobs, extractionJobs, imports] = await Promise.all([
        getTemplates(),
        api.get('/TemplateFields/GetAll'),
        api.get('/api/ingestion/jobs'),
        api.get('/api/extraction/jobs'),
        api.get('/ImportHistory/GetAll'),
      ]);
      setData({ templates, fields, ingestionJobs, extractionJobs, imports });
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  if (loading && !data) return <Spinner label="Loading your workspace…" />;

  const ingestionByStatus = data ? countBy(data.ingestionJobs, 'status') : {};
  const extractionByStatus = data ? countBy(data.extractionJobs, 'status') : {};
  const failedCount = (ingestionByStatus.FAILED || 0) + (extractionByStatus.FAILED || 0);
  const recentImports = data
    ? [...data.imports]
        .sort((a, b) => (b.importDate || '').localeCompare(a.importDate || ''))
        .slice(0, 8)
    : [];
  const openJobs = data
    ? data.ingestionJobs.filter(OPEN_JOB).sort((a, b) => b.ingestionJobId - a.ingestionJobId).slice(0, 3)
    : [];
  // Highlight the first step the user hasn't done yet.
  const highlightStep = data
    ? data.templates.length === 0
      ? 1
      : data.ingestionJobs.length === 0
        ? 3
        : 0
    : 0;

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Home' }]}
        title="Home"
        subtitle="Follow the flow: design a template, add rules, then upload and process files"
        actions={<Button variant="secondary" onClick={load}>Refresh</Button>}
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {data && (
        <>
          <div className="mb-6 flex flex-wrap gap-4">
            <StepCard
              step={1}
              title="Design Templates"
              description="Define the shape of the documents you process."
              stat={`${data.templates.length} template${data.templates.length === 1 ? '' : 's'} · ${data.fields.length} field${data.fields.length === 1 ? '' : 's'}`}
              to={data.templates.length === 0 ? '/templates/new' : '/templates'}
              cta={data.templates.length === 0 ? 'Create your first template' : 'Manage templates'}
              highlight={highlightStep === 1}
            />
            <StepCard
              step={2}
              title="Set Field Rules"
              description="Add per-field checks every processed row must pass."
              stat="Required, regex, date and range rules"
              to="/validation"
              cta="Manage rules"
              highlight={false}
            />
            <StepCard
              step={3}
              title="Upload & Process"
              description="Upload a file; it is parsed, mapped, validated and exported."
              stat={`${data.ingestionJobs.length} job${data.ingestionJobs.length === 1 ? '' : 's'} · ${ingestionByStatus.COMPLETED || 0} completed · ${ingestionByStatus.FAILED || 0} failed`}
              to="/ingestion"
              cta={data.ingestionJobs.length === 0 ? 'Upload your first file' : 'Go to uploads'}
              highlight={highlightStep === 3}
            />
          </div>

          {openJobs.length > 0 && (
            <Card className="mb-6">
              <h2 className="mb-1 text-lg font-normal">Pick up where you left off</h2>
              <p className="mb-3 text-sm text-muted">These uploads are still in progress.</p>
              <ul className="divide-y divide-line">
                {openJobs.map((job) => (
                  <li key={job.ingestionJobId} className="flex items-center gap-3 py-2 text-sm">
                    <Link to={`/ingestion/${job.ingestionJobId}`} className="text-accent hover:underline">
                      Job #{job.ingestionJobId}
                    </Link>
                    <span className="truncate text-muted">{job.fileName}</span>
                    <span className="ml-auto flex items-center gap-2">
                      <Badge status={job.currentStep} />
                      <Badge status={job.status} />
                    </span>
                  </li>
                ))}
              </ul>
            </Card>
          )}

          <div className="mb-8">
            {failedCount === 0 ? (
              <Callout variant="success" title="All clear">
                No failed jobs — every upload and extraction pipeline is healthy.
              </Callout>
            ) : (
              <Callout variant="error" title={`${failedCount} failed job${failedCount > 1 ? 's' : ''}`}>
                Check the <Link to="/ingestion" className="underline">uploads</Link> and{' '}
                <Link to="/extraction/jobs" className="underline">extraction jobs</Link> pages to
                resume or re-run them.
              </Callout>
            )}
          </div>

          <h2 className="mb-3 text-xl font-normal">Recent activity</h2>
          <DataTable
            columns={[
              { key: 'importHistoryId', label: 'ID' },
              { key: 'fileName', label: 'File' },
              {
                key: 'templateId',
                label: 'Template',
                render: (row) =>
                  data.templates.find((t) => t.templateId === row.templateId)?.name ??
                  row.templateId,
              },
              { key: 'importDate', label: 'Imported', render: (row) => row.importDate?.replace('T', ' ').slice(0, 19) || '—' },
            ]}
            rows={recentImports}
            emptyMessage={
              <>
                Nothing processed yet —{' '}
                <Link to="/ingestion" className="text-accent hover:underline">
                  upload your first file
                </Link>{' '}
                to see activity here.
              </>
            }
          />

          <Card className="mt-8">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h2 className="text-lg font-normal">Data Extraction</h2>
                <p className="text-sm text-muted">
                  Pull records from websites, APIs and databases, then bridge them into the flow.
                  {` ${data.extractionJobs.length} job${data.extractionJobs.length === 1 ? '' : 's'} · ${extractionByStatus.RUNNING || 0} running · ${extractionByStatus.COMPLETED || 0} completed`}
                </p>
              </div>
              <div className="flex gap-2">
                <Link to="/extraction/sources" className="rounded border border-line px-3 py-1.5 text-sm text-accent hover:bg-pill/50">
                  Extraction Sources
                </Link>
                <Link to="/extraction/jobs" className="rounded border border-line px-3 py-1.5 text-sm text-accent hover:bg-pill/50">
                  Extraction Jobs
                </Link>
              </div>
            </div>
          </Card>
        </>
      )}
    </div>
  );
}
