// Thin API client for the E2E suite: everything goes through the SPA origin
// (the same proxy the browser uses — vite preview locally, nginx in compose),
// so the API calls exercise the real edge path, not a side door.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { env } from './env.js';
import { nextCode } from './totp.js';
import { getAccount, jwtExp, saveAccount, usableToken } from './accounts.js';

export class ApiError extends Error {
  constructor(method, url, status, body) {
    const detail = typeof body === 'string' ? body : JSON.stringify(body);
    super(`${method} ${url} -> ${status}: ${detail?.slice(0, 500)}`);
    this.status = status;
    this.body = body;
  }
}

export class ApiClient {
  constructor({ baseURL = env.baseURL, token = null, label = 'anonymous' } = {}) {
    this.baseURL = baseURL.replace(/\/$/, '');
    this.token = token;
    this.label = label;
  }

  withToken(token, label = this.label) {
    return new ApiClient({ baseURL: this.baseURL, token, label });
  }

  /**
   * @param {string} method
   * @param {string} url path under the SPA origin, e.g. /api/auth/me
   * @param {{json?: any, form?: FormData, headers?: object, expect?: number|number[], bearer?: string}} [opts]
   * @returns {Promise<{status: number, body: any, headers: Headers}>}
   */
  async request(method, url, { json, form, headers = {}, expect, bearer } = {}) {
    const init = { method, headers: { Accept: 'application/json', ...headers } };
    const token = bearer ?? this.token;
    if (token) init.headers.Authorization = `Bearer ${token}`;
    if (json !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(json);
    } else if (form !== undefined) {
      init.body = form;
    }
    const res = await fetch(this.baseURL + url, init);
    const text = await res.text();
    let body = text;
    if ((res.headers.get('content-type') || '').includes('json') && text) {
      try {
        body = JSON.parse(text);
      } catch {
        /* keep the raw text */
      }
    }
    if (expect !== undefined) {
      const allowed = Array.isArray(expect) ? expect : [expect];
      if (!allowed.includes(res.status)) throw new ApiError(method, url, res.status, body);
    }
    return { status: res.status, body, headers: res.headers };
  }

  async get(url, opts) {
    return (await this.request('GET', url, { expect: 200, ...opts })).body;
  }

  async post(url, json, opts) {
    return (await this.request('POST', url, { json, expect: [200, 201, 202, 204], ...opts })).body;
  }

  async put(url, json, opts) {
    return (await this.request('PUT', url, { json, expect: [200, 202, 204], ...opts })).body;
  }

  async del(url, opts) {
    return (await this.request('DELETE', url, { expect: [200, 202, 204], ...opts })).body;
  }
}

/** A password the backend policy accepts (≥12 chars, not breached, no email part). */
export function strongPassword() {
  return `E2e-${crypto.randomBytes(15).toString('base64url')}-9z`;
}

/**
 * Two-phase sign-in exactly as the SPA does it:
 *   login -> (MFA challenge) -> [forced enrolment: enroll + activate] -> verify.
 * Persists the enrolment secret and the consumed TOTP step in the account book.
 * @returns {Promise<{token: string, user: object}>}
 */
export async function signIn(email, password) {
  const anon = new ApiClient();
  const login = await anon.request('POST', '/api/auth/login', {
    json: { email, password },
    expect: 200,
  });
  const data = login.body;
  if (!data.mfaRequired) return { token: data.token, user: data.user };

  const challenge = data.challenge;
  let account = getAccount(email);
  if (data.enrollmentRequired) {
    const enrollment = await anon.post('/api/auth/mfa/enroll', undefined, { bearer: challenge });
    account = saveAccount(email, { totpSecret: enrollment.secret, lastCounter: null });
    const { code, counter } = await nextCode(enrollment.secret, null);
    await anon.post('/api/auth/mfa/activate', { code }, { bearer: challenge });
    const verified = await anon.post('/api/auth/mfa/verify', { challenge, code });
    saveAccount(email, { lastCounter: counter });
    return { token: verified.token, user: verified.user };
  }
  if (!account?.totpSecret) {
    throw new Error(`${email} has MFA enabled but the suite does not know its TOTP secret`);
  }
  const { code, counter } = await nextCode(account.totpSecret, account.lastCounter);
  const verified = await anon.post('/api/auth/mfa/verify', { challenge, code });
  saveAccount(email, { lastCounter: counter });
  return { token: verified.token, user: verified.user };
}

/**
 * A ready-to-use API client for an account the suite knows: reuses a cached
 * token while it is valid, signs in otherwise, and completes a forced
 * first-login password change (then signs in again with the new password).
 */
export async function sessionFor(email, { refresh = false } = {}) {
  let account = getAccount(email);
  if (!account?.password) throw new Error(`unknown account ${email}; seed it first`);
  const cached = refresh ? null : usableToken(account);
  if (cached) {
    const probe = await new ApiClient({ token: cached }).request('GET', '/api/auth/me');
    if (probe.status === 200) return new ApiClient({ token: cached, label: email });
  }

  let session = await signIn(email, account.password);
  if (session.user?.mustChangePassword) {
    const newPassword = strongPassword();
    await new ApiClient({ token: session.token }).put('/api/auth/password', {
      currentPassword: account.password,
      newPassword,
    });
    account = saveAccount(email, { password: newPassword, passwordChangedAt: new Date().toISOString() });
    session = await signIn(email, newPassword);
  }
  saveAccount(email, { token: session.token, tokenExp: jwtExp(session.token), user: session.user });
  return new ApiClient({ token: session.token, label: email });
}

// ---------------------------------------------------------------------------
// Domain helpers (current main routes; phase-2 helpers land with their waves)
// ---------------------------------------------------------------------------

/** Maker submits USER_CREATE (202) — returns the PendingChange DTO. */
export async function submitUserCreate(maker, { email, displayName, role, temporaryPassword }) {
  const res = await maker.request('POST', '/api/admin/users', {
    json: { email, displayName, role, temporaryPassword },
    expect: 202,
  });
  return res.body;
}

export async function approveChange(checker, pendingChangeId, comment = 'e2e approval') {
  return checker.post(`/api/admin/changes/${pendingChangeId}/approve`, { comment });
}

export async function createTemplate(api, { name, description = '', fields }) {
  return api.post('/Templates/CreateWithFields', {
    name,
    description,
    fields: fields.map((field) =>
      typeof field === 'string'
        ? { fieldName: field, dataType: 'Text', rules: [], piiKind: null }
        : { rules: [], piiKind: null, dataType: 'Text', ...field }
    ),
  });
}

export async function uploadForIngestion(api, { filePath, templateId, runUntil = 'MAPPED', wait = true }) {
  const form = new FormData();
  const bytes = fs.readFileSync(filePath);
  form.append('file', new Blob([bytes]), path.basename(filePath));
  form.append('templateId', String(templateId));
  form.append('autoRun', 'true');
  form.append('runUntil', runUntil);
  form.append('wait', String(wait));
  return (await api.request('POST', '/api/ingestion/jobs', { form, expect: [200, 201, 202] })).body;
}

export async function getJob(api, jobId) {
  return api.get(`/api/ingestion/jobs/${jobId}`);
}

/** Polls a job until `predicate(job)` holds (or the job FAILS). */
export async function waitForJob(api, jobId, predicate, { timeoutMs = 120_000, intervalMs = 1000 } = {}) {
  const deadline = Date.now() + timeoutMs;
  let job;
  while (Date.now() < deadline) {
    job = await getJob(api, jobId);
    if (predicate(job)) return job;
    if (job.status === 'FAILED' || job.status === 'CANCELLED') {
      throw new Error(`job ${jobId} ended ${job.status}: ${job.errorMessage ?? ''}`);
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  throw new Error(`job ${jobId} did not reach the expected state in ${timeoutMs} ms (last: ${JSON.stringify({
    status: job?.status,
    currentStep: job?.currentStep,
  })})`);
}
