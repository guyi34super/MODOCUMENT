import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';
import { getToken } from '../api/client';
import LoginPage from './LoginPage';

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderLogin() {
  return render(
    <MemoryRouter initialEntries={['/login']}>
      <AuthProvider>
        <ToastProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/" element={<div>DASHBOARD</div>} />
          </Routes>
        </ToastProvider>
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('LoginPage', () => {
  it('signs in and navigates to the dashboard', async () => {
    const user = { userId: 1, email: 'demo@modocument.local', displayName: 'Demo User' };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ token: 'jwt-1', user }));
    vi.stubGlobal('fetch', fetchMock);

    renderLogin();
    await userEvent.type(screen.getByLabelText('Email'), 'demo@modocument.local');
    await userEvent.type(screen.getByLabelText('Password'), 'Demo123!');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('DASHBOARD')).toBeInTheDocument();
    expect(getToken()).toBe('jwt-1');

    const [url, opts] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/auth/login');
    expect(JSON.parse(opts.body)).toEqual({
      email: 'demo@modocument.local',
      password: 'Demo123!',
    });
  });

  it('shows the backend error on bad credentials and stays on the page', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ error: 'Invalid email or password.' }, 401))
    );

    renderLogin();
    await userEvent.type(screen.getByLabelText('Email'), 'demo@modocument.local');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-pass');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Invalid email or password.')).toBeInTheDocument();
    expect(screen.queryByText('DASHBOARD')).not.toBeInTheDocument();
    expect(getToken()).toBeNull();
  });

  it('redirects straight to the dashboard when already signed in', () => {
    localStorage.setItem('modoc.token', 'jwt-existing');
    localStorage.setItem('modoc.user', JSON.stringify({ userId: 1 }));

    renderLogin();
    expect(screen.getByText('DASHBOARD')).toBeInTheDocument();
  });
});
