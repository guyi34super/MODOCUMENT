import { useCallback, useEffect, useState } from 'react';
import { isPendingChange } from '../api/changes';
import { deleteConnection, listAgents, listConnections, testConnection } from '../api/integrations';
import { useToast } from '../context/ToastContext';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import ConfirmDialog from '../components/ConfirmDialog';
import ConnectionWizard, { TestResult } from '../components/ConnectionWizard';
import { Button, ErrorBanner, Spinner, Tag } from '../components/ui';
import {
  CONNECTION_TYPE_LABELS,
  PendingTag,
  RequireIntegrationAdmin,
  SentForApproval,
  StatusChip,
  formatDateTime,
} from './IntegrationsCommon';

const CRUMBS = [
  { label: 'Integrations', to: '/admin/integrations' },
  { label: 'Connections' },
];

/** Where a connection points, from its non-secret config. */
function target(connection) {
  const c = connection.config || {};
  switch (connection.type) {
    case 'REST_API':
      return c.baseUrl;
    case 'DATABASE':
      return [c.vendor, `${c.host || '?'}:${c.port || '?'}/${c.database || '?'}`]
        .filter(Boolean)
        .join(' · ');
    case 'SFTP':
      return `${c.host || '?'}:${c.port || 22}${c.folder || ''}`;
    case 'WEBHOOK':
      return c.url;
    default:
      return '';
  }
}

/**
 * `/admin/integrations/connections` (ws25): where the bank's systems are.
 * List + the create/edit wizard (ConnectionWizard). New connections and
 * host/credential changes go to a second administrator (202); "Test" runs
 * the stored connection (DIRECT) or queues the test on the agent (202
 * `{runId}`). Delete is direct.
 */
export default function IntegrationConnectionsPage() {
  return (
    <div>
      <PageHeader
        crumbs={CRUMBS}
        title="Connections"
        subtitle="Where your bank's systems are"
      />
      <RequireIntegrationAdmin>
        <Connections />
      </RequireIntegrationAdmin>
    </div>
  );
}

function Connections() {
  const toast = useToast();
  const [connections, setConnections] = useState(null);
  const [agents, setAgents] = useState([]);
  const [error, setError] = useState('');
  const [wizard, setWizard] = useState(null); // { connection } | null
  const [sent, setSent] = useState(null);
  const [tests, setTests] = useState({});
  const [testing, setTesting] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    listConnections()
      .then((rows) => {
        setConnections(rows || []);
        setError('');
      })
      .catch((err) => setError(err.message));
    listAgents()
      .then((rows) => setAgents(rows || []))
      .catch(() => setAgents([]));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const runTest = async (connection) => {
    setTesting(connection.id);
    try {
      const result = await testConnection(connection.id);
      setTests((current) => ({ ...current, [connection.id]: result }));
    } catch (err) {
      setTests((current) => ({ ...current, [connection.id]: { ok: false, message: err.message } }));
    } finally {
      setTesting(null);
    }
  };

  const confirmDelete = async () => {
    setBusy(true);
    try {
      await toast.promise(deleteConnection(deleting.id), {
        loading: 'Deleting…',
        success: `Connection "${deleting.name}" deleted`,
      });
      setDeleting(null);
      load();
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const columns = [
    {
      key: 'name',
      label: 'Name',
      render: (row) => (
        <span className="flex flex-wrap items-center gap-1.5">
          <span className="font-medium">{row.name}</span>
          {row.pendingChangeId && <PendingTag changeId={row.pendingChangeId} />}
        </span>
      ),
    },
    { key: 'type', label: 'Type', render: (row) => CONNECTION_TYPE_LABELS[row.type] || row.type },
    {
      key: 'mode',
      label: 'Mode',
      render: (row) =>
        row.mode === 'AGENT' ? (
          <Tag tone="info">
            Agent {agents.find((a) => a.id === row.agentId)?.name || `#${row.agentId}`}
          </Tag>
        ) : (
          'Direct'
        ),
    },
    {
      key: 'target',
      label: 'Destination',
      render: (row) => <span className="break-all font-mono text-xs">{target(row)}</span>,
    },
    {
      key: 'secret',
      label: 'Credentials',
      render: (row) =>
        row.mode === 'AGENT'
          ? row.secretRef
            ? `Agent secret "${row.secretRef}"`
            : '—'
          : row.secretSet
            ? 'Secret set ✓'
            : 'None',
    },
    {
      key: 'status',
      label: 'Status',
      render: (row) => (
        <div>
          <StatusChip status={row.status} />
          {row.lastTestAt && (
            <p className="mt-0.5 text-xs text-muted">Tested {formatDateTime(row.lastTestAt)}</p>
          )}
          {tests[row.id] && (
            <div className="mt-1 max-w-80">
              <TestResult result={tests[row.id]} />
            </div>
          )}
        </div>
      ),
    },
    {
      key: 'actions',
      label: '',
      render: (row) => (
        <div className="flex flex-wrap justify-end gap-1">
          <Button
            size="sm"
            variant="secondary"
            onClick={() => runTest(row)}
            disabled={testing === row.id}
            aria-label={`Test ${row.name}`}
          >
            {testing === row.id ? 'Testing…' : 'Test'}
          </Button>
          <Button
            size="sm"
            variant="secondary"
            onClick={() => setWizard({ connection: row })}
            aria-label={`Edit ${row.name}`}
          >
            Edit
          </Button>
          <Button
            size="sm"
            variant="danger"
            onClick={() => setDeleting(row)}
            aria-label={`Delete ${row.name}`}
          >
            Delete
          </Button>
        </div>
      ),
    },
  ];

  return (
    <>
      <div className="mb-4 flex justify-end">
        <Button onClick={() => setWizard({ connection: null })}>New connection</Button>
      </div>
      <ErrorBanner message={error} onDismiss={() => setError('')} />
      <SentForApproval what={sent?.what} change={sent?.change} onDismiss={() => setSent(null)} />
      {!connections && !error && <Spinner label="Loading connections…" />}
      {connections && (
        <DataTable
          caption="Connections"
          columns={columns}
          rows={connections}
          emptyMessage="No connections yet. Add one to start receiving or sending data."
        />
      )}

      <ConnectionWizard
        open={Boolean(wizard)}
        connection={wizard?.connection || null}
        agents={agents}
        onClose={() => setWizard(null)}
        onSaved={(result, request) => {
          if (isPendingChange(result)) {
            setSent({
              what: `${wizard?.connection ? 'Changing' : 'Creating'} connection "${request.name}"`,
              change: result,
            });
          }
          setWizard(null);
          load();
        }}
      />

      <ConfirmDialog
        open={Boolean(deleting)}
        title={`Delete connection "${deleting?.name || ''}"`}
        tone="danger"
        confirmLabel="Delete"
        busy={busy}
        onConfirm={confirmDelete}
        onCancel={() => setDeleting(null)}
        message="Flows that use this connection stop working. Its stored credentials are destroyed."
      />
    </>
  );
}
