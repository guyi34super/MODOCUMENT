import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import QRCode from 'qrcode';
import * as authApi from '../api/auth';
import { useAuth } from '../context/AuthContext';
import PageHeader from '../components/PageHeader';
import AppVersionCard from '../components/AppVersionCard';
import { Button, Card, ErrorBanner, Field, inputClass } from '../components/ui';
import { useToast } from '../context/ToastContext';

export default function ProfilePage() {
  const toast = useToast();
  const { user, updateUser, logout } = useAuth();
  const navigate = useNavigate();

  const [displayName, setDisplayName] = useState(user?.displayName || '');
  const [theme, setTheme] = useState('light');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');

  // MFA self-service (ws06): status + enrol/activate/disable.
  const [mfa, setMfa] = useState(null); // {enrolled, enabled} once loaded
  const [enrollment, setEnrollment] = useState(null);
  const [qrDataUrl, setQrDataUrl] = useState('');
  const [activationCode, setActivationCode] = useState('');
  const [disableCode, setDisableCode] = useState('');

  useEffect(() => {
    authApi.mfaStatus().then(setMfa).catch((err) => setError(err.message));
  }, []);

  useEffect(() => {
    if (enrollment?.otpauthUri) {
      QRCode.toDataURL(enrollment.otpauthUri, { margin: 1, width: 176 })
        .then(setQrDataUrl)
        .catch(() => setQrDataUrl(''));
    }
  }, [enrollment]);

  // One refresh on mount keeps the local copy honest without recurring calls.
  useEffect(() => {
    authApi
      .me()
      .then((fresh) => {
        updateUser(fresh);
        setDisplayName(fresh.displayName || '');
        try {
          const settings = JSON.parse(fresh.settings || '{}');
          if (settings.theme) setTheme(settings.theme);
        } catch {
          /* ignore malformed settings */
        }
      })
      .catch((err) => setError(err.message));
  }, [updateUser]);

  const saveProfile = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      const updated = await toast.promise(
        authApi.updateProfile(displayName, JSON.stringify({ theme })),
        { loading: 'Saving profile…', success: 'Profile saved' }
      );
      updateUser(updated);
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const savePassword = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      await toast.promise(authApi.changePassword(currentPassword, newPassword), {
        loading: 'Changing password…',
        success: 'Password changed',
      });
      setCurrentPassword('');
      setNewPassword('');
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const startEnrollment = async () => {
    setBusy(true);
    try {
      const fresh = await toast.promise(authApi.enrollMfa(), {
        loading: 'Preparing two-factor setup…',
      });
      setEnrollment(fresh);
    } catch {
      // The error toast is already shown.
    } finally {
      setBusy(false);
    }
  };

  const activate = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      await toast.promise(authApi.activateMfa(activationCode.trim()), {
        loading: 'Activating…',
        success: 'Two-factor authentication enabled',
      });
      setEnrollment(null);
      setQrDataUrl('');
      setActivationCode('');
      setMfa(await authApi.mfaStatus());
    } catch {
      setActivationCode('');
    } finally {
      setBusy(false);
    }
  };

  const disableMfa = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      await toast.promise(authApi.disableMfa(disableCode.trim()), {
        loading: 'Disabling…',
        success: 'Two-factor authentication disabled',
      });
      setDisableCode('');
      setMfa(await authApi.mfaStatus());
    } catch {
      setDisableCode('');
    } finally {
      setBusy(false);
    }
  };

  const copyText = async (text, what) => {
    try {
      await navigator.clipboard.writeText(text);
      toast.success(`${what} copied`);
    } catch {
      toast.error('Copy failed — select and copy the text manually.');
    }
  };

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Profile' }]}
        title="Profile"
        subtitle="Account settings"
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      <Card className="mb-5">
        <h2 className="mb-3 text-lg font-normal">Details</h2>
        <form onSubmit={saveProfile} className="space-y-4">
          <Field label="Email">
            <input className={`${inputClass} bg-sidebar`} value={user?.email || ''} disabled />
          </Field>
          <Field label="Display name">
            <input
              required
              className={inputClass}
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
            />
          </Field>
          <Field label="Theme preference">
            <select className={inputClass} value={theme} onChange={(e) => setTheme(e.target.value)}>
              <option value="light">Light</option>
              <option value="dark">Dark</option>
            </select>
          </Field>
          <Button type="submit" disabled={busy}>
            {busy ? 'Saving…' : 'Save profile'}
          </Button>
        </form>
      </Card>

      <Card className="mb-5">
        <h2 className="mb-3 text-lg font-normal">Change password</h2>
        <form onSubmit={savePassword} className="space-y-4">
          <Field label="Current password">
            <input
              type="password"
              required
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
              className={inputClass}
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
            />
          </Field>
          <Button type="submit" disabled={busy}>
            {busy ? 'Saving…' : 'Change password'}
          </Button>
        </form>
      </Card>

      <Card className="mb-5">
        <h2 className="mb-1 text-lg font-normal">Two-factor authentication</h2>
        {!mfa && <p className="text-sm text-muted">Loading status…</p>}

        {mfa && mfa.enabled && (
          <>
            <p className="mb-3 text-sm text-muted">
              Two-factor authentication is <span className="font-medium text-ink">enabled</span>.
              To regenerate your recovery codes, disable it and set it up again
              (a fresh secret and ten fresh codes are issued).
            </p>
            <form onSubmit={disableMfa} className="space-y-4">
              <Field
                label="Verification code"
                hint="A current authenticator code, or an unused recovery code."
              >
                <input
                  required
                  autoComplete="one-time-code"
                  maxLength={32}
                  className={inputClass}
                  value={disableCode}
                  onChange={(e) => setDisableCode(e.target.value)}
                />
              </Field>
              <Button type="submit" variant="danger" disabled={busy}>
                {busy ? 'Working…' : 'Disable two-factor authentication'}
              </Button>
            </form>
          </>
        )}

        {mfa && !mfa.enabled && !enrollment && (
          <>
            <p className="mb-3 text-sm text-muted">
              Two-factor authentication is <span className="font-medium text-ink">off</span>.
              Protect your account with a 6-digit code from an authenticator app.
            </p>
            <Button onClick={startEnrollment} disabled={busy}>
              {busy ? 'Working…' : 'Set up two-factor authentication'}
            </Button>
          </>
        )}

        {mfa && !mfa.enabled && enrollment && (
          <div className="space-y-4 text-sm">
            <p className="text-muted">
              Scan the QR code (or add the secret manually), store the recovery
              codes somewhere safe — they are shown only once — then confirm
              with your first code.
            </p>
            {qrDataUrl && (
              <img
                src={qrDataUrl}
                alt="QR code for authenticator app"
                className="size-44 rounded border border-line"
              />
            )}
            <div>
              <p className="mb-1 font-medium">Secret</p>
              <div className="flex items-center gap-2">
                <code className="break-all rounded bg-sidebar px-2 py-1 text-xs">
                  {enrollment.secret}
                </code>
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => copyText(enrollment.secret, 'Secret')}
                >
                  Copy
                </Button>
              </div>
            </div>
            <div>
              <p className="mb-1 font-medium">Recovery codes</p>
              <ul className="grid max-w-md grid-cols-2 gap-1 rounded bg-sidebar p-2 font-mono text-xs">
                {enrollment.recoveryCodes.map((recovery) => (
                  <li key={recovery}>{recovery}</li>
                ))}
              </ul>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                className="mt-2"
                onClick={() => copyText(enrollment.recoveryCodes.join('\n'), 'Recovery codes')}
              >
                Copy all
              </Button>
            </div>
            <form onSubmit={activate} className="space-y-4">
              <Field label="Verification code" hint="The current 6-digit code from your app.">
                <input
                  required
                  autoComplete="one-time-code"
                  inputMode="numeric"
                  pattern="[0-9]{6}"
                  maxLength={6}
                  className={inputClass}
                  value={activationCode}
                  onChange={(e) => setActivationCode(e.target.value)}
                />
              </Field>
              <Button type="submit" disabled={busy}>
                {busy ? 'Activating…' : 'Activate'}
              </Button>
            </form>
          </div>
        )}
      </Card>

      <Card>
        <h2 className="mb-1 text-lg font-normal">Session</h2>
        <p className="mb-3 text-sm text-muted">
          Signing out clears your token from this browser.
        </p>
        <Button variant="danger" onClick={handleLogout}>
          Log out
        </Button>
      </Card>

      {/* ws24: SPA and server version (data-testid="app-version"). */}
      <AppVersionCard className="mt-5" />
    </div>
  );
}
