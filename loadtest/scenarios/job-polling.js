// Scenario: many users watching job status — the read path the SPA polls
// while a job runs. setup() creates a small pool of jobs once; the VUs then
// poll them (and the job list) at a steady arrival rate.
//
//   k6 run -e BASE_URL=https://staging.example -e USERS_FILE=users.json \
//          -e TEMPLATE_ID=42 loadtest/scenarios/job-polling.js
//
// Job reads are owner-scoped: a user can only poll their OWN jobs, so the
// pool is created per user in setup() and each VU polls its user's jobs.

import http from 'k6/http';
import { check } from 'k6';
import { login, ensureSession, currentUser } from '../lib/api.js';
import { BASE_URL, USERS, thresholds, EXPECTED_PEAK_IPS } from '../lib/config.js';

const CSV = open(__ENV.CSV_FILE || '../data/sample.csv');
const JOBS_PER_USER = Number(__ENV.JOBS_PER_USER || 2);

export const options = {
  scenarios: {
    polling: {
      executor: 'constant-arrival-rate',
      rate: Math.max(1, Math.ceil(EXPECTED_PEAK_IPS * 5)),
      timeUnit: '1s',
      duration: __ENV.DURATION || '10m',
      preAllocatedVUs: 20,
      maxVUs: 200,
    },
  },
  thresholds: thresholds(),
};

// setup() runs once, as VU 0; it logs in as each user to create their jobs.
export function setup() {
  const jobsByEmail = {};
  for (const user of USERS) {
    const session = login(user);
    if (!session) {
      continue;
    }
    jobsByEmail[user.email] = [];
    for (let i = 0; i < JOBS_PER_USER; i++) {
      const id = createJob(session.token, i);
      if (id) {
        jobsByEmail[user.email].push(id);
      }
    }
  }
  return { jobsByEmail };
}

export default function (data) {
  const jobs = data.jobsByEmail[currentUser().email] || [];
  const s = ensureSession();
  const params = (name) => ({
    headers: { Authorization: `Bearer ${s.token}` },
    tags: { flow: 'read', name },
  });
  if (jobs.length > 0) {
    const id = jobs[Math.floor(Math.random() * jobs.length)];
    const res = http.get(`${BASE_URL}/api/ingestion/jobs/${id}`, params('ingestion_job_get'));
    check(res, { 'job status 200': (r) => r.status === 200 });
  }
  const list = http.get(`${BASE_URL}/api/ingestion/jobs`, params('ingestion_jobs_list'));
  check(list, { 'job list 200': (r) => r.status === 200 });
}

function createJob(token, i) {
  // lib/api.js uploadAndIngest() uses the per-VU session; setup() has none,
  // so this posts directly with the token just obtained.
  const unique = `setup-${i}-${Date.now()}`;
  const res = http.post(`${BASE_URL}/api/ingestion/jobs`, {
    file: http.file(CSV.replace(/__RUN_ID__/g, unique), `loadtest-${unique}.csv`, 'text/csv'),
    templateId: String(__ENV.TEMPLATE_ID || ''),
    autoRun: 'true',
    runUntil: 'VALIDATED',
  }, { headers: { Authorization: `Bearer ${token}` }, tags: { flow: 'write', name: 'setup' } });
  return res.status >= 200 && res.status < 300 ? res.json('ingestionJobId') : null;
}
