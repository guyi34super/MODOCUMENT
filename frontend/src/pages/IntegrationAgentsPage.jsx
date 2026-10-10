import { useCallback, useEffect, useId, useState } from 'react';
import { isPendingChange } from '../api/changes';
import { getAgentEnrolment, listAgents, registerAgent } from '../api/integrations';
import { useToast } from '../context/ToastContext';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import Modal from '../components/Modal';
import { Button, ErrorBanner, Field, Spinner, Tag, inputClass } from '../components/ui';
import {
  OneTimeSecret,
  RequireIntegrationAdmin,
  SentForApproval,
  StatusChip,
  formatDateTime,
} from './IntegrationsCommon';

/**
 * `/admin/integrations/agents` (ws26): on-prem connector agents. "Register
 * agent" goes to a second administrator (202); after approval the maker gets
 * the one-time enrolment token and `docker run` command (readable once,
 * 15 minutes) — shown with a countdown and wiped when the dialog closes.
 * Status: ONLINE / OFFLINE / PENDING_ENROLMENT, the version the agent last
 * reported and an "update available" badge from the release pipeline.
 */
export default function IntegrationAgentsPage() {
  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Integrations', to: '/admin/integrations' }, { label: 'Agents' }]}
        title="Connector agents"
        subtitle="Reach systems inside your network without opening inbound ports"
      />
      <RequireIntegrationAdmin>
        <Agents />
      </RequireIntegrationAdmin>
    </div>
  );
}

function Agents() {
  const toast = useToast();
  const formId = useId();
  const [agents, setAgents] = useState(null);
  const [error, setError] = useState('');
  const [registering, setRegistering] = useState(false);
  const [name, setName] = useState('');
  const [nameError, setNameError] = useState('');
  const [saving, setSaving] = useState(false);
  const [sent, setSent] = useState(null);
  // { agentName, token, expiresAt, dockerCommand } — shown once, cleared on close.
  const [enrolment, setEnrolment] = useState(null);
  const [busy, setBusy] = useState(null);

  const load = useCallback(() => {
    listAgents()
      .then((rows) => {
        setAgents(rows || []);
        setError('');
      })
      .catch((err) => setError(err.message));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const submit = async (event) => {
    event.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) {
      setNameError('Give the agent a name, e.g. the data centre it runs in.');
      return;
    }
    setSaving(true);
    setNameError('');
    try {
      const result = await registerAgent(trimmed);
      if (isPendingChange(result)) {
        toast.success(`Sent for approval (change #${result.pendingChangeId})`);
        setSent({ what: `Registering agent "${trimmed}"`, change: result });
      } else {
        toast.success(`Agent "${trimmed}" registered`);
      }
      setRegistering(false);
      setName('');
      load();
    } catch (err) {
      setNameError(err.message);
    } finally {
      setSaving(false);
    }
  };

  const enrol = async (agent) => {
    setBusy(agent.id);
    try {
      const result = await getAgentEnrolment(agent.id);
      setEnrolment({ agentName: agent.name, ...result });
    } catch (err) {
      toast.error(
        err.status === 404 || err.status === 410
          ? 'The enrolment command is no longer available (already shown, or expired).'
          : err.message
      );
    } finally {
      setBusy(null);
    }
  };

  const columns = [
    { key: 'name', label: 'Name', render: (row) => <span className="font-medium">{row.name}</span> },
    { key: 'status', label: 'Status', render: (row) => <StatusChip status={row.status} /> },
    {
      key: 'version',
      label: 'Version',
      render: (row) => (
        <span className="flex flex-wrap items-center gap-1.5">
          {row.version || '—'}
          {row.updateAvailable && (
            <Tag tone="warning" title={row.latestVersion ? `Latest: ${row.latestVersion}` : undefined}>
              Update available{row.latestVersion ? ` (${row.latestVersion})` : ''}
            </Tag>
          )}
        </span>
      ),
    },
    { key: 'lastHeartbeatAt', label: 'Last heartbeat', render: (row) => formatDateTime(row.lastHeartbeatAt) },
    {
      key: 'connections',
      label: 'Connections',
      render: (row) => (row.connections || []).length,
    },
    {
      key: 'actions',
      label: '',
      render: (row) =>
        row.status === 'PENDING_ENROLMENT' ? (
          <Button
            size="sm"
            onClick={() => enrol(row)}
            disabled={busy === row.id}
            aria-label={`Get enrolment command for ${row.name}`}
          >
            Get enrolment command
          </Button>
        ) : null,
    },
  ];

  return (
    <>
      <div className="mb-4 flex justify-end">
        <Button
          onClick={() => {
            setName('');
            setNameError('');
            setRegistering(true);
          }}
        >
          Register agent
        </Button>
      </div>
      <ErrorBanner message={error} onDismiss={() => setError('')} />
      <SentForApproval what={sent?.what} change={sent?.change} onDismiss={() => setSent(null)} />
      {!agents && !error && <Spinner label="Loading agents…" />}
      {agents && (
        <DataTable
          columns={columns}
          rows={agents}
          emptyMessage="No agents yet. Register one to reach databases, APIs or SFTP servers inside your network."
        />
      )}
      {agents && agents.some((a) => a.updateAvailable) && (
        <p className="mt-2 text-xs text-muted">
          To update, pull the new image and restart the container — agents never update themselves.
        </p>
      )}

      <Modal
        open={registering}
        onClose={() => !saving && setRegistering(false)}
        title="Register agent"
        description="A second administrator approves it; then you get a one-time enrolment command."
        footer={
          <>
            <Button type="button" variant="secondary" onClick={() => setRegistering(false)} disabled={saving}>
              Cancel
            </Button>
            <Button type="submit" form={formId} disabled={saving}>
              Send for approval
            </Button>
          </>
        }
      >
        <form id={formId} onSubmit={submit} noValidate className="space-y-3">
          <Field label="Agent name">
            <input
              className={inputClass}
              maxLength={200}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </Field>
          {nameError && (
            <p role="alert" className="text-sm text-danger-ink">
              {nameError}
            </p>
          )}
        </form>
      </Modal>

      <Modal
        open={Boolean(enrolment)}
        onClose={() => setEnrolment(null)}
        title={`Enrol "${enrolment?.agentName || ''}"`}
        description="Run the command on the host inside your network that will run the agent."
        size="lg"
        closeOnBackdrop={false}
        footer={
          <Button type="button" onClick={() => setEnrolment(null)}>
            Done
          </Button>
        }
      >
        {enrolment && (
          <OneTimeSecret
            expiresAt={enrolment.expiresAt}
            warning="The enrolment token works once and expires shortly. Copy the command now; it cannot be shown again."
            items={[
              { label: 'Docker command', value: String(enrolment.dockerCommand ?? ''), multiline: true },
              { label: 'Enrolment token', value: String(enrolment.token ?? '') },
            ]}
          />
        )}
      </Modal>
    </>
  );
}
