import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import * as authApi from '../api/auth';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { Button, Card, Field, inputClass } from '../components/ui';

/**
 * Forced password change (ws09): an admin-created account signs in with a
 * temporary password and lands here before anything else — the backend
 * refuses every non-self-service route with PASSWORD_CHANGE_REQUIRED until
 * the password is changed. A successful change ends the session server-side
 * (every token dies), so the user is sent back to the login page to sign in
 * with the password only they know — signed out LOCALLY only: the dead token
 * would earn the logout call a 401 and the "session expired" banner (BUG-02).
 */
export default function ForceChangePasswordPage() {
  const toast = useToast();
  const navigate = useNavigate();
  const { user, logout } = useAuth();

  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      await toast.promise(authApi.changePassword(currentPassword, newPassword), {
        loading: 'Changing password…',
        success: 'Password changed — sign in with your new password',
      });
      // The notice travels with the sign-out itself: ProtectedRoute around
      // this page redirects to /login as soon as the user is cleared, before
      // the navigate below runs (BUG-04). The navigate stays for when the
      // page is rendered without that guard.
      await logout({ revoke: false, notice: 'password-changed' });
      navigate('/login', { replace: true, state: { notice: 'password-changed' } });
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-sidebar px-4">
      <Card className="w-full max-w-md">
        <h1 className="mb-1 text-xl font-normal">Choose your own password</h1>
        <p className="mb-4 text-sm text-muted">
          {user?.email ? `You are signed in as ${user.email}. ` : ''}
          Your account was created with a temporary password. Replace it now —
          nothing else is available until you do.
        </p>
        <form onSubmit={submit} className="space-y-4">
          <Field label="Temporary password">
            <input
              type="password"
              required
              autoComplete="current-password"
              className={inputClass}
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
            />
          </Field>
          <Field
            label="New password"
            hint="At least 12 characters; long passphrases welcome. Common/breached passwords and your email are rejected."
          >
            <input
              type="password"
              required
              minLength={12}
              autoComplete="new-password"
              className={inputClass}
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
            />
          </Field>
          <Button type="submit" disabled={busy} className="w-full justify-center">
            {busy ? 'Saving…' : 'Change password'}
          </Button>
        </form>
      </Card>
    </div>
  );
}
