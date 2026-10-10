import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getDashboard } from '../api/dashboard';
import { listAgents, listConnections, listFlows, listRuns } from '../api/integrations';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Button, ErrorBanner, KpiTile, Spinner } from '../components/ui';
import { RequireIntegrationAdmin, StatusChip, formatDateTime } from './IntegrationsCommon';

const DAY_MS = 24 * 60 * 60 * 1000;

const countBy = (rows, key) =>
  (rows || []).reduce((acc, row) => {
    acc[row[key]] = (acc[row[key]] || 0) + 1;
    return acc;
  }, {});

/** Settles every call: one failing endpoint must not blank the whole overview. */
const settle = (promise) => promise.then((value) => ({ value })).catch((error) => ({ error }));

/**
 * `/admin/integrations` (ws25/ws26): the hub at a glance — connections and
 * their test status, enabled flows, the latest runs, failures in the last
 * 24 h, dead letters and agents online/offline — with links to each page.
 */
export default function IntegrationsOverviewPage() {
  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Integrations' }]}
        title="Integration hub"
        subtitle="Connections, flows, runs and agents at a glance"
      />
      <RequireIntegrationAdmin>
        <Overview />
      </RequireIntegrationAdmin>
    </div>
  );
}

function Overview() {
  const [data, setData] = useState(null);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    const [connections, flows, runs, agents, dashboard] = await Promise.all([
      settle(listConnections()),
      settle(listFlows()),
      settle(listRuns()),
      settle(listAgents()),
      settle(getDashboard()),
    ]);
    const failed = [connections, flows, runs, agents].find((r) => r.error);
    setError(failed ? failed.error.message : '');
    setData({
      connections: connections.value || [],
      flows: flows.value || [],
      runs: runs.value || [],
      agents: agents.value || [],
      integrations: dashboard.value?.integrations || null,
    });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  if (!data) return <Spinner label="Loading the integration hub…" />;

  const connectionStatus = countBy(data.connections, 'status');
  const agentStatus = countBy(data.agents, 'status');
  const enabledFlows = data.flows.filter((f) => f.enabled).length;
  const since = Date.now() - DAY_MS;
  const failures24h = data.runs.filter((r) => {
    if (r.status !== 'FAILED') return false;
    const at = new Date(r.finishedAt || r.startedAt || r.scheduledFor).getTime();
    return Number.isNaN(at) || at >= since;
  }).length;
  const lastRuns = [...data.runs]
    .sort(
      (a, b) =>
        new Date(b.startedAt || b.scheduledFor || 0) - new Date(a.startedAt || a.scheduledFor || 0)
    )
    .slice(0, 5);
  const deadLetters = data.integrations?.deadLetters;
  const updates = data.agents.filter((a) => a.updateAvailable).length;

  return (
    <div className="space-y-6">
      <div className="flex justify-end">
        <Button variant="secondary" onClick={load}>
          Refresh
        </Button>
      </div>
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      <section aria-label="Integration health" className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <KpiTile
          testId="kpi-connections"
          label="Connections"
          value={data.connections.length}
          hint={`${connectionStatus.OK || 0} OK · ${connectionStatus.FAILED || 0} failing · ${
            connectionStatus.UNTESTED || 0
          } untested`}
          tone={connectionStatus.FAILED ? 'warning' : 'neutral'}
          to="/admin/integrations/connections"
        />
        <KpiTile
          testId="kpi-flows-enabled"
          label="Flows enabled"
          value={enabledFlows}
          hint={`of ${data.flows.length} flow${data.flows.length === 1 ? '' : 's'}`}
          to="/admin/integrations/flows"
        />
        <KpiTile
          testId="kpi-run-failures"
          label="Failed runs (24 h)"
          value={failures24h}
          tone={failures24h > 0 ? 'danger' : 'neutral'}
          to="/admin/integrations/runs"
        />
        <KpiTile
          testId="kpi-dead-letters"
          label="Dead letters"
          value={deadLetters ?? null}
          hint="Deliveries that ran out of retries"
          tone={deadLetters > 0 ? 'danger' : 'neutral'}
          to="/admin/integrations/runs"
        />
        <KpiTile
          testId="kpi-agents"
          label="Agents online"
          value={agentStatus.ONLINE || 0}
          hint={`${agentStatus.OFFLINE || 0} offline · ${agentStatus.PENDING_ENROLMENT || 0} awaiting enrolment${
            updates ? ` · ${updates} update${updates === 1 ? '' : 's'} available` : ''
          }`}
          tone={agentStatus.OFFLINE ? 'warning' : 'neutral'}
          to="/admin/integrations/agents"
        />
      </section>

      <section aria-label="Last runs">
        <div className="mb-2 flex items-baseline justify-between">
          <h2 className="text-sm font-semibold uppercase tracking-wide text-muted">Last runs</h2>
          <Link to="/admin/integrations/runs" className="text-sm text-accent hover:underline">
            All runs
          </Link>
        </div>
        <DataTable
          columns={[
            {
              key: 'id',
              label: 'Run',
              render: (r) => (
                <Link to={`/admin/integrations/runs/${r.id}`} className="text-accent hover:underline">
                  #{r.id}
                </Link>
              ),
            },
            { key: 'flowName', label: 'Flow', render: (r) => r.flowName || `flow #${r.flowId}` },
            { key: 'status', label: 'Status', render: (r) => <StatusChip status={r.status} /> },
            { key: 'startedAt', label: 'Started', render: (r) => formatDateTime(r.startedAt || r.scheduledFor) },
            { key: 'records', label: 'In / out', render: (r) => `${r.recordsIn ?? 0} / ${r.recordsOut ?? 0}` },
          ]}
          rows={lastRuns}
          emptyMessage="No runs yet."
        />
      </section>

      <nav aria-label="Integration pages" className="flex flex-wrap gap-2 text-sm">
        {[
          ['/admin/integrations/connections', 'Connections'],
          ['/admin/integrations/flows', 'Flows'],
          ['/admin/integrations/api-clients', 'API clients'],
          ['/admin/integrations/agents', 'Agents'],
          ['/admin/integrations/egress-rules', 'Egress destinations'],
        ].map(([to, label]) => (
          <Link key={to} to={to} className="rounded border border-line px-3 py-1 text-accent hover:bg-pill/50">
            {label}
          </Link>
        ))}
      </nav>
    </div>
  );
}
