import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import * as authApi from '../api/auth';
import { useAuth } from '../context/AuthContext';
import PageHeader from '../components/PageHeader';
import { Button, Callout, Card, ErrorBanner, Field, inputClass } from '../components/ui';

export default function ProfilePage() {
  const { user, updateUser, logout } = useAuth();
  const navigate = useNavigate();

  const [displayName, setDisplayName] = useState(user?.displayName || '');
  const [theme, setTheme] = useState('light');
  const [error, setError] = useState('');
  const [savedMessage, setSavedMessage] = useState('');
  const [busy, setBusy] = useState(false);

  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [passwordMessage, setPasswordMessage] = useState('');

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
    setError('');
    setSavedMessage('');
    try {
      const updated = await authApi.updateProfile(displayName, JSON.stringify({ theme }));
      updateUser(updated);
      setSavedMessage('Profile saved.');
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const savePassword = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError('');
    setPasswordMessage('');
    try {
      await authApi.changePassword(currentPassword, newPassword);
      setCurrentPassword('');
      setNewPassword('');
      setPasswordMessage('Password changed.');
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <div>
      <PageHeader
        crumbs={[{ label: 'Docs' }, { label: 'Profile' }]}
        title="Profile"
        subtitle="Account settings"
      />
      <ErrorBanner message={error} onDismiss={() => setError('')} />

      <Card className="mb-5">
        <h2 className="mb-3 text-lg font-normal">Details</h2>
        {savedMessage && (
          <div className="mb-3">
            <Callout variant="success" title="Saved">{savedMessage}</Callout>
          </div>
        )}
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
        {passwordMessage && (
          <div className="mb-3">
            <Callout variant="success" title="Done">{passwordMessage}</Callout>
          </div>
        )}
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
          <Field label="New password" hint="At least 8 characters.">
            <input
              type="password"
              required
              minLength={8}
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

      <Card>
        <h2 className="mb-1 text-lg font-normal">Session</h2>
        <p className="mb-3 text-sm text-muted">
          Signing out clears your token from this browser.
        </p>
        <Button variant="danger" onClick={handleLogout}>
          Log out
        </Button>
      </Card>
    </div>
  );
}
