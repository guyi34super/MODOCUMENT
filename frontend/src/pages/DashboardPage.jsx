import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { getTemplates } from '../api/cache';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Badge, Button, Callout, Card, Spinner } from '../components/ui';

function countBy(list, key) {
  return list.reduce((acc, item) => {
    const value = item[key] || 'UNKNOWN';
    acc[value] = (acc[value] || 0) + 1;
    return acc;
  }, {});
}

function StepCard({ step, title, stat, to, cta, highlight }) {
  return (
    <Link
      to={to}
      className={`flex min-w-56 flex-1 items-center gap-3 rounded-lg border bg-white p-3 hover:bg-sidebar/60 ${
        highlight ? 'border-accent shadow-sm' : 'border-line'
      }`}
    >
      <span
        className={`flex size-6 shrink-0 items-center justify-center rounded-full text-xs font-semibold ${
          highlight ? 'bg-accent text-white' : 'bg-pill text-accent'
        }`}
      >
        {step}
      </span>
      <span className="min-w-0">
        <span className="block truncate text-sm font-medium text-ink">{title}</span>
        <span className="block truncate text-xs text-muted">{stat}</span>
      </span>
      <span className="ml-auto shrink-0 text-sm text-accent">{cta} →</span>
    </Link>
  );
}

/**
 * A job worth reopening: not cancelled and not finished with an export. Status
 * is deliberately not part of this — every successful stage leaves the job
 * COMPLETED, so testing for it hid every normal in-progress upload.
 */
const OPEN_JOB = (job) => job.status !== 'CANCELLED' && job.currentStep !== 'EXPORTED';

const STEP_LABELS = {
  UPLOADED: 'Waiting to be parsed',
  PARSED: 'Ready to map columns',
  MAPPED: 'Ready to review and validate',
  VALIDATED: 'Ready to export',
};

/** What this job is actually waiting on, using the gates the server reports. */
function waitingOn(job) {
  const unmapped = job.unmappedFields?.length ?? 0;
  if (unmapped > 0) {
    return `${unmapped} field${unmapped === 1 ? '' : 's'} still to map`;
  }
  const errors = job.validationErrorCount ?? 0;
  if (errors > 0) {
    return `${errors} row${errors === 1 ? '' : 's'} to fix`;
  }
  return STEP_LABELS[job.currentStep] || '';
}

export default function DashboardPage() {
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  /**
   * Partial results beat a blank page: one failing endpoint used to leave the
   * whole dashboard on a spinner forever, so each panel falls back to empty and
   * the banner names what could not be loaded.
   */
  const load = useCallback(async () => {
    setLoading(true);
    const sources = [
      ['templates', () => getTemplates()],
      ['ingestionJobs', () => api.get('/api/ingestion/jobs')],
      ['extractionJobs', () => api.get('/api/extraction/jobs')],
      ['imports', () => api.get('/ImportHistory/GetAll')],
    ];
    const settled = await Promise.allSettled(sources.map(([, fetcher]) => fetcher()));
    const loaded = {};
    const failures = [];
    settled.forEach((result, i) => {
      const [key] = sources[i];
      if (result.status === 'fulfilled' && Array.isArray(result.value)) {
        loaded[key] = result.value;
      } else {
        loaded[key] = [];
        failures.push(`${key} (${result.reason?.message || 'failed'})`);
      }
    });
    setData(loaded);
    setError(failures.length ? `Could not load ${failures.join(', ')}.` : '');
    setLoading(false);
  }, []);

  useEffect(() => {
    load();
  }, [load]);

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
  // Highlight the first step the user hasn't done yet. With templates but no
  // uploads, rules are the natural next stop — step 3 only once something has
  // been uploaded before.
  const highlightStep = !data
    ? 0
    : data.templates.length === 0
      ? 1
      : data.ingestionJobs.length === 0
        ? 2
        : 0;

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Home' }]}
        title="Home"
        subtitle="Design a template, add rules, then upload and process files"
        actions={
          <Button variant="secondary" onClick={load} disabled={loading}>
            {loading ? 'Refreshing…' : 'Refresh'}
          </Button>
        }
      />
      {error && (
        <div className="mb-4 flex items-start justify-between gap-3 rounded-md bg-danger-bg px-4 py-3 text-sm text-danger-ink">
          <span>{error}</span>
          <Button variant="secondary" size="sm" onClick={load} disabled={loading}>
            Retry
          </Button>
        </div>
      )}

      {!data ? (
        <Spinner label="Loading your workspace…" />
      ) : (
        <>
          {openJobs.length > 0 && (
            <Card padding="compact" className="mb-4">
              <h2 className="mb-1 text-sm font-medium">Pick up where you left off</h2>
              <ul className="divide-y divide-line">
                {openJobs.map((job) => (
                  <li key={job.ingestionJobId} className="flex items-center gap-3 py-1.5 text-sm">
                    <Link
                      to={`/ingestion/${job.ingestionJobId}`}
                      className="text-accent hover:underline"
                    >
                      Job #{job.ingestionJobId}
                    </Link>
                    <span className="truncate text-muted">{job.fileName}</span>
                    <span className="hidden truncate text-xs text-muted sm:block">
                      {waitingOn(job)}
                    </span>
                    <span className="ml-auto flex items-center gap-2">
                      <Badge status={job.currentStep} />
                      <Badge status={job.status} />
                    </span>
                  </li>
                ))}
              </ul>
            </Card>
          )}

          {failedCount > 0 && (
            <div className="mb-4">
              <Callout variant="error" title={`${failedCount} failed job${failedCount > 1 ? 's' : ''}`}>
                Check the <Link to="/ingestion" className="underline">uploads</Link> and{' '}
                <Link to="/extraction/jobs" className="underline">extraction jobs</Link> pages to
                resume or re-run them.
              </Callout>
            </div>
          )}

          <div className="mb-4 flex flex-wrap gap-3">
            <StepCard
              step={1}
              title="Design Templates"
              stat={`${data.templates.length} template${data.templates.length === 1 ? '' : 's'}`}
              to={data.templates.length === 0 ? '/templates/new' : '/templates'}
              cta={data.templates.length === 0 ? 'Create one' : 'Manage'}
              highlight={highlightStep === 1}
            />
            <StepCard
              step={2}
              title="Set Field Rules"
              stat="Required, regex, date and range rules"
              to="/validation"
              cta="Manage"
              highlight={highlightStep === 2}
            />
            <StepCard
              step={3}
              title="Upload & Process"
              stat={`${data.ingestionJobs.length} job${data.ingestionJobs.length === 1 ? '' : 's'} · ${ingestionByStatus.COMPLETED || 0} completed · ${ingestionByStatus.FAILED || 0} failed`}
              to="/ingestion"
              cta={data.ingestionJobs.length === 0 ? 'Upload' : 'Open'}
              highlight={highlightStep === 3}
            />
          </div>

          <div className="mb-2 flex items-baseline justify-between">
            <h2 className="text-base font-medium">Recent activity</h2>
            {failedCount === 0 && (
              <span className="text-xs text-success-ink">All jobs healthy</span>
            )}
          </div>
          <DataTable
            maxHeight="50vh"
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
              {
                key: 'importDate',
                label: 'Imported',
                render: (row) => row.importDate?.replace('T', ' ').slice(0, 19) || '—',
              },
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

          <p className="mt-4 text-sm text-muted">
            Also:{' '}
            <Link to="/extraction/sources" className="text-accent hover:underline">
              Extraction Sources
            </Link>
            {' · '}
            <Link to="/extraction/jobs" className="text-accent hover:underline">
              Extraction Jobs
            </Link>
            {` — ${data.extractionJobs.length} job${data.extractionJobs.length === 1 ? '' : 's'}, ${extractionByStatus.RUNNING || 0} running`}
          </p>
        </>
      )}
    </div>
  );
}
