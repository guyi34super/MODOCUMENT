import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getTemplates } from '../api/cache';
import {
  bridgeToIngestion,
  cancelJob,
  getAudit,
  getJob,
  getRecords,
  pauseJob,
  resumeJob,
  startJob,
} from '../api/extraction';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Badge, Button, Callout, Card, ErrorBanner, Field, inputClass, Spinner } from '../components/ui';
import { useToast } from '../context/ToastContext';
import usePolling from '../hooks/usePolling';

const ACTION_MESSAGES = {
  start: { loading: 'Starting job…', success: 'Job started' },
  pause: { loading: 'Pausing job…', success: 'Job paused' },
  resume: { loading: 'Resuming job…', success: 'Job resumed' },
  cancel: { loading: 'Cancelling job…', success: 'Job cancelled' },
};

const TERMINAL_STATUSES = ['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED'];
const BLOCKED_ACTIONS = ['ROBOTS_BLOCKED', 'DOMAIN_BLOCKED', 'RATE_LIMITED'];
const POLL_MS = 2500;

/** Whether a polled job differs from the shown one in what the page shows live. */
export function jobChanged(previous, next) {
  if (!previous || !next) return previous !== next;
  if (previous.status !== next.status) return true;
  const a = previous.counters || {};
  const b = next.counters || {};
  for (const key of new Set([...Object.keys(a), ...Object.keys(b)])) {
    if (a[key] !== b[key]) return true;
  }
  return false;
}

const isBlockedEvent = (event) =>
  BLOCKED_ACTIONS.includes(event.action) || event.outcome === 'blocked';

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

  const [auditEvents, setAuditEvents] = useState([]);
  const [auditNextAfterId, setAuditNextAfterId] = useState(null);
  const [auditLoaded, setAuditLoaded] = useState(false);

  const [bridgeTemplateId, setBridgeTemplateId] = useState('');
  const [bridgeResult, setBridgeResult] = useState(null);

  const prevStatus = useRef(null);
  const runningToast = useRef(null);
  const autoLoaded = useRef(false);

  // Load immediately, then keep polling while the job is still moving so the
  // progress counters tick live and status changes surface as toasts.
  // v1.2.0: the poll is usePolling's setTimeout chain — never two requests
  // at once, nothing while the tab is hidden, an immediate refresh when it
  // is shown again — and a poll that brings no visible change (same status,
  // same counters) does not re-render the page.
  const jobRef = useRef(null);
  const [pollFailed, setPollFailed] = useState(false);

  useEffect(() => {
    let active = true;
    prevStatus.current = null;
    autoLoaded.current = false;
    jobRef.current = null;
    setJob(null);
    setPollFailed(false);
    setRecords([]);
    setRecordsLoaded(false);
    setAuditEvents([]);
    setAuditLoaded(false);
    getJob(id)
      .then((loaded) => {
        if (!active) return;
        jobRef.current = loaded;
        setJob(loaded);
        setError('');
      })
      .catch((err) => {
        if (active) setError(err.message);
      });
    getTemplates().then(setTemplates).catch(() => {});
    return () => {
      active = false;
    };
  }, [id]);

  const poll = useCallback(async () => {
    try {
      const loaded = await getJob(id);
      // An answer for a job this page no longer shows is dropped.
      if (!jobRef.current || jobRef.current.id !== loaded?.id) return;
      if (!jobChanged(jobRef.current, loaded)) return;
      jobRef.current = loaded;
      setJob(loaded);
      setError('');
    } catch (err) {
      // As before: a failing poll stops the live updates and says why.
      setError(err.message);
      setPollFailed(true);
    }
  }, [id]);

  usePolling(
    poll,
    POLL_MS,
    Boolean(job) && !TERMINAL_STATUSES.includes(job.status) && !pollFailed
  );

  // Lifecycle toasts: a persistent "running" toast that morphs into the
  // completion outcome. Jobs already terminal on arrival stay silent.
  useEffect(() => {
    if (!job) return;
    const { status } = job;
    const prev = prevStatus.current;
    prevStatus.current = status;
    if (status === 'RUNNING') {
      if (runningToast.current == null) {
        runningToast.current = toast.loading('Extraction running…');
      }
      return;
    }
    if (!TERMINAL_STATUSES.includes(status)) return;
    const watched = runningToast.current != null || (prev && !TERMINAL_STATUSES.includes(prev));
    if (!watched) return;
    const processed = job.counters?.recordsProcessed ?? 0;
    const success = status === 'COMPLETED' && processed > 0;
    const message = success
      ? `Extraction completed — ${processed} processed record${processed === 1 ? '' : 's'}`
      : status === 'COMPLETED'
        ? 'Job completed but produced no records — check the audit trail'
        : status === 'COMPLETED_WITH_ERRORS'
          ? 'Extraction completed with errors — check the audit trail'
          : `Extraction ${status.toLowerCase()}`;
    if (runningToast.current != null) {
      toast.update(runningToast.current, { type: success ? 'success' : 'error', message });
      runningToast.current = null;
    } else if (success) {
      toast.success(message);
    } else {
      toast.error(message);
    }
  }, [job, toast]);

  // Don't leave an orphaned "running" toast behind when navigating away.
  useEffect(
    () => () => {
      if (runningToast.current != null) {
        toast.dismiss(runningToast.current);
        runningToast.current = null;
      }
    },
    [toast]
  );

  const loadRecords = useCallback(
    async (afterId) => {
      setBusyAction('records');
      try {
        const page = await getRecords(id, { afterId });
        setRecords((prev) => (afterId ? [...prev, ...page.records] : page.records));
        setNextAfterId(page.nextAfterId || null);
        setRecordsLoaded(true);
      } catch (err) {
        setError(err.message);
      } finally {
        setBusyAction('');
      }
    },
    [id]
  );

  const loadAudit = useCallback(
    async (afterId) => {
      setBusyAction('audit');
      try {
        const page = await getAudit(id, { afterId });
        setAuditEvents((prev) => (afterId ? [...prev, ...page.events] : page.events));
        setAuditNextAfterId(page.nextAfterId || null);
        setAuditLoaded(true);
      } catch (err) {
        setError(err.message);
      } finally {
        setBusyAction('');
      }
    },
    [id]
  );

  // Once the job settles, show its results without another click — and when
  // it produced nothing, the audit trail that explains why.
  useEffect(() => {
    if (!job || autoLoaded.current || !TERMINAL_STATUSES.includes(job.status)) return;
    autoLoaded.current = true;
    loadRecords();
    if ((job.counters?.recordsProcessed ?? 0) === 0) {
      loadAudit();
    }
  }, [job, loadRecords, loadAudit]);

  const runAction = async (name, fn) => {
    setBusyAction(name);
    try {
      const updated = await toast.promise(fn, ACTION_MESSAGES[name] || { loading: 'Working…' });
      jobRef.current = updated;
      setJob(updated);
      setPollFailed(false);
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
  const completedEmpty =
    ['COMPLETED', 'COMPLETED_WITH_ERRORS'].includes(job.status) &&
    (counters.recordsProcessed ?? 0) === 0;

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Extraction Jobs', to: '/extraction/jobs' }, { label: job.id.slice(0, 12) }]}
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
        {!TERMINAL_STATUSES.includes(job.status) && (
          <span className="text-sm text-muted">Updating live…</span>
        )}
      </div>

      {job.errorSummary && (
        <div className="mb-5">
          <Callout variant="error" title="Errors during extraction">{job.errorSummary}</Callout>
        </div>
      )}

      {completedEmpty && (
        <div className="mb-5">
          <Callout variant="error" title="Completed without records">
            Every fetch was skipped or blocked. Common causes: robots.txt disallows the URL (or
            could not be fetched — the crawler fails closed), the seed host is not covered by the
            source&apos;s allowed domains, or the site answered 401/403/404. The audit trail below
            shows the exact reason per URL.
          </Callout>
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

      <Card className="mb-5">
        <div className="mb-3 flex items-center justify-between">
          <div>
            <h2 className="text-lg font-normal">Audit trail</h2>
            <p className="text-sm text-muted">Every fetch, block, retry and LLM call this job made.</p>
          </div>
          <Button variant="secondary" onClick={() => loadAudit()} disabled={busyAction === 'audit'}>
            {auditLoaded ? 'Reload' : 'Load audit trail'}
          </Button>
        </div>
        {auditLoaded && (
          <>
            <DataTable
              columns={[
                {
                  key: 'timestamp',
                  label: 'Time',
                  render: (row) => (
                    <span className="whitespace-nowrap text-xs">
                      {row.timestamp ? String(row.timestamp).replace('T', ' ').slice(0, 19) : '—'}
                    </span>
                  ),
                },
                {
                  key: 'action',
                  label: 'Action',
                  render: (row) => (
                    <span className={isBlockedEvent(row) ? 'font-semibold text-danger-ink' : ''}>
                      {row.action}
                    </span>
                  ),
                },
                {
                  key: 'outcome',
                  label: 'Outcome',
                  render: (row) => (
                    <span className={row.outcome === 'blocked' ? 'text-danger-ink' : ''}>
                      {row.outcome || '—'}
                    </span>
                  ),
                },
                {
                  key: 'uri',
                  label: 'URI',
                  render: (row) => (
                    <span className="block max-w-xs truncate font-mono text-xs" title={row.uri}>
                      {row.uri || '—'}
                    </span>
                  ),
                },
                {
                  key: 'detail',
                  label: 'Detail',
                  render: (row) => (
                    <span className="block max-w-sm truncate" title={row.detail}>
                      {row.detail || '—'}
                    </span>
                  ),
                },
              ]}
              rows={auditEvents}
              emptyMessage="No audit events recorded for this job."
            />
            {auditNextAfterId && (
              <Button
                variant="secondary"
                className="mt-3"
                onClick={() => loadAudit(auditNextAfterId)}
                disabled={busyAction === 'audit'}
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
