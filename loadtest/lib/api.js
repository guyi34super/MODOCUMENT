// MoDocument API calls used by the scenarios: authentication with a per-VU
// cached session, CSV upload + ingestion, job polling and export. Every
// request is tagged with a stable `name` (so URLs with ids aggregate into one
// metric) and a `flow` (auth | read | write | export) that the thresholds in
// config.js key on.

import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import {
  BASE_URL, POLL_INTERVAL_S, POLL_TIMEOUT_S, TEMPLATE_ID, TOKEN_REFRESH_AFTER_S, USERS,
} from './config.js';

/** 429s are counted separately: they mean the test hit a rate limit, not capacity. */
export const rateLimited = new Counter('rate_limited');
/** Wall-clock time from upload to the job reaching a terminal state. */
export const jobCompletion = new Trend('job_completion_ms', true);

const TERMINAL = ['COMPLETED', 'FAILED', 'CANCELLED'];

// One session per VU (module state is per-VU in k6).
let session = null;

/** The synthetic user this VU acts as (round-robin over USERS). */
export function currentUser() {
  return USERS[(__VU - 1) % USERS.length];
}

/** POST /api/auth/login — returns {token, issuedAt}. */
export function login(user) {
  const res = http.post(`${BASE_URL}/api/auth/login`,
    JSON.stringify({ email: user.email, password: user.password }),
    tagged('auth', 'login', { headers: { 'Content-Type': 'application/json' } }));
  noteRateLimit(res, 'login');
  const ok = check(res, {
    'login 200': (r) => r.status === 200,
    'login issued a token (no MFA challenge)': (r) => !!jsonField(r, 'token'),
  });
  if (!ok) {
    if (jsonField(res, 'mfaRequired')) {
      fail('Load-test users must not have MFA enrolled — use READ_WRITE synthetic users.');
    }
    return null;
  }
  return { token: jsonField(res, 'token'), issuedAt: Date.now() };
}

/**
 * POST /api/auth/refresh — rotates the httpOnly refresh cookie the login set
 * (k6's per-VU cookie jar carries it; the cookie is Secure, so BASE_URL must
 * be https for refresh to work).
 */
export function refresh() {
  const res = http.post(`${BASE_URL}/api/auth/refresh`, null, tagged('auth', 'refresh'));
  noteRateLimit(res, 'refresh');
  const ok = check(res, {
    'refresh 200': (r) => r.status === 200,
    'refresh issued a token': (r) => !!jsonField(r, 'token'),
  });
  return ok ? { token: jsonField(res, 'token'), issuedAt: Date.now() } : null;
}

/** A valid session for this VU: logs in once, refreshes before expiry. */
export function ensureSession() {
  if (session && (Date.now() - session.issuedAt) / 1000 < TOKEN_REFRESH_AFTER_S) {
    return session;
  }
  session = (session && refresh()) || login(currentUser());
  if (!session) {
    fail('Could not authenticate the load-test user.');
  }
  return session;
}

/** Upload a CSV through the multipart ingestion create; returns the job id. */
export function uploadAndIngest(csvContent, runUntil) {
  requireTemplate();
  const s = ensureSession();
  // The duplicate gate (C6) refuses same-owner + same-name + same-bytes, so
  // every upload gets a unique file name and the CSV's __RUN_ID__ placeholder
  // is replaced with a unique value (unique bytes).
  const unique = `${__VU}-${__ITER}-${Date.now()}`;
  const body = `${csvContent.trimEnd()}\n`.replace(/__RUN_ID__/g, unique);
  const res = http.post(`${BASE_URL}/api/ingestion/jobs`, {
    file: http.file(body, `loadtest-${unique}.csv`, 'text/csv'),
    templateId: String(TEMPLATE_ID),
    autoRun: 'true',
    runUntil: runUntil || 'VALIDATED',
  }, tagged('write', 'ingestion_create', auth(s)));
  noteRateLimit(res, 'ingestion_create');
  const ok = check(res, {
    'ingestion create 202': (r) => r.status === 202 || r.status === 200 || r.status === 201,
  });
  return ok ? jsonField(res, 'ingestionJobId') : null;
}

/** GET /api/ingestion/jobs/{id} until terminal; returns the last job view. */
export function pollJob(jobId) {
  const started = Date.now();
  let job = null;
  while ((Date.now() - started) / 1000 < POLL_TIMEOUT_S) {
    const s = ensureSession();
    const res = http.get(`${BASE_URL}/api/ingestion/jobs/${jobId}`,
      tagged('read', 'ingestion_job_get', auth(s)));
    noteRateLimit(res, 'ingestion_job_get');
    if (!check(res, { 'job status 200': (r) => r.status === 200 })) {
      return null;
    }
    job = res.json();
    if (TERMINAL.includes(job.status)) {
      jobCompletion.add(Date.now() - started);
      check(job, { 'job completed': (j) => j.status === 'COMPLETED' });
      return job;
    }
    sleep(POLL_INTERVAL_S);
  }
  check(job, { 'job finished within POLL_TIMEOUT_S': () => false });
  return job;
}

/** GET /api/ingestion/jobs/{id}/export/file?format=... */
export function exportFile(jobId, format) {
  const s = ensureSession();
  const res = http.get(
    `${BASE_URL}/api/ingestion/jobs/${jobId}/export/file?format=${format || 'csv'}`,
    tagged('export', 'export_file', auth(s)));
  noteRateLimit(res, 'export_file');
  return check(res, {
    'export 200': (r) => r.status === 200,
    'export has a body': (r) => (r.body || '').length > 0,
  });
}

/** Cheap authenticated reads the SPA issues on navigation. */
export function browse() {
  const s = ensureSession();
  const responses = http.batch([
    ['GET', `${BASE_URL}/api/auth/me`, null, tagged('read', 'auth_me', auth(s))],
    ['GET', `${BASE_URL}/api/ingestion/jobs`, null, tagged('read', 'ingestion_jobs_list', auth(s))],
    ['GET', `${BASE_URL}/Templates/GetAll`, null, tagged('read', 'templates_list', auth(s))],
  ]);
  responses.forEach((r) => noteRateLimit(r, 'browse'));
  return check(responses, { 'browse reads 200': (rs) => rs.every((r) => r.status === 200) });
}

function requireTemplate() {
  if (!TEMPLATE_ID) {
    fail('Set TEMPLATE_ID to a synthetic template whose fields match the CSV headers.');
  }
}

function auth(s) {
  return { headers: { Authorization: `Bearer ${s.token}` } };
}

function tagged(flow, name, params) {
  const p = params || {};
  p.tags = Object.assign({}, p.tags || {}, { flow, name });
  return p;
}

function noteRateLimit(res, name) {
  if (res && res.status === 429) {
    rateLimited.add(1, { name });
  }
}

function jsonField(res, field) {
  try {
    const body = res.json();
    return body ? body[field] : undefined;
  } catch (e) {
    return undefined;
  }
}
