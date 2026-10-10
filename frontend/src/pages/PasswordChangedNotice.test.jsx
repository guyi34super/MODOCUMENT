import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';
import ProtectedRoute from '../components/ProtectedRoute';
import ForceChangePasswordPage from './ForceChangePasswordPage';
import LoginPage from './LoginPage';

import { changePassword, logout } from '../api/auth';

// Plain functions where the global afterEach restoreAllMocks must not strip
// the implementation (providers is called on every LoginPage mount).
vi.mock('../api/auth', () => ({
  changePassword: vi.fn(),
  logout: vi.fn(),
  providers: () => Promise.resolve([]),
  enrollMfa: () => Promise.resolve(null),
  activateMfa: () => Promise.resolve(null),
}));

vi.mock('qrcode', () => ({
  default: { toDataURL: () => Promise.resolve('data:image/png;base64,QR') },
}));

const NOTICE = /Password changed — sign in again/;

/** The routes as App.jsx wires them: the change page sits behind ProtectedRoute. */
function renderApp(initialEntry, { signedIn = true } = {}) {
  if (signedIn) {
    localStorage.setItem('modoc.token', 'temp-token');
    localStorage.setItem(
      'modoc.user',
      JSON.stringify({ userId: 9, email: 'joiner@bank.example', mustChangePassword: true })
    );
  }
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <AuthProvider>
        <ToastProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route
              path="/force-password-change"
              element={
                <ProtectedRoute>
                  <ForceChangePasswordPage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/"
              element={
                <ProtectedRoute>
                  <div>DASHBOARD</div>
                </ProtectedRoute>
              }
            />
          </Routes>
        </ToastProvider>
      </AuthProvider>
    </MemoryRouter>
  );
}

const inlineNotices = () =>
  screen.queryAllByRole('status').filter((el) => NOTICE.test(el.textContent));

describe('BUG-04: the "Password changed" notice after a forced password change', () => {
  beforeEach(() => {
    changePassword.mockReset().mockResolvedValue(null);
    logout.mockReset().mockResolvedValue(null);
  });

  it('shows the inline notice on the login page although ProtectedRoute redirects first', async () => {
    renderApp('/force-password-change');

    await userEvent.type(screen.getByLabelText(/Temporary password/), 'Temp-Rotor-91x!');
    await userEvent.type(screen.getByLabelText(/New password/), 'My-own-secret-42!');
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }));

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(inlineNotices()).toHaveLength(1);
    expect(screen.queryByText('Your session expired. Please sign in again.')).toBeNull();
    expect(localStorage.getItem('modoc.token')).toBeNull();
    // BUG-02 stays fixed: no logout call with the dead token.
    expect(logout).not.toHaveBeenCalled();
  });

  it('an ordinary redirect to /login carries no notice', async () => {
    renderApp('/', { signedIn: false });

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(inlineNotices()).toHaveLength(0);
  });
});
