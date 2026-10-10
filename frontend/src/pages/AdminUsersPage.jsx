import { useCallback, useEffect, useId, useState } from 'react';
import { Link } from 'react-router-dom';
import * as adminApi from '../api/admin';
import { isPendingChange } from '../api/changes';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import PageHeader from '../components/PageHeader';
import Modal from '../components/Modal';
import ConfirmDialog from '../components/ConfirmDialog';
import CreateUserForm, { rolesFor } from '../components/CreateUserForm';
import { Button, Card, ErrorBanner, Field, Spinner, inputClass } from '../components/ui';
import { generateTemporaryPassword } from '../utils/temporaryPassword';

function formatDate(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

/** The toast line for an action that may come back as a pending change. */
function pendingOr(result, pendingText, directText) {
  return isPendingChange(result)
    ? `Sent for approval: ${pendingText} (change #${result.pendingChangeId})`
    : directText;
}

/**
 * Bank-scoped user administration (ws09): the server enforces everything —
 * this page only exists for ADMINs of the bank and renders their own bank's
 * users. Creation, role changes and the DISABLE switch are maker/checker
 * gated (ws10): the server answers 202 with a pending change that a second
 * administrator must approve, and this page says so instead of pretending
 * the change happened. Only re-enabling applies immediately.
 *
 * Phase 2 (ws22) row actions, each in its own dialog: Unlock (direct),
 * Reset password and Reset MFA (maker/checker — "Sent for approval"), and
 * Edit (display name, direct). None is offered on the caller's own row; the
 * password reset is hidden for IdP-managed (SSO) accounts.
 */
export default function AdminUsersPage() {
  const toast = useToast();
  const { user: me } = useAuth();
  const passwordFieldId = useId();

  const [users, setUsers] = useState(null);
  const [error, setError] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const roles = rolesFor(me?.role);
  // 'users' | 'review' — the access-review worksheet view (SEC-013).
  const [view, setView] = useState('users');
  const [review, setReview] = useState(null);

  // The open row dialog: { kind, user, role? } with kind one of
  // role | disable | unlock | password | mfa | edit.
  const [dialog, setDialog] = useState(null);
  const [saving, setSaving] = useState(false);
  const [temporaryPassword, setTemporaryPassword] = useState('');
  const [mfaReason, setMfaReason] = useState('');
  const [displayName, setDisplayName] = useState('');

  const load = useCallback(() => {
    adminApi
      .listUsers()
      .then(setUsers)
      .catch((err) => setError(err.message));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const openReview = async () => {
    setView('review');
    try {
      setReview(await adminApi.accessReview());
    } catch (err) {
      setError(err.message);
    }
  };

  const openDialog = (kind, target, extra = {}) => {
    setTemporaryPassword('');
    setMfaReason('');
    setDisplayName(target.displayName || '');
    setDialog({ kind, user: target, ...extra });
  };

  const closeDialog = () => {
    if (!saving) setDialog(null);
  };

  /**
   * Runs one dialog action behind a toast. A refusal keeps a form dialog
   * open (fix the input and retry); a confirmation closes either way.
   */
  const runAction = async (call, messages, { keepOpenOnError = false } = {}) => {
    setSaving(true);
    try {
      await toast.promise(call, messages);
      setDialog(null);
      load();
    } catch {
      // The error toast is already shown.
      if (!keepOpenOnError) setDialog(null);
    } finally {
      setSaving(false);
    }
  };

  const submitRole = () => {
    const { user: target, role } = dialog;
    return runAction(adminApi.setUserRole(target.id, role), {
      loading: 'Submitting role change…',
      success: (result) =>
        isPendingChange(result)
          ? `Role change for ${target.email} submitted for approval (change #${result.pendingChangeId})`
          : `${target.email} is now ${role}`,
    });
  };

  const submitDisabled = () => {
    const target = dialog.user;
    const disabling = !target.disabled;
    return runAction(adminApi.setUserDisabled(target.id, disabling), {
      loading: disabling ? 'Submitting disable request…' : 'Enabling…',
      success: (result) =>
        isPendingChange(result)
          ? `Disabling ${target.email} submitted for approval (change #${result.pendingChangeId})`
          : `${target.email} ${disabling ? 'disabled' : 'enabled'}`,
    });
  };

  const submitUnlock = () => {
    const target = dialog.user;
    return runAction(adminApi.unlockUser(target.id), {
      loading: 'Unlocking…',
      success: `${target.email} unlocked — they can sign in again`,
    });
  };

  const submitPasswordReset = (e) => {
    e?.preventDefault();
    if (!temporaryPassword) return undefined;
    const target = dialog.user;
    return runAction(
      adminApi.resetUserPassword(target.id, temporaryPassword),
      {
        loading: 'Submitting password reset…',
        success: (result) =>
          pendingOr(
            result,
            `password reset for ${target.email}`,
            `Password of ${target.email} reset`
          ),
      },
      { keepOpenOnError: true }
    );
  };

  const submitMfaReset = (e) => {
    e?.preventDefault();
    if (!mfaReason.trim()) return undefined;
    const target = dialog.user;
    return runAction(
      adminApi.resetUserMfa(target.id, mfaReason.trim()),
      {
        loading: 'Submitting MFA reset…',
        success: (result) =>
          pendingOr(result, `MFA reset for ${target.email}`, `MFA of ${target.email} reset`),
      },
      { keepOpenOnError: true }
    );
  };

  const submitEdit = (e) => {
    e?.preventDefault();
    if (!displayName.trim()) return undefined;
    const target = dialog.user;
    return runAction(
      adminApi.updateUser(target.id, { displayName: displayName.trim() }),
      { loading: 'Saving…', success: `Display name of ${target.email} updated` },
      { keepOpenOnError: true }
    );
  };

  const copyPassword = async () => {
    try {
      await navigator.clipboard.writeText(temporaryPassword);
      toast.success('Temporary password copied');
    } catch {
      toast.error('Copy is not available here — select the password and copy it manually.');
    }
  };

  const exportCsv = async () => {
    try {
      await toast.promise(adminApi.exportUsers(), {
        loading: 'Preparing export…',
        success: 'User list downloaded',
      });
    } catch {
      // The error toast is already shown.
    }
  };

  const target = dialog?.user;
  const cancelButton = (
    <Button type="button" variant="secondary" onClick={closeDialog} disabled={saving}>
      Cancel
    </Button>
  );

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Administration' }, { label: 'Users' }]}
        title="Users"
        subtitle="Accounts of your bank"
        actions={
          <>
            {view === 'users' ? (
              <Button variant="secondary" onClick={openReview}>
                Access review
              </Button>
            ) : (
              <Button variant="secondary" onClick={() => setView('users')}>
                Back to users
              </Button>
            )}
            <Button variant="secondary" onClick={exportCsv}>
              Export CSV
            </Button>
            <Button onClick={() => setShowCreate((open) => !open)}>
              {showCreate ? 'Close' : 'Create user'}
            </Button>
          </>
        }
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />
      <p className="mb-4 text-sm text-muted">
        Creating, re-roling or disabling a user and resetting a password or MFA need a second
        administrator&apos;s approval (maker/checker) — track requests under{' '}
        <Link to="/admin/changes" className="text-accent hover:underline">
          Approvals
        </Link>
        .
      </p>

      {showCreate && (
        <Card className="mb-5">
          <h2 className="mb-3 text-lg font-normal">Create user</h2>
          <CreateUserForm
            roles={roles}
            onSubmitted={() => {
              setShowCreate(false);
              load();
            }}
          />
        </Card>
      )}

      {view === 'users' && (
        <Card padding="compact">
          {!users && <Spinner label="Loading users…" />}
          {users && (
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-left text-xs uppercase tracking-wide text-muted">
                  <th className="px-2 py-2">Email</th>
                  <th className="px-2 py-2">Name</th>
                  <th className="px-2 py-2">Role</th>
                  <th className="px-2 py-2">Status</th>
                  <th className="px-2 py-2">Last login</th>
                  <th className="px-2 py-2">MFA</th>
                  <th className="px-2 py-2" />
                </tr>
              </thead>
              <tbody>
                {users.map((row) => {
                  const self = me && row.id === me.userId;
                  return (
                    <tr key={row.id} className="border-b border-line/60 last:border-0">
                      <td className="px-2 py-2">
                        {row.email}
                        {self && <span className="ml-1 text-xs text-muted">(you)</span>}
                      </td>
                      <td className="px-2 py-2">{row.displayName || '—'}</td>
                      <td className="px-2 py-2">
                        {self ? (
                          row.role
                        ) : (
                          <select
                            aria-label={`Role of ${row.email}`}
                            className="rounded border border-line px-1.5 py-0.5 text-sm"
                            value={row.role || ''}
                            onChange={(e) => {
                              if (e.target.value !== row.role) {
                                openDialog('role', row, { role: e.target.value });
                              }
                            }}
                          >
                            {roles.map((role) => (
                              <option key={role} value={role}>
                                {role}
                              </option>
                            ))}
                          </select>
                        )}
                      </td>
                      <td className="px-2 py-2">
                        <span
                          className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${
                            row.disabled
                              ? 'bg-danger-bg text-danger-ink'
                              : 'bg-success-bg text-success-ink'
                          }`}
                        >
                          {row.disabled ? 'Disabled' : 'Active'}
                        </span>
                        {row.locked && (
                          <span
                            className="ml-1 inline-block rounded-full bg-amber-100 px-2.5 py-0.5 text-xs font-medium text-amber-800"
                            title={`Locked until ${formatDate(row.lockedUntil)}`}
                          >
                            Locked
                          </span>
                        )}
                      </td>
                      <td className="px-2 py-2 text-muted">{formatDate(row.lastLoginAt)}</td>
                      <td className="px-2 py-2 text-muted">{row.mfaEnabled ? 'On' : 'Off'}</td>
                      <td className="px-2 py-2">
                        {!self && (
                          <div className="flex flex-wrap justify-end gap-1.5">
                            <Button
                              variant="secondary"
                              size="sm"
                              aria-label={`Edit ${row.email}`}
                              onClick={() => openDialog('edit', row)}
                            >
                              Edit
                            </Button>
                            {row.locked && (
                              <Button
                                variant="secondary"
                                size="sm"
                                aria-label={`Unlock ${row.email}`}
                                onClick={() => openDialog('unlock', row)}
                              >
                                Unlock
                              </Button>
                            )}
                            {!row.authProvider && (
                              <Button
                                variant="secondary"
                                size="sm"
                                aria-label={`Reset password of ${row.email}`}
                                onClick={() => openDialog('password', row)}
                              >
                                Reset password
                              </Button>
                            )}
                            {row.mfaEnabled && (
                              <Button
                                variant="secondary"
                                size="sm"
                                aria-label={`Reset MFA of ${row.email}`}
                                onClick={() => openDialog('mfa', row)}
                              >
                                Reset MFA
                              </Button>
                            )}
                            <Button
                              variant={row.disabled ? 'secondary' : 'danger'}
                              size="sm"
                              onClick={() => openDialog('disable', row)}
                            >
                              {row.disabled ? 'Enable' : 'Disable'}
                            </Button>
                          </div>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </Card>
      )}

      {view === 'review' && (
        <Card padding="compact">
          <p className="px-2 pt-2 text-sm text-muted">
            Quarterly access review: confirm every account still needs its role.
            Rows flagged <span className="font-medium text-danger-ink">inactive 90d+</span> are
            disable-by-default unless justified.
          </p>
          {!review && <Spinner label="Generating worksheet…" />}
          {review && (
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-left text-xs uppercase tracking-wide text-muted">
                  <th className="px-2 py-2">Email</th>
                  <th className="px-2 py-2">Role</th>
                  <th className="px-2 py-2">Status</th>
                  <th className="px-2 py-2">MFA</th>
                  <th className="px-2 py-2">Last login</th>
                  <th className="px-2 py-2">Days since</th>
                  <th className="px-2 py-2">Inactivity</th>
                </tr>
              </thead>
              <tbody>
                {review.map((row) => (
                  <tr
                    key={row.id}
                    className={`border-b border-line/60 last:border-0 ${
                      row.inactive90d ? 'bg-danger-bg/40' : ''
                    }`}
                  >
                    <td className="px-2 py-2">{row.email}</td>
                    <td className="px-2 py-2">{row.role}</td>
                    <td className="px-2 py-2">{row.disabled ? 'Disabled' : 'Active'}</td>
                    <td className="px-2 py-2 text-muted">{row.mfaEnabled ? 'On' : 'Off'}</td>
                    <td className="px-2 py-2 text-muted">{formatDate(row.lastLoginAt)}</td>
                    <td className="px-2 py-2 text-muted">
                      {row.daysSinceLastLogin == null ? 'never' : row.daysSinceLastLogin}
                    </td>
                    <td className="px-2 py-2">
                      {row.inactive90d && (
                        <span className="inline-block rounded-full bg-danger-bg px-2.5 py-0.5 text-xs font-medium text-danger-ink">
                          inactive 90d+
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      )}

      {/* ---------------------------------------------------- row dialogs */}

      <ConfirmDialog
        open={dialog?.kind === 'role'}
        title="Change role"
        confirmLabel="Request change"
        busy={saving}
        onConfirm={submitRole}
        onCancel={closeDialog}
      >
        <p>
          Request changing <strong>{target?.email}</strong> from {target?.role} to{' '}
          {dialog?.role}? A second administrator must approve it; on approval the user is
          signed out immediately.
        </p>
      </ConfirmDialog>

      <ConfirmDialog
        open={dialog?.kind === 'disable'}
        title={target?.disabled ? 'Enable user' : 'Disable user'}
        confirmLabel={target?.disabled ? 'Enable' : 'Request disable'}
        tone={target?.disabled ? 'default' : 'danger'}
        busy={saving}
        onConfirm={submitDisabled}
        onCancel={closeDialog}
      >
        {target?.disabled ? (
          <p>
            Enable <strong>{target?.email}</strong>? They will be able to sign in again.
          </p>
        ) : (
          <p>
            Request disabling <strong>{target?.email}</strong>? A second administrator must
            approve it; on approval the account becomes unusable immediately.
          </p>
        )}
      </ConfirmDialog>

      <ConfirmDialog
        open={dialog?.kind === 'unlock'}
        title="Unlock account"
        confirmLabel="Unlock"
        busy={saving}
        onConfirm={submitUnlock}
        onCancel={closeDialog}
      >
        <p>
          Unlock <strong>{target?.email}</strong>? The login lockout ends now (it would
          otherwise last until {formatDate(target?.lockedUntil)}) and the failed-attempt
          counters reset. The password does not change.
        </p>
      </ConfirmDialog>

      <Modal
        open={dialog?.kind === 'password'}
        onClose={closeDialog}
        title="Reset password"
        description={target?.email}
        footer={
          <>
            {cancelButton}
            <Button
              type="button"
              onClick={submitPasswordReset}
              disabled={saving || !temporaryPassword}
            >
              Request reset
            </Button>
          </>
        }
      >
        <form onSubmit={submitPasswordReset} className="space-y-3">
          <p className="text-muted">
            A second administrator must approve the reset. On approval every session of the user
            ends and they must choose a new password at their next sign-in.
          </p>
          <div>
            <label htmlFor={passwordFieldId} className="mb-1 block font-medium text-ink">
              Temporary password
            </label>
            <div className="flex gap-2">
              <input
                id={passwordFieldId}
                type="text"
                required
                minLength={12}
                autoComplete="off"
                spellCheck={false}
                className={`${inputClass} font-mono`}
                value={temporaryPassword}
                onChange={(e) => setTemporaryPassword(e.target.value)}
              />
              <Button
                type="button"
                variant="secondary"
                onClick={() => setTemporaryPassword(generateTemporaryPassword())}
              >
                Generate
              </Button>
              <Button
                type="button"
                variant="secondary"
                onClick={copyPassword}
                disabled={!temporaryPassword}
              >
                Copy
              </Button>
            </div>
            <p className="mt-1 text-xs text-muted">
              At least 12 characters. Hand it to the user over a separate channel — it is not
              shown again.
            </p>
          </div>
        </form>
      </Modal>

      <Modal
        open={dialog?.kind === 'mfa'}
        onClose={closeDialog}
        title="Reset MFA"
        description={target?.email}
        footer={
          <>
            {cancelButton}
            <Button
              type="button"
              variant="danger"
              onClick={submitMfaReset}
              disabled={saving || !mfaReason.trim()}
            >
              Request MFA reset
            </Button>
          </>
        }
      >
        <form onSubmit={submitMfaReset} className="space-y-3">
          <p className="text-muted">
            Removes the user&apos;s authenticator enrolment and recovery codes. A second
            administrator must approve it; on approval the user is signed out and sets up MFA
            again at the next sign-in (administrators are required to).
          </p>
          <Field label="Reason" hint="Shown to the approver and kept in the audit trail.">
            <textarea
              required
              maxLength={500}
              rows={3}
              className={inputClass}
              value={mfaReason}
              onChange={(e) => setMfaReason(e.target.value)}
            />
          </Field>
        </form>
      </Modal>

      <Modal
        open={dialog?.kind === 'edit'}
        onClose={closeDialog}
        title="Edit user"
        description={target?.email}
        footer={
          <>
            {cancelButton}
            <Button type="button" onClick={submitEdit} disabled={saving || !displayName.trim()}>
              Save
            </Button>
          </>
        }
      >
        <form onSubmit={submitEdit} className="space-y-3">
          <Field label="Display name">
            <input
              required
              maxLength={200}
              className={inputClass}
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
            />
          </Field>
          <p className="text-xs text-muted">
            The email address is the sign-in identity and cannot be changed.
          </p>
        </form>
      </Modal>
    </div>
  );
}
