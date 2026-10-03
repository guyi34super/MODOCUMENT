import { describe, expect, it, vi } from 'vitest';
import { api, clearSession, getStoredUser, getToken, setSession } from './client';

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
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

  it('falls back to a status message for non-JSON error bodies', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('boom', { status: 500 }))
    );

    await expect(api.get('/Templates/GetAll')).rejects.toThrow('Request failed (500)');
  });

  it('on 401 attempts one refresh, then clears the session and redirects to /login', async () => {
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
    expect(assign).toHaveBeenCalledWith('/login');

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
});
