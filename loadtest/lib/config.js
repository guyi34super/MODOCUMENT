// Shared configuration for the MoDocument k6 scenarios. Everything comes from
// environment variables (k6 -e NAME=value or the process environment), so no
// URL or credential is ever committed. See loadtest/README.md.

/** Required: the API origin under test, e.g. https://staging.modocument.example */
export const BASE_URL = requireEnv('BASE_URL').replace(/\/+$/, '');

/** Template the uploaded CSV is ingested against (its fields must match the CSV headers). */
export const TEMPLATE_ID = __ENV.TEMPLATE_ID || '';

/** Seconds between job-status polls, and how long to wait for a job overall. */
export const POLL_INTERVAL_S = num('POLL_INTERVAL_S', 2);
export const POLL_TIMEOUT_S = num('POLL_TIMEOUT_S', 120);

/** Think time between a virtual user's actions (seconds, uniformly 0.5x..1.5x). */
export const THINK_TIME_S = num('THINK_TIME_S', 3);

/**
 * Expected production PEAK, in scenario iterations per second (one iteration
 * = one user action from the main mix). The main scenario ramps to 2x this.
 * Derive it from the capacity model (see README "Sizing the run").
 */
export const EXPECTED_PEAK_IPS = num('EXPECTED_PEAK_IPS', 2);

/** Access tokens live 15 minutes; refresh a little before that. */
export const TOKEN_REFRESH_AFTER_S = num('TOKEN_REFRESH_AFTER_S', 12 * 60);

/** Latency SLOs (ms, p95) — the defaults are the proposed go-live targets. */
export const SLO = {
  p95ReadMs: num('P95_READ_MS', 500),
  p95WriteMs: num('P95_WRITE_MS', 2000),
  p95AuthMs: num('P95_AUTH_MS', 1000),
  p95ExportMs: num('P95_EXPORT_MS', 3000),
  maxErrorRate: num('MAX_ERROR_RATE', 0.01),
};

/**
 * Synthetic load-test users. Either USERS_FILE (a JSON array of
 * {"email","password"} — one per virtual user is ideal, see README) or a
 * single USER_EMAIL / USER_PASSWORD pair. These must be READ_WRITE users
 * WITHOUT MFA enrolled, in a bank that holds synthetic data only.
 */
export const USERS = loadUsers();

/** Thresholds shared by every scenario: error rate < 1% and per-flow p95s. */
export function thresholds(extra) {
  return Object.assign({
    http_req_failed: [`rate<${SLO.maxErrorRate}`],
    checks: [`rate>${1 - SLO.maxErrorRate}`],
    'http_req_duration{flow:auth}': [`p(95)<${SLO.p95AuthMs}`],
    'http_req_duration{flow:read}': [`p(95)<${SLO.p95ReadMs}`],
    'http_req_duration{flow:write}': [`p(95)<${SLO.p95WriteMs}`],
    'http_req_duration{flow:export}': [`p(95)<${SLO.p95ExportMs}`],
    rate_limited: ['count==0'],
  }, extra || {});
}

/** Uniform think time around THINK_TIME_S. */
export function thinkTime() {
  return THINK_TIME_S * (0.5 + Math.random());
}

function loadUsers() {
  if (__ENV.USERS_FILE) {
    // open() is only legal in the init context — this module is imported there.
    const parsed = JSON.parse(open(__ENV.USERS_FILE));
    if (!Array.isArray(parsed) || parsed.length === 0) {
      throw new Error('USERS_FILE must be a non-empty JSON array of {email, password}.');
    }
    return parsed;
  }
  return [{ email: requireEnv('USER_EMAIL'), password: requireEnv('USER_PASSWORD') }];
}

function requireEnv(name) {
  const value = __ENV[name];
  if (!value) {
    throw new Error(`Set ${name} (k6 run -e ${name}=...). See loadtest/README.md.`);
  }
  return value;
}

function num(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') {
    return fallback;
  }
  const value = Number(raw);
  if (Number.isNaN(value)) {
    throw new Error(`${name} must be a number, got "${raw}".`);
  }
  return value;
}
