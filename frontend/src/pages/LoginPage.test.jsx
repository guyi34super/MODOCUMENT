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

function renderLogin(initialEntry = '/login') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
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

/**
 * Routes the mocked fetch by URL: the page always asks /api/auth/providers on
 * mount (SSO buttons), while /api/auth/login answers with `loginResponse`.
 */
function stubFetch({ providers = [], loginResponse }) {
  const fetchMock = vi.fn((url) => {
    if (url === '/api/auth/providers') return Promise.resolve(jsonResponse(providers));
    return Promise.resolve(loginResponse);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('LoginPage', () => {
  it('signs in and navigates to the dashboard', async () => {
    const user = { userId: 1, email: 'demo@modocument.local', displayName: 'Demo User' };
    const fetchMock = stubFetch({ loginResponse: jsonResponse({ token: 'jwt-1', user }) });

    renderLogin();
    await userEvent.type(screen.getByLabelText('Email'), 'demo@modocument.local');
    await userEvent.type(screen.getByLabelText('Password'), 'Demo123!');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('DASHBOARD')).toBeInTheDocument();
    expect(getToken()).toBe('jwt-1');

    const loginCall = fetchMock.mock.calls.find(([url]) => url === '/api/auth/login');
    expect(loginCall).toBeTruthy();
    expect(JSON.parse(loginCall[1].body)).toEqual({
      email: 'demo@modocument.local',
      password: 'Demo123!',
    });
  });

  it('shows the backend error on bad credentials and stays on the page', async () => {
    stubFetch({ loginResponse: jsonResponse({ error: 'Invalid email or password.' }, 401) });

    renderLogin();
    await userEvent.type(screen.getByLabelText('Email'), 'demo@modocument.local');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-pass');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Invalid email or password.')).toBeInTheDocument();
    expect(screen.queryByText('DASHBOARD')).not.toBeInTheDocument();
    expect(getToken()).toBeNull();
  });

  it('redirects straight to the dashboard when already signed in', () => {
    stubFetch({});
    localStorage.setItem('modoc.token', 'jwt-existing');
    localStorage.setItem('modoc.user', JSON.stringify({ userId: 1 }));

    renderLogin();
    expect(screen.getByText('DASHBOARD')).toBeInTheDocument();
  });

  it('shows a sign-in link per configured OIDC provider', async () => {
    stubFetch({
      providers: [
        { id: 'entra', name: 'Microsoft' },
        { id: 'keycloak', name: 'Keycloak' },
      ],
    });

    renderLogin();

    const microsoft = await screen.findByRole('link', { name: 'Sign in with Microsoft' });
    expect(microsoft).toHaveAttribute('href', '/oauth2/authorization/entra');
    const keycloak = screen.getByRole('link', { name: 'Sign in with Keycloak' });
    expect(keycloak).toHaveAttribute('href', '/oauth2/authorization/keycloak');
    // The password form stays as the break-glass path.
    expect(screen.getByLabelText('Email')).toBeInTheDocument();
  });

  it('shows no provider buttons when no OIDC is configured', async () => {
    stubFetch({ providers: [] });

    renderLogin();

    // The providers call resolves before this settles.
    expect(await screen.findByLabelText('Email')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Sign in with/ })).not.toBeInTheDocument();
  });

  it('shows an opaque SSO error banner when redirected with ?error=oidc', async () => {
    stubFetch({ providers: [{ id: 'keycloak', name: 'Keycloak' }] });

    renderLogin('/login?error=oidc');

    expect(
      await screen.findByText(/Single sign-on failed/)
    ).toBeInTheDocument();
  });
});
