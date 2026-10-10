import { useCallback, useEffect, useState } from 'react';
import { isPendingChange } from '../api/changes';
import { listEgressRules, requestEgressRule, revokeEgressRule } from '../api/integrations';
import { useToast } from '../context/ToastContext';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import ConfirmDialog from '../components/ConfirmDialog';
import { Button, Card, ErrorBanner, Field, Spinner, inputClass } from '../components/ui';
import {
  RequireIntegrationAdmin,
  SentForApproval,
  StatusChip,
  formatDateTime,
} from './IntegrationsCommon';

const EMPTY = { kind: 'host', host: '', cidr: '', port: '', reason: '' };
const HOST = /^(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$/;
const CIDR = /^[0-9a-fA-F:.]+\/\d{1,3}$/;

/**
 * `/admin/integrations/egress-rules` (ws25): private destinations (VPN /
 * PrivateLink addresses) a DIRECT connection may reach. Public hosts need
 * no rule. A request waits for the platform owner's approval
 * (EGRESS_RULE_APPROVE); revoking is immediate.
 */
export default function IntegrationEgressRulesPage() {
  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Integrations', to: '/admin/integrations' }, { label: 'Egress rules' }]}
        title="Egress destinations"
        subtitle="Private addresses your Direct connections may reach"
      />
      <RequireIntegrationAdmin>
        <EgressRules />
      </RequireIntegrationAdmin>
    </div>
  );
}

function EgressRules() {
  const toast = useToast();
  const [rules, setRules] = useState(null);
  const [error, setError] = useState('');
  const [form, setForm] = useState(EMPTY);
  const [formError, setFormError] = useState('');
  const [saving, setSaving] = useState(false);
  const [sent, setSent] = useState(null);
  const [revoking, setRevoking] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    listEgressRules()
      .then((rows) => {
        setRules(rows || []);
        setError('');
      })
      .catch((err) => setError(err.message));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const submit = async (event) => {
    event.preventDefault();
    const host = form.kind === 'host' ? form.host.trim() : '';
    const cidr = form.kind === 'cidr' ? form.cidr.trim() : '';
    if (form.kind === 'host' && !HOST.test(host)) return setFormError('Enter a host name or IP address.');
    if (form.kind === 'cidr' && !CIDR.test(cidr)) return setFormError('Enter a CIDR range, e.g. 10.20.0.0/24.');
    const port = Number(form.port);
    if (!Number.isInteger(port) || port < 1 || port > 65535) return setFormError('Enter a port between 1 and 65535.');
    if (!form.reason.trim()) return setFormError('Say why: the platform owner reads it.');
    setFormError('');
    setSaving(true);
    try {
      const result = await requestEgressRule({ host, cidr, port, reason: form.reason.trim() });
      const what = `${host || cidr}:${port}`;
      if (isPendingChange(result)) {
        toast.success(`Sent for approval (change #${result.pendingChangeId})`);
        setSent({ what: `Allowing ${what}`, change: result });
      } else {
        toast.success(`Requested ${what} — the platform owner must approve it`);
      }
      setForm(EMPTY);
      load();
    } catch (err) {
      setFormError(err.message);
    } finally {
      setSaving(false);
    }
  };

  const confirmRevoke = async () => {
    setBusy(true);
    try {
      await toast.promise(revokeEgressRule(revoking.id), {
        loading: 'Revoking…',
        success: 'Destination revoked',
      });
      setRevoking(null);
      load();
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const columns = [
    {
      key: 'destination',
      label: 'Destination',
      render: (row) => (
        <span className="font-mono text-xs">
          {row.host || row.cidr}
          {row.port ? `:${row.port}` : ''}
        </span>
      ),
    },
    { key: 'status', label: 'Status', render: (row) => <StatusChip status={row.status} /> },
    { key: 'reason', label: 'Reason', render: (row) => row.reason || '' },
    { key: 'requestedBy', label: 'Requested by', render: (row) => row.requestedBy || '—' },
    { key: 'approvedBy', label: 'Approved by', render: (row) => row.approvedBy || '—' },
    { key: 'createdAt', label: 'Requested', render: (row) => formatDateTime(row.createdAt) },
    {
      key: 'actions',
      label: '',
      render: (row) =>
        row.status !== 'REVOKED' ? (
          <Button
            size="sm"
            variant="danger"
            onClick={() => setRevoking(row)}
            aria-label={`Revoke ${row.host || row.cidr}`}
          >
            Revoke
          </Button>
        ) : null,
    },
  ];

  return (
    <>
      <Card className="mb-4">
        <h2 className="mb-3 text-base font-medium">Request a destination</h2>
        <form onSubmit={submit} noValidate className="space-y-3">
          <fieldset>
            <legend className="sr-only">Destination kind</legend>
            <div className="flex gap-4 text-sm">
              <label className="flex items-center gap-1.5">
                <input
                  type="radio"
                  name="egress-kind"
                  checked={form.kind === 'host'}
                  onChange={() => setForm({ ...form, kind: 'host' })}
                />
                Host
              </label>
              <label className="flex items-center gap-1.5">
                <input
                  type="radio"
                  name="egress-kind"
                  checked={form.kind === 'cidr'}
                  onChange={() => setForm({ ...form, kind: 'cidr' })}
                />
                CIDR range
              </label>
            </div>
          </fieldset>
          <div className="grid gap-3 sm:grid-cols-[1fr_8rem]">
            {form.kind === 'host' ? (
              <Field label="Host">
                <input
                  className={inputClass}
                  value={form.host}
                  placeholder="db.internal.bank.example"
                  onChange={(e) => setForm({ ...form, host: e.target.value })}
                />
              </Field>
            ) : (
              <Field label="CIDR">
                <input
                  className={inputClass}
                  value={form.cidr}
                  placeholder="10.20.0.0/24"
                  onChange={(e) => setForm({ ...form, cidr: e.target.value })}
                />
              </Field>
            )}
            <Field label="Port">
              <input
                inputMode="numeric"
                className={inputClass}
                value={form.port}
                onChange={(e) => setForm({ ...form, port: e.target.value })}
              />
            </Field>
          </div>
          <Field label="Reason">
            <input
              className={inputClass}
              maxLength={500}
              value={form.reason}
              onChange={(e) => setForm({ ...form, reason: e.target.value })}
            />
          </Field>
          {formError && (
            <p role="alert" className="text-sm text-danger-ink">
              {formError}
            </p>
          )}
          <Button type="submit" disabled={saving}>
            {saving ? 'Requesting…' : 'Request destination'}
          </Button>
        </form>
      </Card>
      <ErrorBanner message={error} onDismiss={() => setError('')} />
      <SentForApproval what={sent?.what} change={sent?.change} onDismiss={() => setSent(null)} />
      {!rules && !error && <Spinner label="Loading destinations…" />}
      {rules && (
        <DataTable columns={columns} rows={rules} emptyMessage="No private destinations requested." />
      )}

      <ConfirmDialog
        open={Boolean(revoking)}
        title="Revoke destination"
        tone="danger"
        confirmLabel="Revoke"
        busy={busy}
        onConfirm={confirmRevoke}
        onCancel={() => setRevoking(null)}
        message={`Direct connections can no longer reach ${revoking?.host || revoking?.cidr || ''}${
          revoking?.port ? `:${revoking.port}` : ''
        }. This applies immediately.`}
      />
    </>
  );
}
