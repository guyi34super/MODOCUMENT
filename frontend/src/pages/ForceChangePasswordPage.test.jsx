import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';
import ForceChangePasswordPage from './ForceChangePasswordPage';

import { changePassword, logout } from '../api/auth';

vi.mock('../api/auth', () => ({
  changePassword: vi.fn(),
  logout: vi.fn(),
}));

function LoginProbe() {
  const location = useLocation();
  return (
    <div>
      LOGIN PAGE {location.search || '(no query)'} {location.state?.notice || '(no notice)'}
    </div>
  );
}

function renderPage() {
  localStorage.setItem('modoc.token', 'temp-token');
  localStorage.setItem(
    'modoc.user',
    JSON.stringify({ userId: 9, email: 'joiner@bank.example', mustChangePassword: true })
  );
  return render(
    <MemoryRouter initialEntries={['/force-password-change']}>
      <AuthProvider>
        <ToastProvider>
          <Routes>
            <Route path="/login" element={<LoginProbe />} />
            <Route path="/force-password-change" element={<ForceChangePasswordPage />} />
          </Routes>
        </ToastProvider>
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('ForceChangePasswordPage', () => {
  beforeEach(() => {
    changePassword.mockReset().mockResolvedValue(null);
    logout.mockReset().mockResolvedValue(null);
  });

  it('explains the situation and changes the temporary password', async () => {
    renderPage();

    expect(screen.getByText(/temporary password/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(/Temporary password/), 'Temp-Rotor-91x!');
    await userEvent.type(screen.getByLabelText(/New password/), 'My-own-secret-42!');
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    await waitFor(() =>
      expect(changePassword).toHaveBeenCalledWith('Temp-Rotor-91x!', 'My-own-secret-42!')
    );
    // The change killed the session server-side: local sign-out + /login.
    expect(await screen.findByText(/LOGIN PAGE/)).toBeInTheDocument();
    expect(localStorage.getItem('modoc.token')).toBeNull();
  });

  it('BUG-02: lands on a plain /login with a neutral notice, never "session expired"', async () => {
    renderPage();

    await userEvent.type(screen.getByLabelText(/Temporary password/), 'Temp-Rotor-91x!');
    await userEvent.type(screen.getByLabelText(/New password/), 'My-own-secret-42!');
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    // No ?expired=1, and the notice the login page renders as
    // "Password changed — sign in again".
    expect(
      await screen.findByText('LOGIN PAGE (no query) password-changed')
    ).toBeInTheDocument();
    // The session is already dead server-side: logging out with its token
    // would only earn the 401 that triggered the expiry redirect.
    expect(logout).not.toHaveBeenCalled();
  });

  it('stays on the page when the change is rejected', async () => {
    changePassword.mockRejectedValue(new Error('Current password is incorrect.'));
    renderPage();

    await userEvent.type(screen.getByLabelText(/Temporary password/), 'wrong-temp-pass');
    await userEvent.type(screen.getByLabelText(/New password/), 'My-own-secret-42!');
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    await waitFor(() => expect(changePassword).toHaveBeenCalled());
    expect(screen.queryByText(/LOGIN PAGE/)).not.toBeInTheDocument();
    expect(logout).not.toHaveBeenCalled();
  });
});
