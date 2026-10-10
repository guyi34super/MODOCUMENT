import { describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { AuthProvider, useAuth } from './AuthContext';
import { getStoredUser, getToken } from '../api/client';

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const wrapper = ({ children }) => <AuthProvider>{children}</AuthProvider>;

describe('AuthContext', () => {
  it('starts unauthenticated with no stored session', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.user).toBeNull();
  });

  it('login stores the token and user', async () => {
    const user = { userId: 1, email: 'demo@modocument.local', displayName: 'Demo User' };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ token: 'jwt-1', user })));

    const { result } = renderHook(() => useAuth(), { wrapper });
    await act(() => result.current.login('demo@modocument.local', 'Demo123!'));

    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.user).toEqual(user);
    expect(getToken()).toBe('jwt-1');
    expect(getStoredUser()).toEqual(user);
  });

  it('login with MFA required returns the challenge and stores NO session', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({ mfaRequired: true, enrollmentRequired: true, challenge: 'challenge-jwt' })
      )
    );

    const { result } = renderHook(() => useAuth(), { wrapper });
    let outcome;
    await act(async () => {
      outcome = await result.current.login('admin@modocument.local', 'Demo123!');
    });

    expect(outcome).toEqual({
      mfaRequired: true,
      enrollmentRequired: true,
      challenge: 'challenge-jwt',
    });
    expect(result.current.isAuthenticated).toBe(false);
    expect(getToken()).toBeNull();
    expect(getStoredUser()).toBeNull();
  });

  it('completeMfaLogin verifies the code and stores the session', async () => {
    const user = { userId: 1, email: 'demo@modocument.local' };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ token: 'jwt-mfa', user }));
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() => useAuth(), { wrapper });
    await act(() => result.current.completeMfaLogin('challenge-jwt', '123456'));

    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.user).toEqual(user);
    expect(getToken()).toBe('jwt-mfa');
    const [url, opts] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/auth/mfa/verify');
    expect(JSON.parse(opts.body)).toEqual({ challenge: 'challenge-jwt', code: '123456' });
  });

  it('a wrong MFA code leaves the session empty', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ error: 'Invalid verification code.' }, 401))
    );

    const { result } = renderHook(() => useAuth(), { wrapper });
    await expect(
      act(() => result.current.completeMfaLogin('challenge-jwt', '000000'))
    ).rejects.toThrow('Invalid verification code.');

    expect(result.current.isAuthenticated).toBe(false);
    expect(getToken()).toBeNull();
  });

  it('login failure leaves the session empty', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ error: 'Invalid email or password.' }, 401))
    );

    const { result } = renderHook(() => useAuth(), { wrapper });
    await expect(
      act(() => result.current.login('demo@modocument.local', 'wrong'))
    ).rejects.toThrow('Invalid email or password.');

    expect(result.current.isAuthenticated).toBe(false);
    expect(getToken()).toBeNull();
  });

  it('logout revokes server-side, then clears the token, user and storage', async () => {
    const user = { userId: 1, email: 'demo@modocument.local' };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ token: 'jwt-1', user }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() => useAuth(), { wrapper });
    await act(() => result.current.login('demo@modocument.local', 'Demo123!'));
    await act(() => result.current.logout());

    expect(fetchMock.mock.calls[1][0]).toBe('/api/auth/logout');
    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.user).toBeNull();
    expect(getToken()).toBeNull();
    expect(getStoredUser()).toBeNull();
  });

  it('logout clears the session even when the server call fails', async () => {
    const user = { userId: 1, email: 'demo@modocument.local' };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ token: 'jwt-1', user }))
      .mockRejectedValue(new Error('network down'));
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() => useAuth(), { wrapper });
    await act(() => result.current.login('demo@modocument.local', 'Demo123!'));
    await act(() => result.current.logout());

    expect(result.current.isAuthenticated).toBe(false);
    expect(getToken()).toBeNull();
  });

  it('updateUser persists the fresh profile', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    act(() => result.current.updateUser({ userId: 1, displayName: 'Renamed' }));

    expect(result.current.user.displayName).toBe('Renamed');
    expect(getStoredUser().displayName).toBe('Renamed');
  });

  it('logout({ revoke: false }) signs out locally without calling the server (BUG-02)', async () => {
    localStorage.setItem('modoc.token', 'dead-token');
    localStorage.setItem('modoc.user', JSON.stringify({ userId: 3 }));
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const { result } = renderHook(() => useAuth(), { wrapper });
    await act(() => result.current.logout({ revoke: false }));

    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.current.isAuthenticated).toBe(false);
    expect(getToken()).toBeNull();
  });
});
