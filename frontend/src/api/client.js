const TOKEN_KEY = 'modoc.token';
const USER_KEY = 'modoc.user';

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

export function getStoredUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY));
  } catch {
    return null;
  }
}

export function setSession(token, user) {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function setStoredUser(user) {
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

/**
 * Validation endpoints answer with a list — `{ errors: [{ fieldName, ruleType,
 * message }] }` — rather than a single `error`. Without this every template
 * problem reached the user as "Request failed (400)".
 */
function errorList(data) {
  if (!data || !Array.isArray(data.errors) || data.errors.length === 0) return null;
  return data.errors
    .map((item) => {
      if (typeof item === 'string') return item;
      const text = item?.message || item?.error || '';
      const where = item?.fieldName ? `${item.fieldName}${item.ruleType ? ` (${item.ruleType})` : ''}` : '';
      if (where && text) return `${where}: ${text}`;
      return text || where;
    })
    .filter(Boolean)
    .join('\n');
}

/**
 * One in-flight refresh at a time: concurrent 401s all wait on the same
 * rotation instead of racing (a reused refresh token revokes the family
 * server-side). Resolves to the new access token, or null when the refresh
 * cookie is missing/expired/revoked.
 */
let refreshPromise = null;

function refreshAccessToken() {
  if (!refreshPromise) {
    refreshPromise = fetch('/api/auth/refresh', { method: 'POST', credentials: 'include' })
      .then(async (res) => {
        if (!res.ok) return null;
        const data = await res.json().catch(() => null);
        if (!data || !data.token) return null;
        setSession(data.token, data.user || getStoredUser());
        return data.token;
      })
      .catch(() => null)
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

/**
 * `authToken` carries an explicit bearer (the MFA challenge token during the
 * forced-enrolment flow) instead of the stored session token; those calls
 * must never trigger the silent-refresh/redirect dance — a 401 just surfaces
 * as an error on the login screen.
 */
async function request(path, { method = 'GET', body, raw = false, retried = false, authToken } = {}) {
  const opts = { method, headers: {} };
  const token = authToken || getToken();
  if (token) opts.headers.Authorization = `Bearer ${token}`;
  if (body !== undefined && body !== null) {
    if (body instanceof FormData) {
      opts.body = body; // browser sets the multipart boundary itself
    } else {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
  }

  const res = await fetch(path, opts);

  // The two-phase login endpoints answer 401 for a wrong code — that is
  // feedback for the login form, never a reason to bounce the browser.
  const isAuthEntryPoint =
    path.startsWith('/api/auth/login') ||
    path.startsWith('/api/auth/refresh') ||
    path.startsWith('/api/auth/mfa/verify');
  if (res.status === 401 && !isAuthEntryPoint && !authToken) {
    // The short-lived access token probably just expired: try ONE silent
    // refresh (httpOnly cookie) and replay the request with the new token.
    if (!retried) {
      const newToken = await refreshAccessToken();
      if (newToken) {
        return request(path, { method, body, raw, retried: true });
      }
    }
    clearSession();
    window.location.assign('/login');
    throw new Error('Session expired. Please sign in again.');
  }
  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try {
      const data = await res.json();
      const listed = errorList(data);
      if (listed) message = listed;
      else if (data && data.error) message = data.error;
      else if (data && data.message) message = data.message;
      else if (typeof data === 'string' && data) message = data;
    } catch {
      /* non-JSON error body */
    }
    throw new Error(message);
  }
  if (raw) return res;
  if (res.status === 204) return null;
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

export const api = {
  get: (path, opts = {}) => request(path, { ...opts }),
  post: (path, body, opts = {}) => request(path, { ...opts, method: 'POST', body }),
  put: (path, body, opts = {}) => request(path, { ...opts, method: 'PUT', body }),
  del: (path, body, opts = {}) => request(path, { ...opts, method: 'DELETE', body }),
};

/**
 * Fetch a protected file (plain links can't carry the Bearer header) and
 * trigger a browser download, using the server's Content-Disposition name.
 */
export async function downloadFile(path, fallbackName = 'export') {
  const res = await request(path, { raw: true });
  const blob = await res.blob();
  const disposition = res.headers.get('Content-Disposition') || '';
  const match = disposition.match(/filename\*?="?([^";]+)"?/);
  const name = match ? decodeURIComponent(match[1].replace(/^UTF-8''/, '')) : fallbackName;
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
