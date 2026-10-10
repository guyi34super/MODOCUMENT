import { useCallback, useEffect, useId, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { addBankAdmin, getBank, reactivateBank, suspendBank } from '../api/platform';
import { isPendingChange } from '../api/changes';
import { useToast } from '../context/ToastContext';
import PageHeader from '../components/PageHeader';
import Modal from '../components/Modal';
import ConfirmDialog from '../components/ConfirmDialog';
import {
  Button,
  Callout,
  ErrorBanner,
  Field,
  KpiTile,
  Spinner,
  inputClass,
} from '../components/ui';
import {
  BankStatusBadge,
  TemporaryPasswordInput,
  formatDateTime,
  formatPercent,
} from './PlatformCommon';

const emptyAdmin = { email: '', displayName: '', temporaryPassword: '', reason: '' };

/**
 * One bank in the platform console (ws21, `/platform/banks/:id`): its
 * counts-only figures and the platform owner's actions on it.
 *
 * - Suspend bank — maker/checker (BANK_SUSPEND, 202 "Sent for approval"); on
 *   approval every session of the bank's users ends and nobody can sign in.
 * - Reactivate bank — direct; the users sign in afresh.
 * - Add admin — break-glass (BANK_ADMIN_ADD, 202): a new ADMIN for a bank
 *   that lost its administrators.
 *
 * None of these is offered for the platform bank itself.
 */
export default function PlatformBankDetailPage() {
  const { id } = useParams();
  const toast = useToast();
  const reasonId = useId();
  const adminFormId = useId();

  const [bank, setBank] = useState(null);
  const [error, setError] = useState('');
  // 'suspend' | 'reactivate' | 'admin' | null
  const [dialog, setDialog] = useState(null);
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState('');
  const [admin, setAdmin] = useState(emptyAdmin);
  const [saving, setSaving] = useState(false);
  const [pending, setPending] = useState(null);

  const load = useCallback(() => {
    getBank(id)
      .then(setBank)
      .catch((err) => setError(err.message));
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  const openDialog = (kind) => {
    setReason('');
    setReasonError('');
    setAdmin(emptyAdmin);
    setDialog(kind);
  };

  const closeDialog = () => {
    if (!saving) setDialog(null);
  };

  const run = async (call, messages, onDone) => {
    setSaving(true);
    try {
      const result = await toast.promise(call, messages);
      setDialog(null);
      onDone?.(result);
    } catch {
      // The error toast is already shown.
    } finally {
      setSaving(false);
    }
  };

  const confirmSuspend = () => {
    if (!reason.trim()) {
      setReasonError('A reason is required: the approving administrator reads it.');
      return;
    }
    run(
      suspendBank(bank.bankId, reason.trim()),
      {
        loading: 'Submitting the suspension…',
        success: (result) =>
          isPendingChange(result)
            ? `Sent for approval: suspending ${bank.code} (change #${result.pendingChangeId})`
            : `${bank.code} suspended`,
      },
      (result) => setPending({ what: `Suspending ${bank.code}`, change: result })
    );
  };

  const confirmReactivate = () =>
    run(
      reactivateBank(bank.bankId),
      { loading: 'Reactivating…', success: `${bank.code} reactivated — its users can sign in again` },
      (updated) => {
        setPending(null);
        setBank(updated);
      }
    );

  const submitAdmin = (event) => {
    event.preventDefault();
    run(
      addBankAdmin(bank.bankId, admin),
      {
        loading: 'Submitting the break-glass request…',
        success: (result) =>
          isPendingChange(result)
            ? `Sent for approval: ${admin.email} as administrator of ${bank.code} (change #${result.pendingChangeId})`
            : `${admin.email} added`,
      },
      (result) =>
        setPending({ what: `Adding ${admin.email} as an administrator`, change: result })
    );
  };

  if (error && !bank) {
    return (
      <div>
        <PageHeader
          crumbs={[{ label: 'Platform', to: '/platform' }, { label: 'Banks', to: '/platform/banks' }]}
          title="Bank"
        />
        <ErrorBanner message={error} />
      </div>
    );
  }
  if (!bank) return <Spinner />;

  const customer = bank.kind === 'CUSTOMER';
  const suspended = bank.status === 'SUSPENDED';

  return (
    <div>
      <PageHeader
        crumbs={[
          { label: 'Platform', to: '/platform' },
          { label: 'Banks', to: '/platform/banks' },
          { label: bank.code },
        ]}
        title={`${bank.name} (${bank.code})`}
        actions={
          customer && (
            <>
              <Button variant="secondary" onClick={() => openDialog('admin')}>
                Add admin
              </Button>
              {suspended ? (
                <Button onClick={() => openDialog('reactivate')}>Reactivate bank</Button>
              ) : (
                <Button variant="danger" onClick={() => openDialog('suspend')}>
                  Suspend bank
                </Button>
              )}
            </>
          )
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      {pending && (
        <div className="mb-4">
          <Callout variant="success" title="Sent for approval">
            {pending.what} is change #{pending.change?.pendingChangeId}. A second platform
            administrator must approve it under{' '}
            <Link to="/admin/changes" className="underline">
              Approvals
            </Link>
            .
          </Callout>
        </div>
      )}
      {!customer && (
        <div className="mb-4">
          <Callout variant="info" title="This is the platform bank">
            It cannot be suspended. Manage platform administrators under{' '}
            <Link to="/admin/users" className="underline">
              Users
            </Link>
            .
          </Callout>
        </div>
      )}

      <dl className="mb-6 grid gap-x-6 gap-y-2 text-sm sm:grid-cols-[10rem_1fr]">
        <dt className="text-muted">Status</dt>
        <dd>
          <BankStatusBadge status={bank.status} />
        </dd>
        <dt className="text-muted">Kind</dt>
        <dd>{customer ? 'Customer bank' : 'Platform bank'}</dd>
        <dt className="text-muted">Last sign-in</dt>
        <dd>{formatDateTime(bank.lastActivityAt)}</dd>
      </dl>

      <div className="flex flex-wrap gap-3">
        <KpiTile label="Users" value={bank.users} hint="Enabled accounts" />
        <KpiTile label="MFA enrolled" value={formatPercent(bank.mfaEnrolledPct)} />
        <KpiTile label="Jobs (7 days)" value={bank.jobs7d} />
        <KpiTile
          label="Failures (7 days)"
          value={bank.failures7d}
          tone={bank.failures7d > 0 ? 'warning' : 'neutral'}
        />
      </div>

      <ConfirmDialog
        open={dialog === 'suspend'}
        title={`Suspend ${bank.code}`}
        tone="danger"
        confirmLabel="Request suspension"
        busy={saving}
        onConfirm={confirmSuspend}
        onCancel={closeDialog}
      >
        <p className="mb-3">
          A second platform administrator must approve it. On approval every session of the
          bank's users ends and nobody in the bank can sign in until it is reactivated.
        </p>
        <label htmlFor={reasonId} className="mb-1 block font-medium text-ink">
          Reason
        </label>
        <textarea
          id={reasonId}
          rows={3}
          maxLength={500}
          className={inputClass}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
        />
        {reasonError && (
          <p role="alert" className="mt-1 text-xs text-danger-ink">
            {reasonError}
          </p>
        )}
      </ConfirmDialog>

      <ConfirmDialog
        open={dialog === 'reactivate'}
        title={`Reactivate ${bank.code}`}
        confirmLabel="Reactivate"
        busy={saving}
        onConfirm={confirmReactivate}
        onCancel={closeDialog}
        message="The bank's users can sign in again straight away (sessions ended by the suspension stay ended)."
      />

      <Modal
        open={dialog === 'admin'}
        onClose={closeDialog}
        title="Add administrator (break-glass)"
        description="For a bank that has lost its administrators. A second platform administrator must approve it."
        closeOnBackdrop={false}
        footer={
          <>
            <Button type="button" variant="secondary" onClick={closeDialog} disabled={saving}>
              Cancel
            </Button>
            <Button type="submit" form={adminFormId} disabled={saving}>
              Send for approval
            </Button>
          </>
        }
      >
        <form id={adminFormId} onSubmit={submitAdmin} className="space-y-3">
          <Field label="Email">
            <input
              type="email"
              required
              maxLength={256}
              className={inputClass}
              value={admin.email}
              onChange={(e) => setAdmin({ ...admin, email: e.target.value })}
            />
          </Field>
          <Field label="Display name">
            <input
              maxLength={200}
              className={inputClass}
              value={admin.displayName}
              onChange={(e) => setAdmin({ ...admin, displayName: e.target.value })}
            />
          </Field>
          <div>
            <p className="mb-1 block font-medium text-ink">Temporary password</p>
            <TemporaryPasswordInput
              label="Temporary password"
              value={admin.temporaryPassword}
              onChange={(value) => setAdmin({ ...admin, temporaryPassword: value })}
            />
            <p className="mt-1 text-xs text-muted">
              Hand it to the person over a separate channel — it is not shown again. They must
              change it and enrol two-factor authentication at first sign-in.
            </p>
          </div>
          <Field label="Reason" hint="Why break-glass is needed; recorded in both banks' audit trails.">
            <textarea
              required
              rows={2}
              maxLength={500}
              className={inputClass}
              value={admin.reason}
              onChange={(e) => setAdmin({ ...admin, reason: e.target.value })}
            />
          </Field>
        </form>
      </Modal>
    </div>
  );
}
