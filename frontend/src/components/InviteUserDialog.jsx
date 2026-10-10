import { useState } from 'react';
import * as adminApi from '../api/admin';
import { isPendingChange } from '../api/changes';
import { useToast } from '../context/ToastContext';
import Modal from './Modal';
import { BANK_ROLES } from './CreateUserForm';
import { Button, Callout, Field, inputClass } from './ui';
import { generateTemporaryPassword } from '../utils/temporaryPassword';

/** The text an administrator hands to a new user (there is no email channel). */
export function inviteText({ email, role, temporaryPassword, bankName, pending, origin }) {
  const lines = [
    `You have been invited to MoDocument${bankName ? ` (${bankName})` : ''}.`,
    '',
    `Sign in at: ${origin}/login`,
    `Email: ${email}`,
    `Temporary password: ${temporaryPassword}`,
    '',
    role === 'ADMIN'
      ? 'At your first sign-in you will choose your own password and set up two-factor authentication.'
      : 'At your first sign-in you will choose your own password.',
  ];
  if (pending) {
    lines.push('Your account becomes active once a second administrator has approved it.');
  }
  return lines.join('\n');
}

const freshForm = () => ({
  email: '',
  displayName: '',
  role: 'READ_ONLY',
  temporaryPassword: generateTemporaryPassword(),
});

/**
 * "Invite" on the admin dashboard: creates the account (USER_CREATE, which
 * goes through maker/checker in production) with a generated temporary
 * password, then shows a copyable invitation — sign-in URL, email and the
 * temporary password. MoDocument sends no email: the administrator passes
 * the text on through the bank's own channel.
 */
export default function InviteUserDialog({ open, onClose, bankName, onInvited }) {
  const toast = useToast();
  const [form, setForm] = useState(freshForm);
  const [busy, setBusy] = useState(false);
  const [invite, setInvite] = useState(null);

  const close = () => {
    if (busy) return;
    setForm(freshForm());
    setInvite(null);
    onClose?.();
  };

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      const result = await toast.promise(adminApi.createUser(form), {
        loading: 'Creating the account…',
        success: (answer) =>
          isPendingChange(answer)
            ? `Account for ${form.email} sent for approval (change #${answer.pendingChangeId})`
            : `Account for ${form.email} created`,
      });
      setInvite(
        inviteText({
          ...form,
          bankName,
          pending: isPendingChange(result),
          origin: window.location.origin,
        })
      );
      onInvited?.(result);
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(invite);
      toast.success('Invitation copied');
    } catch {
      toast.error('Copy is not available here — select the text and copy it manually.');
    }
  };

  return (
    <Modal
      open={open}
      onClose={close}
      title="Invite a user"
      description="Creates the account and gives you an invitation to pass on."
      size="lg"
      footer={
        invite ? (
          <>
            <Button type="button" variant="secondary" onClick={copy}>
              Copy invitation
            </Button>
            <Button type="button" onClick={close}>
              Done
            </Button>
          </>
        ) : (
          <>
            <Button type="button" variant="secondary" onClick={close} disabled={busy}>
              Cancel
            </Button>
            <Button type="submit" form="invite-user-form" disabled={busy}>
              {busy ? 'Creating…' : 'Create account'}
            </Button>
          </>
        )
      }
    >
      <div className="space-y-4">
        <Callout variant="info" title="No email is sent">
          MoDocument has no email channel. Copy the invitation and send it through your
          bank&apos;s approved channel — ideally the temporary password separately from the
          rest.
        </Callout>
        {invite ? (
          <Field label="Invitation">
            <textarea
              readOnly
              data-testid="invite-text"
              rows={9}
              className={`${inputClass} font-mono text-xs`}
              value={invite}
              onFocus={(e) => e.target.select()}
            />
          </Field>
        ) : (
          <form id="invite-user-form" onSubmit={submit} className="space-y-4">
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
                {BANK_ROLES.map((role) => (
                  <option key={role} value={role}>
                    {role}
                  </option>
                ))}
              </select>
            </Field>
            <Field
              label="Temporary password"
              hint="Generated for you; the user must replace it at the first sign-in."
            >
              <div className="flex gap-2">
                <input
                  readOnly
                  className={`${inputClass} font-mono`}
                  value={form.temporaryPassword}
                />
                <Button
                  type="button"
                  variant="secondary"
                  onClick={() =>
                    setForm({ ...form, temporaryPassword: generateTemporaryPassword() })
                  }
                >
                  Regenerate
                </Button>
              </div>
            </Field>
          </form>
        )}
      </div>
    </Modal>
  );
}
