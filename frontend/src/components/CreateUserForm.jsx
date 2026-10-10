import { useState } from 'react';
import * as adminApi from '../api/admin';
import { isPendingChange } from '../api/changes';
import { useToast } from '../context/ToastContext';
import { Button, Field, inputClass } from './ui';

export const BANK_ROLES = ['ADMIN', 'APPROVER', 'READ_WRITE', 'READ_ONLY'];

/** ws21: the platform bank holds platform administrators and nobody else. */
export const PLATFORM_ROLES = ['PLATFORM_ADMIN'];

/** The roles the caller's bank can hand out (the server enforces the same rule). */
export const rolesFor = (callerRole) =>
  callerRole === 'PLATFORM_ADMIN' ? PLATFORM_ROLES : BANK_ROLES;

/** A blank form defaulting to the least privileged role on offer. */
const blankForm = (roles, initial) => ({
  email: '',
  displayName: '',
  role: roles.at(-1),
  temporaryPassword: '',
  ...initial,
});

/**
 * The create-user form (ws09), shared by the Users page and the admin
 * dashboard's "Add user" dialog. Creation is maker/checker gated (ws10): the
 * server answers 202 with a pending change a second administrator must
 * approve, and the toast says so instead of pretending the user exists.
 *
 * `onSubmitted(result, form)` runs after a successful submission (the form
 * is then reset). `roles` are the roles on offer (default: the bank roles;
 * see `rolesFor`); `initial` pre-fills fields.
 */
export default function CreateUserForm({
  onSubmitted,
  initial,
  roles = BANK_ROLES,
  submitLabel = 'Create user',
  passwordHint = 'At least 12 characters. The user must replace it on first login.',
}) {
  const toast = useToast();
  const [form, setForm] = useState(() => blankForm(roles, initial));
  const [busy, setBusy] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    const submitted = form;
    try {
      const result = await toast.promise(adminApi.createUser(submitted), {
        loading: 'Submitting user creation…',
        success: (answer) =>
          isPendingChange(answer)
            ? `Creating ${submitted.email} was submitted for approval — a second administrator must approve it (change #${answer.pendingChangeId})`
            : `User ${submitted.email} created — they must change the temporary password on first login`,
      });
      setForm(blankForm(roles, initial));
      onSubmitted?.(result, submitted);
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} className="space-y-4">
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
          {roles.map((role) => (
            <option key={role} value={role}>
              {role}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Temporary password" hint={passwordHint}>
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
        {busy ? 'Creating…' : submitLabel}
      </Button>
    </form>
  );
}
