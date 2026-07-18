import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getTemplates } from '../api/cache';
import {
  bridgeToIngestion,
  cancelJob,
  getJob,
  getRecords,
  pauseJob,
  resumeJob,
  startJob,
} from '../api/extraction';
import usePolling from '../hooks/usePolling';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Badge, Button, Callout, Card, ErrorBanner, Field, inputClass, Spinner } from '../components/ui';
import { useToast } from '../context/ToastContext';

const ACTIVE_STATUSES = ['PENDING', 'RUNNING'];

const ACTION_MESSAGES = {
  start: { loading: 'Starting job…', success: 'Job started' },
  pause: { loading: 'Pausing job…', success: 'Job paused' },
  resume: { loading: 'Resuming job…', success: 'Job resumed' },
  cancel: { loading: 'Cancelling job…', success: 'Job cancelled' },
};

export default function ExtractionJobDetailPage() {
  const { id } = useParams();
  const toast = useToast();
  const [job, setJob] = useState(null);
  const [templates, setTemplates] = useState([]);
  const [error, setError] = useState('');
  const [busyAction, setBusyAction] = useState('');

  const [records, setRecords] = useState([]);
  const [nextAfterId, setNextAfterId] = useState(null);
  const [recordsLoaded, setRecordsLoaded] = useState(false);

  const [bridgeTemplateId, setBridgeTemplateId] = useState('');
  const [bridgeResult, setBridgeResult] = useState(null);

  const refresh = useCallback(async () => {
    try {
      setJob(await getJob(id));
    } catch (err) {
      setError(err.message);
    }
  }, [id]);

  useEffect(() => {
    refresh();
    getTemplates().then(setTemplates).catch(() => {});
  }, [refresh]);

  // Poll only while the job is actually doing work; the interval disappears
  // the moment the job reaches a terminal or paused state.
  const isActive = Boolean(job && ACTIVE_STATUSES.includes(job.status));
  usePolling(refresh, 3000, isActive);

  const runAction = async (name, fn) => {
    setBusyAction(name);
    try {
      setJob(await toast.promise(fn, ACTION_MESSAGES[name] || { loading: 'Working…' }));
    } catch {
      // The error toast is already shown.
    } finally {
      setBusyAction('');
    }
  };

  const loadRecords = async (afterId) => {
    setBusyAction('records');
    try {
      const page = await toast.promise(getRecords(id, { afterId }), {
        loading: 'Loading records…',
      });
      setRecords((prev) => (afterId ? [...prev, ...page.records] : page.records));
      setNextAfterId(page.nextAfterId || null);
      setRecordsLoaded(true);
    } catch {
      // The error toast is already shown.
    } finally {
      setBusyAction('');
    }
  };

  const runBridge = async (e) => {
    e.preventDefault();
    setBusyAction('bridge');
    try {
      const result = await toast.promise(bridgeToIngestion(id, Number(bridgeTemplateId)), {
        loading: 'Bridging records into an ingestion job…',
        success: (r) => `Created ingestion job #${r.ingestionJobId}`,
      });
      setBridgeResult(result);
    } catch {
      // The error toast is already shown.
    } finally {
      setBusyAction('');
    }
  };

  if (!job) return error ? <ErrorBanner message={error} /> : <Spinner label="Loading job…" />;

  const counters = job.counters || {};

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Docs' }, { label: 'Extraction Jobs', to: '/extraction/jobs' }, { label: job.id.slice(0, 12) }]}
        title="Extraction Job"
        subtitle={<span className="font-mono text-sm">{job.id}</span>}
        actions={
          <>
            <Button
              onClick={() => runAction('start', () => startJob(id))}
              disabled={Boolean(busyAction) || job.status !== 'PENDING'}
            >
              Start
            </Button>
            <Button
              variant="secondary"
              onClick={() => runAction('pause', () => pauseJob(id))}
              disabled={Boolean(busyAction) || job.status !== 'RUNNING'}
            >
              Pause
            </Button>
            <Button
              variant="secondary"
              onClick={() => runAction('resume', () => resumeJob(id))}
              disabled={Boolean(busyAction) || job.status !== 'PAUSED'}
            >
              Resume
            </Button>
            <Button
              variant="danger"
              onClick={() => runAction('cancel', () => cancelJob(id))}
              disabled={Boolean(busyAction) || !['PENDING', 'RUNNING', 'PAUSED'].includes(job.status)}
            >
              Cancel
            </Button>
          </>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      <div className="mb-5 flex items-center gap-3">
        <Badge status={job.status} />
        {isActive && <span className="text-xs text-muted">auto-refreshing every 3s…</span>}
      </div>

      {job.errorSummary && (
        <div className="mb-5">
          <Callout variant="error" title="Errors during extraction">{job.errorSummary}</Callout>
        </div>
      )}

      <Card className="mb-5">
        <h2 className="mb-3 text-lg font-normal">Progress</h2>
        <div className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-3">
          {[
            ['Tasks completed', counters.tasksCompleted],
            ['Tasks skipped', counters.tasksSkipped],
            ['Tasks dead', counters.tasksDead],
            ['Raw records', counters.recordsRaw],
            ['Processed records', counters.recordsProcessed],
            ['Deduplicated', counters.recordsDeduped],
          ].map(([label, value]) => (
            <div key={label}>
              <p className="text-xs uppercase tracking-wide text-muted">{label}</p>
              <p className="text-2xl font-semibold">{value ?? 0}</p>
            </div>
          ))}
        </div>
      </Card>

      <Card className="mb-5">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-lg font-normal">Processed records</h2>
          <Button variant="secondary" onClick={() => loadRecords()} disabled={busyAction === 'records'}>
            {recordsLoaded ? 'Reload' : 'Load records'}
          </Button>
        </div>
        {recordsLoaded && (
          <>
            <DataTable
              columns={[
                { key: 'id', label: 'ID', render: (row) => <span className="font-mono text-xs">{String(row.id).slice(0, 10)}…</span> },
                {
                  key: 'fields',
                  label: 'Fields',
                  render: (row) => (
                    <code className="block max-w-xl truncate text-xs" title={JSON.stringify(row.fields)}>
                      {JSON.stringify(row.fields)}
                    </code>
                  ),
                },
              ]}
              rows={records}
              emptyMessage="No processed records yet."
            />
            {nextAfterId && (
              <Button
                variant="secondary"
                className="mt-3"
                onClick={() => loadRecords(nextAfterId)}
                disabled={busyAction === 'records'}
              >
                Load more
              </Button>
            )}
          </>
        )}
      </Card>

      <Card>
        <h2 className="mb-1 text-lg font-normal">Bridge into ingestion</h2>
        <p className="mb-3 text-sm text-muted">
          Once the job completes, map + validate its records against a template — this creates a
          standard ingestion job you can review and export.
        </p>
        {bridgeResult ? (
          <Callout variant="success" title="Bridged">
            Created ingestion job{' '}
            <Link to={`/ingestion/${bridgeResult.ingestionJobId}`} className="underline">
              #{bridgeResult.ingestionJobId}
            </Link>
            {' '}— open it to review mapping, validation and export.
          </Callout>
        ) : (
          <form onSubmit={runBridge} className="flex flex-wrap items-end gap-2">
            <Field label="Template">
              <select
                required
                className={`${inputClass} w-56`}
                value={bridgeTemplateId}
                onChange={(e) => setBridgeTemplateId(e.target.value)}
              >
                <option value="">Select a template…</option>
                {templates.map((t) => (
                  <option key={t.templateId} value={t.templateId}>
                    {t.name}
                  </option>
                ))}
              </select>
            </Field>
            <Button
              type="submit"
              disabled={
                Boolean(busyAction) ||
                !['COMPLETED', 'COMPLETED_WITH_ERRORS'].includes(job.status)
              }
            >
              {busyAction === 'bridge' ? 'Bridging…' : 'Bridge records'}
            </Button>
            {!['COMPLETED', 'COMPLETED_WITH_ERRORS'].includes(job.status) && (
              <p className="w-full text-xs text-muted">Available once the job has completed.</p>
            )}
          </form>
        )}
      </Card>
    </div>
  );
}
