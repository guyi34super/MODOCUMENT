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

  it('register stores the new session', async () => {
    const user = { userId: 2, email: 'new@example.com', displayName: 'New' };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ token: 'jwt-2', user }, 201)));

    const { result } = renderHook(() => useAuth(), { wrapper });
    await act(() => result.current.register('new@example.com', 'Password1!', 'New'));

    expect(result.current.user).toEqual(user);
    expect(getToken()).toBe('jwt-2');
  });

  it('logout clears the token, user and storage', async () => {
    const user = { userId: 1, email: 'demo@modocument.local' };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ token: 'jwt-1', user })));

    const { result } = renderHook(() => useAuth(), { wrapper });
    await act(() => result.current.login('demo@modocument.local', 'Demo123!'));
    act(() => result.current.logout());

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.user).toBeNull();
    expect(getToken()).toBeNull();
    expect(getStoredUser()).toBeNull();
  });

  it('updateUser persists the fresh profile', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    act(() => result.current.updateUser({ userId: 1, displayName: 'Renamed' }));

    expect(result.current.user.displayName).toBe('Renamed');
    expect(getStoredUser().displayName).toBe('Renamed');
  });
});
