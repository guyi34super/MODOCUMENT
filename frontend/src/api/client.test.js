import { describe, expect, it, vi } from 'vitest';
import { api, clearSession, getStoredUser, getToken, setSession } from './client';

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** An unsigned JWT-shaped token whose exp lies the given seconds from now. */
function fakeJwt(expiresInSeconds) {
  const payload = btoa(
    JSON.stringify({ exp: Math.floor(Date.now() / 1000) + expiresInSeconds })
  )
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
  return `header.${payload}.signature`;
}

describe('session storage', () => {
  it('stores and clears token and user', () => {
    setSession('tok-123', { userId: 1, email: 'a@b.com' });
    expect(getToken()).toBe('tok-123');
    expect(getStoredUser()).toEqual({ userId: 1, email: 'a@b.com' });

    clearSession();
    expect(getToken()).toBeNull();
    expect(getStoredUser()).toBeNull();
  });

  it('returns null for corrupt stored user JSON', () => {
    localStorage.setItem('modoc.user', 'not-json{');
    expect(getStoredUser()).toBeNull();
  });
});

describe('api request wrapper', () => {
  it('attaches the Bearer token and parses JSON', async () => {
    setSession('tok-abc', {});
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([{ templateId: 1 }]));
    vi.stubGlobal('fetch', fetchMock);

    const result = await api.get('/Templates/GetAll');

    expect(result).toEqual([{ templateId: 1 }]);
    const [url, opts] = fetchMock.mock.calls[0];
    expect(url).toBe('/Templates/GetAll');
    expect(opts.headers.Authorization).toBe('Bearer tok-abc');
  });

  it('sends JSON bodies with Content-Type and method', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ok: true }, 201));
    vi.stubGlobal('fetch', fetchMock);

    await api.post('/Templates/Create', { name: 'T' });

    const [, opts] = fetchMock.mock.calls[0];
    expect(opts.method).toBe('POST');
    expect(opts.headers['Content-Type']).toBe('application/json');
    expect(opts.body).toBe('{"name":"T"}');
  });

  it('does NOT set Content-Type for FormData uploads', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ok: true }));
    vi.stubGlobal('fetch', fetchMock);

    const form = new FormData();
    form.append('templateId', '1');
    await api.post('/api/ingestion/jobs', form);

    const [, opts] = fetchMock.mock.calls[0];
    expect(opts.headers['Content-Type']).toBeUndefined();
    expect(opts.body).toBe(form);
  });

  it('treats 201 and 204 as success (204 returns null)', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ id: 5 }, 201))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(api.put('/Templates/Update', {})).resolves.toEqual({ id: 5 });
    await expect(api.del('/Templates/5')).resolves.toBeNull();
  });

  it('throws the backend error message on failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ error: 'mapping is required.' }, 400))
    );

    await expect(api.put('/api/ingestion/jobs/1/mapping', {})).rejects.toThrow(
      'mapping is required.'
    );
  });

  it('exposes status, machine code and body on the thrown error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          { error: 'Too many rows.', code: 'EXPORT_CAP_EXCEEDED', cap: 5000, rows: 10000 },
          403
        )
      )
    );

    const error = await api.get('/api/ingestion/jobs/7/export/file').catch((e) => e);
    expect(error.message).toBe('Too many rows.');
    expect(error.status).toBe(403);
    expect(error.code).toBe('EXPORT_CAP_EXCEEDED');
    expect(error.body).toMatchObject({ cap: 5000, rows: 10000 });
  });

  it('falls back to a status message for non-JSON error bodies', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('boom', { status: 500 }))
    );

    await expect(api.get('/Templates/GetAll')).rejects.toThrow('Request failed (500)');
  });

  it('on 401 attempts one refresh, then clears the session and redirects with ?expired=1', async () => {
    setSession('expired', { userId: 1 });
    const assign = vi.fn();
    const original = window.location;
    Object.defineProperty(window, 'location', {
      value: { ...original, assign },
      writable: true,
      configurable: true,
    });
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(api.get('/Templates/GetAll')).rejects.toThrow('Session expired');
    expect(getToken()).toBeNull();
    // ?expired=1 makes LoginPage show the "session expired" banner.
    expect(assign).toHaveBeenCalledWith('/login?expired=1');

    // Exactly one silent refresh attempt (credentials included), no retry loop.
    const refreshCalls = fetchMock.mock.calls.filter(([url]) => url === '/api/auth/refresh');
    expect(refreshCalls).toHaveLength(1);
    expect(refreshCalls[0][1]).toMatchObject({ method: 'POST', credentials: 'include' });

    Object.defineProperty(window, 'location', { value: original, configurable: true });
  });

  it('on 401 retries the original request once after a successful refresh', async () => {
    setSession('stale-token', { userId: 1 });
    const fetchMock = vi
      .fn()
      // original request: expired access token
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      // silent refresh: new access token
      .mockResolvedValueOnce(jsonResponse({ token: 'fresh-token', user: { userId: 1 } }))
      // replayed request succeeds
      .mockResolvedValueOnce(jsonResponse([{ templateId: 7 }]));
    vi.stubGlobal('fetch', fetchMock);

    const result = await api.get('/Templates/GetAll');

    expect(result).toEqual([{ templateId: 7 }]);
    expect(getToken()).toBe('fresh-token');
    const [retryUrl, retryOpts] = fetchMock.mock.calls[2];
    expect(retryUrl).toBe('/Templates/GetAll');
    expect(retryOpts.headers.Authorization).toBe('Bearer fresh-token');
  });

  it('does NOT redirect on 401 from the MFA verify endpoint (wrong code is form feedback)', async () => {
    const assign = vi.fn();
    const original = window.location;
    Object.defineProperty(window, 'location', {
      value: { ...original, assign },
      writable: true,
      configurable: true,
    });
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ error: 'Invalid verification code.' }, 401))
    );

    await expect(
      api.post('/api/auth/mfa/verify', { challenge: 'c', code: '000000' })
    ).rejects.toThrow('Invalid verification code.');
    expect(assign).not.toHaveBeenCalled();

    Object.defineProperty(window, 'location', { value: original, configurable: true });
  });

  it('an explicit authToken overrides the stored one and never triggers refresh/redirect', async () => {
    setSession('stored-token', { userId: 1 });
    const assign = vi.fn();
    const original = window.location;
    Object.defineProperty(window, 'location', {
      value: { ...original, assign },
      writable: true,
      configurable: true,
    });
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ error: 'MFA is already enabled.' }, 401));
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      api.post('/api/auth/mfa/enroll', undefined, { authToken: 'challenge-jwt' })
    ).rejects.toThrow('MFA is already enabled.');

    // The challenge token rode the Authorization header...
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer challenge-jwt');
    // ...no silent refresh ran, no redirect happened, the session survives.
    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/auth/refresh')).toHaveLength(0);
    expect(assign).not.toHaveBeenCalled();
    expect(getToken()).toBe('stored-token');

    Object.defineProperty(window, 'location', { value: original, configurable: true });
  });

  it('refreshes PROACTIVELY when the stored token expires within 60s', async () => {
    setSession(fakeJwt(30), { userId: 1 }); // 30s left — inside the margin
    const fetchMock = vi
      .fn()
      // the lazy pre-flight refresh runs BEFORE the actual request...
      .mockResolvedValueOnce(jsonResponse({ token: 'fresh-token', user: { userId: 1 } }))
      // ...which then carries the fresh token.
      .mockResolvedValueOnce(jsonResponse([{ templateId: 3 }]));
    vi.stubGlobal('fetch', fetchMock);

    const result = await api.get('/Templates/GetAll');

    expect(result).toEqual([{ templateId: 3 }]);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/auth/refresh');
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: 'POST', credentials: 'include' });
    expect(fetchMock.mock.calls[1][0]).toBe('/Templates/GetAll');
    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBe('Bearer fresh-token');
    expect(getToken()).toBe('fresh-token');
  });

  it('does NOT refresh proactively while the token has more than 60s left', async () => {
    const longLived = fakeJwt(900);
    setSession(longLived, { userId: 1 });
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([{ templateId: 4 }]));
    vi.stubGlobal('fetch', fetchMock);

    await api.get('/Templates/GetAll');

    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/auth/refresh')).toHaveLength(0);
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe(`Bearer ${longLived}`);
  });

  it('still sends the original request when the proactive refresh fails', async () => {
    const dying = fakeJwt(30);
    setSession(dying, { userId: 1 });
    const fetchMock = vi
      .fn()
      // pre-flight refresh fails (e.g. offline blip)...
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      // ...the request still goes out with the old token and happens to work.
      .mockResolvedValueOnce(jsonResponse([{ templateId: 5 }]));
    vi.stubGlobal('fetch', fetchMock);

    const result = await api.get('/Templates/GetAll');

    expect(result).toEqual([{ templateId: 5 }]);
    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBe(`Bearer ${dying}`);
  });

  it('does NOT redirect on 401 from the login endpoint itself', async () => {
    const assign = vi.fn();
    const original = window.location;
    Object.defineProperty(window, 'location', {
      value: { ...original, assign },
      writable: true,
      configurable: true,
    });
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ error: 'Invalid email or password.' }, 401))
    );

    await expect(api.post('/api/auth/login', { email: 'x', password: 'y' })).rejects.toThrow(
      'Invalid email or password.'
    );
    expect(assign).not.toHaveBeenCalled();

    Object.defineProperty(window, 'location', { value: original, configurable: true });
  });

  it('BUG-02: a 401 on a noExpiryRedirect call (logout) refreshes once but never redirects', async () => {
    setSession('dead-after-password-change', { userId: 1 });
    const assign = vi.fn();
    const original = window.location;
    Object.defineProperty(window, 'location', {
      value: { ...original, assign },
      writable: true,
      configurable: true,
    });
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      api.post('/api/auth/logout', undefined, { noExpiryRedirect: true })
    ).rejects.toMatchObject({ status: 401 });
    expect(assign).not.toHaveBeenCalled();
    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/auth/refresh')).toHaveLength(1);

    Object.defineProperty(window, 'location', { value: original, configurable: true });
  });
});
