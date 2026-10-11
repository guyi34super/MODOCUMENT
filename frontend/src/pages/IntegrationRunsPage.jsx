import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listRuns } from '../api/integrations';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import { Button, ErrorBanner, Spinner, inputClass } from '../components/ui';
import { RequireIntegrationAdmin, StatusChip, formatDateTime } from './IntegrationsCommon';

const RUN_STATUSES = [
  'QUEUED',
  'RUNNING',
  'AWAITING_AGENT',
  'RUNNING_AGENT',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
];

/**
 * `/admin/integrations/runs` (ws25): the run history (INTEGRATION_RUN —
 * administrators and READ_WRITE users), filterable by status.
 */
export default function IntegrationRunsPage() {
  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Integrations', to: '/admin/integrations' }, { label: 'Runs' }]}
        title="Integration runs"
        subtitle="Every inbound and outbound run, with its log"
      />
      <RequireIntegrationAdmin allowRun>
        <Runs />
      </RequireIntegrationAdmin>
    </div>
  );
}

function Runs() {
  const [status, setStatus] = useState('');
  const [runs, setRuns] = useState(null);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    setRuns(null);
    listRuns({ status })
      .then((rows) => {
        setRuns(rows || []);
        setError('');
      })
      .catch((err) => setError(err.message));
  }, [status]);

  useEffect(() => {
    load();
  }, [load]);

  const columns = [
    {
      key: 'id',
      label: 'Run',
      render: (row) => (
        <Link to={`/admin/integrations/runs/${row.id}`} className="text-accent hover:underline">
          #{row.id}
        </Link>
      ),
    },
    { key: 'flowName', label: 'Flow', render: (row) => row.flowName || `flow #${row.flowId}` },
    {
      key: 'direction',
      label: 'Direction',
      render: (row) => (row.direction === 'OUTBOUND' ? 'Outbound' : row.direction === 'INBOUND' ? 'Inbound' : '—'),
    },
    { key: 'status', label: 'Status', render: (row) => <StatusChip status={row.status} /> },
    { key: 'trigger', label: 'Trigger', render: (row) => row.trigger || '—' },
    { key: 'startedAt', label: 'Started', render: (row) => formatDateTime(row.startedAt || row.scheduledFor) },
    { key: 'finishedAt', label: 'Finished', render: (row) => formatDateTime(row.finishedAt) },
    {
      key: 'records',
      label: 'Records in / out',
      render: (row) => `${row.recordsIn ?? 0} / ${row.recordsOut ?? 0}`,
    },
    {
      key: 'error',
      label: 'Error',
      render: (row) => <span className="text-xs text-danger-ink">{row.error || ''}</span>,
    },
  ];

  return (
    <>
      <div className="mb-4 flex flex-wrap justify-end gap-2">
        <select
          aria-label="Status filter"
          className={`${inputClass} w-auto`}
          value={status}
          onChange={(e) => setStatus(e.target.value)}
        >
          <option value="">All statuses</option>
          {RUN_STATUSES.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
        <Button variant="secondary" onClick={load}>
          Refresh
        </Button>
      </div>
      <ErrorBanner message={error} onDismiss={() => setError('')} />
      {!runs && !error && <Spinner label="Loading runs…" />}
      {runs && <DataTable caption="Integration runs" columns={columns} rows={runs} emptyMessage="No runs yet." />}
    </>
  );
}
