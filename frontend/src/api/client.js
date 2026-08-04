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

async function request(path, { method = 'GET', body, raw = false } = {}) {
  const opts = { method, headers: {} };
  const token = getToken();
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

  if (res.status === 401 && !path.startsWith('/api/auth/login')) {
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
  get: (path) => request(path),
  post: (path, body) => request(path, { method: 'POST', body }),
  put: (path, body) => request(path, { method: 'PUT', body }),
  del: (path) => request(path, { method: 'DELETE' }),
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
