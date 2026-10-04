import { useCallback, useEffect, useState } from 'react';
import * as adminApi from '../api/admin';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import PageHeader from '../components/PageHeader';
import { Button, Card, ErrorBanner, Field, Spinner, inputClass } from '../components/ui';

const ROLES = ['ADMIN', 'APPROVER', 'READ_WRITE', 'READ_ONLY'];

const emptyForm = { email: '', displayName: '', role: 'READ_ONLY', temporaryPassword: '' };

function formatDate(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

/**
 * Bank-scoped user administration (ws09): the server enforces everything —
 * this page only exists for ADMINs of the bank and renders their own bank's
 * users. Role changes and the enable/disable (leaver) switch always confirm,
 * because both end the target's sessions immediately.
 */
export default function AdminUsersPage() {
  const toast = useToast();
  const { user: me } = useAuth();

  const [users, setUsers] = useState(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState(emptyForm);
  // 'users' | 'review' — the access-review worksheet view (SEC-013).
  const [view, setView] = useState('users');
  const [review, setReview] = useState(null);

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

  const submitCreate = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      await toast.promise(adminApi.createUser(form), {
        loading: 'Creating user…',
        success: `User ${form.email} created — they must change the temporary password on first login`,
      });
      setForm(emptyForm);
      setShowCreate(false);
      load();
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const changeRole = async (target, role) => {
    if (role === target.role) return;
    if (
      !window.confirm(
        `Change ${target.email} from ${target.role} to ${role}? ` +
          'Their active sessions will be signed out immediately.'
      )
    ) {
      return;
    }
    try {
      await toast.promise(adminApi.setUserRole(target.id, role), {
        loading: 'Changing role…',
        success: `${target.email} is now ${role}`,
      });
      load();
    } catch {
      // The error toast is already shown.
    }
  };

  const toggleDisabled = async (target) => {
    const disabling = !target.disabled;
    const question = disabling
      ? `Disable ${target.email}? The account becomes unusable immediately: ` +
        'their sessions end and they can no longer sign in.'
      : `Enable ${target.email}? They will be able to sign in again.`;
    if (!window.confirm(question)) return;
    try {
      await toast.promise(adminApi.setUserDisabled(target.id, disabling), {
        loading: disabling ? 'Disabling…' : 'Enabling…',
        success: `${target.email} ${disabling ? 'disabled' : 'enabled'}`,
      });
      load();
    } catch {
      // The error toast is already shown.
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

      {showCreate && (
        <Card className="mb-5">
          <h2 className="mb-3 text-lg font-normal">Create user</h2>
          <form onSubmit={submitCreate} className="space-y-4">
            <Field label="Email">
              <input
                type="email"
                required
                className={inputClass}
                value={form.email}
                onChange={(e) => setForm({ ...form, email: e.target.value })}
              />
            </Field>
            <Field label="Display name">
              <input
                className={inputClass}
                value={form.displayName}
                onChange={(e) => setForm({ ...form, displayName: e.target.value })}
              />
            </Field>
            <Field label="Role" hint="Least privilege: pick the lowest role that does the job.">
              <select
                className={inputClass}
                value={form.role}
                onChange={(e) => setForm({ ...form, role: e.target.value })}
              >
                {ROLES.map((role) => (
                  <option key={role} value={role}>
                    {role}
                  </option>
                ))}
              </select>
            </Field>
            <Field
              label="Temporary password"
              hint="At least 12 characters. The user must replace it on first login."
            >
              <input
                type="password"
                required
                minLength={12}
                className={inputClass}
                value={form.temporaryPassword}
                onChange={(e) => setForm({ ...form, temporaryPassword: e.target.value })}
              />
            </Field>
            <Button type="submit" disabled={busy}>
              {busy ? 'Creating…' : 'Create user'}
            </Button>
          </form>
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
                            onChange={(e) => changeRole(row, e.target.value)}
                          >
                            {ROLES.map((role) => (
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
                      </td>
                      <td className="px-2 py-2 text-muted">{formatDate(row.lastLoginAt)}</td>
                      <td className="px-2 py-2 text-muted">{row.mfaEnabled ? 'On' : 'Off'}</td>
                      <td className="px-2 py-2 text-right">
                        {!self && (
                          <Button
                            variant={row.disabled ? 'secondary' : 'danger'}
                            size="sm"
                            onClick={() => toggleDisabled(row)}
                          >
                            {row.disabled ? 'Enable' : 'Disable'}
                          </Button>
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
    </div>
  );
}
