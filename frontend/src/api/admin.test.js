import { describe, expect, it, vi } from 'vitest';
import { resetUserMfa, resetUserPassword, unlockUser, updateUser } from './admin';
import { getDashboard } from './dashboard';

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** The method, path and JSON body of the single fetch the call made. */
async function captured(call, status = 200) {
  const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ok: true }, status));
  vi.stubGlobal('fetch', fetchMock);
  await call();
  const [url, opts] = fetchMock.mock.calls[0];
  return { url, method: opts.method || 'GET', body: opts.body && JSON.parse(opts.body) };
}

describe('phase 2 user-admin API', () => {
  it('unlocks with a bodiless POST', async () => {
    expect(await captured(() => unlockUser(7))).toEqual({
      url: '/api/admin/users/7/unlock',
      method: 'POST',
      body: undefined,
    });
  });

  it('submits a password reset with the temporary password only', async () => {
    expect(await captured(() => resetUserPassword(7, 'Temp-Rotor-91x!'), 202)).toEqual({
      url: '/api/admin/users/7/password-reset',
      method: 'POST',
      body: { temporaryPassword: 'Temp-Rotor-91x!' },
    });
  });

  it('submits an MFA reset with its reason', async () => {
    expect(await captured(() => resetUserMfa(7, 'Lost phone'), 202)).toEqual({
      url: '/api/admin/users/7/mfa-reset',
      method: 'POST',
      body: { reason: 'Lost phone' },
    });
  });

  it('PATCHes the display name and nothing else', async () => {
    expect(
      await captured(() => updateUser(7, { displayName: 'Jane', email: 'x@evil.example' }))
    ).toEqual({
      url: '/api/admin/users/7',
      method: 'PATCH',
      body: { displayName: 'Jane' },
    });
  });

  it('reads the dashboard aggregate', async () => {
    expect(await captured(() => getDashboard())).toEqual({
      url: '/api/admin/dashboard',
      method: 'GET',
      body: undefined,
    });
  });
});
