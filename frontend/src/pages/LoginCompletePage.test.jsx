import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from '../context/AuthContext';
import { getStoredUser, getToken } from '../api/client';
import LoginCompletePage from './LoginCompletePage';

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderComplete() {
  return render(
    <MemoryRouter initialEntries={['/login/complete']}>
      <AuthProvider>
        <Routes>
          <Route path="/login/complete" element={<LoginCompletePage />} />
          <Route path="/login" element={<div>LOGIN</div>} />
          <Route path="/" element={<div>DASHBOARD</div>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('LoginCompletePage', () => {
  it('exchanges the refresh cookie for a session and heads home', async () => {
    const user = { userId: 7, email: 'ana@acme.example', displayName: 'Ana', role: 'READ_WRITE' };
    const fetchMock = vi.fn((url) => {
      if (url === '/api/auth/refresh') {
        return Promise.resolve(jsonResponse({ token: 'jwt-oidc', user }));
      }
      if (url === '/api/auth/me') {
        return Promise.resolve(jsonResponse(user));
      }
      return Promise.resolve(jsonResponse({}, 404));
    });
    vi.stubGlobal('fetch', fetchMock);

    renderComplete();
    expect(screen.getByText('Completing sign-in…')).toBeInTheDocument();

    expect(await screen.findByText('DASHBOARD')).toBeInTheDocument();
    expect(getToken()).toBe('jwt-oidc');
    expect(getStoredUser()).toEqual(user);

    // The cookie is the credential: refresh is a POST with no token in any URL.
    const refreshCall = fetchMock.mock.calls.find(([url]) => url === '/api/auth/refresh');
    expect(refreshCall[1].method).toBe('POST');
    for (const [url] of fetchMock.mock.calls) {
      expect(url).not.toContain('jwt-oidc');
    }
  });

  it('falls back to the login page with the opaque oidc error when no session exists', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ error: 'Refresh token is missing.' }, 401))
    );

    renderComplete();

    expect(await screen.findByText('LOGIN')).toBeInTheDocument();
    expect(getToken()).toBeNull();
  });
});
