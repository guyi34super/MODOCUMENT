import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getRun, retryRun } from '../api/integrations';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import usePolling from '../hooks/usePolling';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Button, ErrorBanner, Spinner } from '../components/ui';
import {
  RequireIntegrationAdmin,
  StatusChip,
  canManageIntegrations,
  canRunIntegrations,
  formatDateTime,
} from './IntegrationsCommon';

const ACTIVE = ['QUEUED', 'RUNNING', 'AWAITING_AGENT', 'RUNNING_AGENT'];
const LEVEL_TONES = { ERROR: 'text-danger-ink', WARN: 'text-amber-800' };

/**
 * `/admin/integrations/runs/:id` (ws25): one run with its log events and
 * outbound deliveries. "Retry" (INTEGRATION_RUN) replays dead deliveries or
 * re-queues a failed run. While the run is active the page polls.
 */
export default function IntegrationRunDetailPage() {
  const { id } = useParams();
  return (
    <div>
      <PageHeader
        crumbs={[
          { label: 'Integrations', to: '/admin/integrations' },
          { label: 'Runs', to: '/admin/integrations/runs' },
          { label: `Run #${id}` },
        ]}
        title={`Run #${id}`}
      />
      <RequireIntegrationAdmin allowRun>
        <RunDetail runId={id} />
      </RequireIntegrationAdmin>
    </div>
  );
}

function RunDetail({ runId }) {
  const toast = useToast();
  const { user } = useAuth();
  const [run, setRun] = useState(null);
  const [error, setError] = useState('');
  const [retrying, setRetrying] = useState(false);

  const load = useCallback(() => {
    getRun(runId)
      .then((view) => {
        setRun(view);
        setError('');
      })
      .catch((err) => setError(err.message));
  }, [runId]);

  useEffect(() => {
    load();
  }, [load]);

  usePolling(load, 5000, Boolean(run && ACTIVE.includes(run.status)));

  const retry = async () => {
    setRetrying(true);
    try {
      await toast.promise(retryRun(run.id), {
        loading: 'Retrying…',
        success: `Run #${run.id} queued for retry`,
      });
      load();
    } catch {
      // The error toast is already shown.
    } finally {
      setRetrying(false);
    }
  };

  if (error && !run) return <ErrorBanner message={error} />;
  if (!run) return <Spinner label="Loading the run…" />;

  const deliveries = run.deliveries || [];
  const dead = deliveries.filter((d) => d.status === 'DEAD').length;
  const canRetry = canRunIntegrations(user?.role) && (run.status === 'FAILED' || dead > 0);

  return (
    <div className="space-y-5">
      <ErrorBanner message={error} onDismiss={() => setError('')} />
      <div className="flex flex-wrap items-center gap-3">
        <StatusChip status={run.status} />
        <span className="text-sm text-muted">
          {canManageIntegrations(user?.role) ? (
            <Link to={`/admin/integrations/flows/${run.flowId}`} className="text-accent hover:underline">
              {run.flowName || `Flow #${run.flowId}`}
            </Link>
          ) : (
            run.flowName || `Flow #${run.flowId}`
          )}{' '}
          · {run.direction === 'OUTBOUND' ? 'Outbound' : 'Inbound'} · attempt {run.attempt ?? 1}
        </span>
        <span className="flex-1" />
        {canRetry && (
          <Button onClick={retry} disabled={retrying}>
            {dead > 0 ? `Retry ${dead} dead deliver${dead === 1 ? 'y' : 'ies'}` : 'Retry run'}
          </Button>
        )}
        <Button variant="secondary" onClick={load}>
          Refresh
        </Button>
      </div>

      <dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-[10rem_1fr]">
        <dt className="text-muted">Trigger</dt>
        <dd>{run.trigger || '—'}</dd>
        <dt className="text-muted">Scheduled for</dt>
        <dd>{formatDateTime(run.scheduledFor)}</dd>
        <dt className="text-muted">Started</dt>
        <dd>{formatDateTime(run.startedAt)}</dd>
        <dt className="text-muted">Finished</dt>
        <dd>{formatDateTime(run.finishedAt)}</dd>
        <dt className="text-muted">Records in / out</dt>
        <dd>
          {run.recordsIn ?? 0} / {run.recordsOut ?? 0}
        </dd>
        {run.ingestionJobId && (
          <>
            <dt className="text-muted">Ingestion job</dt>
            <dd>
              <Link to={`/ingestion/${run.ingestionJobId}`} className="text-accent hover:underline">
                Job #{run.ingestionJobId}
              </Link>
            </dd>
          </>
        )}
        {run.error && (
          <>
            <dt className="text-muted">Error</dt>
            <dd className="text-danger-ink">{run.error}</dd>
          </>
        )}
      </dl>

      <section aria-label="Run log">
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-muted">Log</h2>
        {(run.events || []).length === 0 ? (
          <p className="text-sm text-muted">No log events.</p>
        ) : (
          <ol className="space-y-1 rounded-lg border border-line p-3 font-mono text-xs">
            {run.events.map((event, i) => (
              <li key={i} className={LEVEL_TONES[event.level] || 'text-ink'}>
                <span className="text-muted">{formatDateTime(event.at)}</span> [{event.level}]{' '}
                {event.message}
              </li>
            ))}
          </ol>
        )}
      </section>

      <section aria-label="Deliveries">
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-muted">Deliveries</h2>
        <DataTable
          columns={[
            { key: 'id', label: 'Delivery', render: (d) => `#${d.id}` },
            { key: 'status', label: 'Status', render: (d) => <StatusChip status={d.status} /> },
            { key: 'attempts', label: 'Attempts' },
            { key: 'nextAttemptAt', label: 'Next attempt', render: (d) => formatDateTime(d.nextAttemptAt) },
            {
              key: 'lastError',
              label: 'Last error',
              render: (d) => <span className="text-xs text-danger-ink">{d.lastError || ''}</span>,
            },
          ]}
          rows={deliveries}
          emptyMessage="No outbound deliveries for this run."
        />
      </section>
    </div>
  );
}
