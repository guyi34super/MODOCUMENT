import { useCallback, useEffect, useState } from 'react';
import { approveEgressRule, listPlatformEgressRules } from '../api/integrations';
import { isPendingChange } from '../api/changes';
import { useToast } from '../context/ToastContext';
import PageHeader from '../components/PageHeader';
import DataTable from '../components/DataTable';
import ConfirmDialog from '../components/ConfirmDialog';
import { Button, ErrorBanner, Spinner, inputClass } from '../components/ui';
import { PendingTag, SentForApproval, StatusChip, formatDateTime } from './IntegrationsCommon';

const STATUSES = ['PENDING', 'APPROVED', 'REVOKED'];

/**
 * `/platform/egress-rules` (ws25, platform console): the private
 * destinations banks asked their Direct connections to reach. Approving is
 * maker/checker (EGRESS_RULE_APPROVE → 202): a second platform administrator
 * decides it under Approvals.
 */
export default function PlatformEgressRulesPage() {
  const toast = useToast();
  const [status, setStatus] = useState('PENDING');
  const [rules, setRules] = useState(null);
  const [error, setError] = useState('');
  const [approving, setApproving] = useState(null);
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(null);

  const load = useCallback(() => {
    setRules(null);
    listPlatformEgressRules(status)
      .then((rows) => {
        setRules(rows || []);
        setError('');
      })
      .catch((err) => setError(err.message));
  }, [status]);

  useEffect(() => {
    load();
  }, [load]);

  const destination = (rule) => `${rule.host || rule.cidr}${rule.port ? `:${rule.port}` : ''}`;
  const bankOf = (rule) => rule.bankCode || rule.bankName || (rule.bankId ? `bank ${rule.bankId}` : '—');

  const confirmApprove = async () => {
    setBusy(true);
    try {
      const result = await toast.promise(approveEgressRule(approving.id), {
        loading: 'Submitting…',
        success: (r) =>
          isPendingChange(r)
            ? `Sent for approval (change #${r.pendingChangeId})`
            : `${destination(approving)} approved`,
      });
      if (isPendingChange(result)) {
        setSent({ what: `Allowing ${destination(approving)} for ${bankOf(approving)}`, change: result });
      }
      setApproving(null);
      load();
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const columns = [
    { key: 'bank', label: 'Bank', render: (row) => bankOf(row) },
    {
      key: 'destination',
      label: 'Destination',
      render: (row) => <span className="font-mono text-xs">{destination(row)}</span>,
    },
    { key: 'reason', label: 'Reason', render: (row) => row.reason || '' },
    { key: 'requestedBy', label: 'Requested by', render: (row) => row.requestedBy || '—' },
    { key: 'createdAt', label: 'Requested', render: (row) => formatDateTime(row.createdAt) },
    {
      key: 'status',
      label: 'Status',
      render: (row) => (
        <span className="flex flex-wrap items-center gap-1.5">
          <StatusChip status={row.status} />
          {row.pendingChangeId && <PendingTag changeId={row.pendingChangeId} />}
        </span>
      ),
    },
    {
      key: 'actions',
      label: '',
      render: (row) =>
        row.status === 'PENDING' && !row.pendingChangeId ? (
          <Button size="sm" onClick={() => setApproving(row)} aria-label={`Approve ${destination(row)}`}>
            Approve
          </Button>
        ) : null,
    },
  ];

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Platform', to: '/platform' }, { label: 'Egress rules' }]}
        title="Egress rules"
        subtitle="Private destinations banks asked to reach"
        actions={
          <>
            <select
              aria-label="Status filter"
              className={`${inputClass} w-auto`}
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              {STATUSES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
            <Button variant="secondary" onClick={load}>
              Refresh
            </Button>
          </>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />
      <SentForApproval what={sent?.what} change={sent?.change} onDismiss={() => setSent(null)} />
      {!rules && !error && <Spinner label="Loading egress rules…" />}
      {rules && (
        <DataTable columns={columns} rows={rules} emptyMessage={`No ${status.toLowerCase()} egress rules.`} />
      )}

      <ConfirmDialog
        open={Boolean(approving)}
        title="Approve egress destination"
        confirmLabel="Send for approval"
        busy={busy}
        onConfirm={confirmApprove}
        onCancel={() => setApproving(null)}
      >
        <p>
          Allow {approving ? bankOf(approving) : ''}'s Direct connections to reach{' '}
          <span className="font-mono">{approving ? destination(approving) : ''}</span>. A second
          platform administrator must approve it.
        </p>
        {approving?.reason && <p className="mt-2 text-muted">Reason: “{approving.reason}”</p>}
      </ConfirmDialog>
    </div>
  );
}
