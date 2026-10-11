import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { isPendingChange } from '../api/changes';
import { disableFlow, enableFlow, listFlows, runFlow } from '../api/integrations';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Button, ErrorBanner, Spinner, Tag } from '../components/ui';
import {
  FLOW_EVENTS,
  PendingTag,
  RequireIntegrationAdmin,
  SentForApproval,
  StatusChip,
  canRunIntegrations,
  formatDateTime,
} from './IntegrationsCommon';

const EVENT_LABELS = Object.fromEntries(FLOW_EVENTS.map((e) => [e.value, e.label]));

export function describeTrigger(flow) {
  switch (flow.trigger) {
    case 'SCHEDULE':
      return `Schedule ${flow.cron || ''}`.trim();
    case 'EVENT':
      return `On ${(flow.events || []).map((e) => EVENT_LABELS[e] || e).join(', ') || 'events'}`;
    default:
      return 'Manual';
  }
}

/**
 * Enable / disable a flow with the contract's semantics: enabling an
 * OUTBOUND flow (data leaves the bank's tenant) answers 202 and waits for a
 * second administrator; INBOUND enables at once (200); disabling is always
 * immediate. Returns `{pending}` with the change, or `{flow}`.
 */
export async function toggleFlow(toast, flow, enable) {
  if (!enable) {
    const updated = await toast.promise(disableFlow(flow.id), {
      loading: 'Disabling…',
      success: `Flow "${flow.name}" disabled`,
    });
    return { flow: updated };
  }
  const result = await toast.promise(enableFlow(flow.id), {
    loading: 'Enabling…',
    success: (r) =>
      isPendingChange(r)
        ? `Sent for approval (change #${r.pendingChangeId})`
        : `Flow "${flow.name}" enabled`,
  });
  return isPendingChange(result) ? { pending: result } : { flow: result };
}

/**
 * `/admin/integrations/flows` (ws25): what to receive or send, and when.
 * The list with enable/disable and "Run now"; the builder lives at
 * `/admin/integrations/flows/new` and `/admin/integrations/flows/:id`.
 */
export default function IntegrationFlowsPage() {
  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Integrations', to: '/admin/integrations' }, { label: 'Flows' }]}
        title="Flows"
        subtitle="What to receive or send, and when"
      />
      <RequireIntegrationAdmin>
        <Flows />
      </RequireIntegrationAdmin>
    </div>
  );
}

function Flows() {
  const toast = useToast();
  const { user } = useAuth();
  const [flows, setFlows] = useState(null);
  const [error, setError] = useState('');
  const [sent, setSent] = useState(null);
  const [busy, setBusy] = useState(null);

  const load = useCallback(() => {
    listFlows()
      .then((rows) => {
        setFlows(rows || []);
        setError('');
      })
      .catch((err) => setError(err.message));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const toggle = async (flow, enable) => {
    setBusy(flow.id);
    try {
      const { pending } = await toggleFlow(toast, flow, enable);
      if (pending) setSent({ what: `Enabling flow "${flow.name}"`, change: pending });
      load();
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(null);
    }
  };

  const runNow = async (flow) => {
    setBusy(flow.id);
    try {
      const run = await toast.promise(runFlow(flow.id), {
        loading: 'Starting…',
        success: (r) => (r?.id ? `Run #${r.id} queued` : 'Run queued'),
      });
      load();
      return run;
    } catch {
      return null;
    } finally {
      setBusy(null);
    }
  };

  const columns = [
    {
      key: 'name',
      label: 'Name',
      render: (row) => (
        <span className="flex flex-wrap items-center gap-1.5">
          <Link to={`/admin/integrations/flows/${row.id}`} className="font-medium text-accent hover:underline">
            {row.name}
          </Link>
          {row.pendingChangeId && <PendingTag changeId={row.pendingChangeId} />}
        </span>
      ),
    },
    {
      key: 'direction',
      label: 'Direction',
      render: (row) => (
        <Tag tone={row.direction === 'OUTBOUND' ? 'warning' : 'info'}>
          {row.direction === 'OUTBOUND' ? 'Outbound' : 'Inbound'}
        </Tag>
      ),
    },
    { key: 'connectionName', label: 'Connection' },
    { key: 'templateName', label: 'Template' },
    { key: 'trigger', label: 'Trigger', render: (row) => describeTrigger(row) },
    {
      key: 'enabled',
      label: 'State',
      render: (row) => <StatusChip status={row.enabled ? 'ENABLED' : 'DISABLED'} />,
    },
    {
      key: 'lastRun',
      label: 'Last run',
      render: (row) =>
        row.lastRun ? (
          <Link to={`/admin/integrations/runs/${row.lastRun.id}`} className="inline-flex flex-col">
            <StatusChip status={row.lastRun.status} />
            <span className="text-xs text-muted">{formatDateTime(row.lastRun.finishedAt)}</span>
          </Link>
        ) : (
          '—'
        ),
    },
    {
      key: 'actions',
      label: '',
      render: (row) => (
        <div className="flex flex-wrap justify-end gap-1">
          {canRunIntegrations(user?.role) && (
            <Button
              size="sm"
              variant="secondary"
              disabled={busy === row.id}
              onClick={() => runNow(row)}
              aria-label={`Run ${row.name} now`}
            >
              Run now
            </Button>
          )}
          {row.enabled ? (
            <Button
              size="sm"
              variant="danger"
              disabled={busy === row.id}
              onClick={() => toggle(row, false)}
              aria-label={`Disable ${row.name}`}
            >
              Disable
            </Button>
          ) : (
            <Button
              size="sm"
              disabled={busy === row.id || Boolean(row.pendingChangeId)}
              onClick={() => toggle(row, true)}
              aria-label={`Enable ${row.name}`}
            >
              Enable
            </Button>
          )}
        </div>
      ),
    },
  ];

  return (
    <>
      <div className="mb-4 flex justify-end">
        <Link
          to="/admin/integrations/flows/new"
          className="inline-flex items-center rounded bg-accent px-4 py-1.5 text-sm font-medium text-white hover:bg-accent-hover"
        >
          New flow
        </Link>
      </div>
      <ErrorBanner message={error} onDismiss={() => setError('')} />
      <SentForApproval what={sent?.what} change={sent?.change} onDismiss={() => setSent(null)} />
      {!flows && !error && <Spinner label="Loading flows…" />}
      {flows && (
        <DataTable
          caption="Flows"
          columns={columns}
          rows={flows}
          emptyMessage="No flows yet. A flow says what to receive or send over a connection, and when."
        />
      )}
    </>
  );
}
