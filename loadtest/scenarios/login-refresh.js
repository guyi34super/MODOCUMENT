// Scenario: authentication throughput — login, then refresh-token rotation.
//
//   k6 run -e BASE_URL=https://staging.example -e USERS_FILE=users.json \
//          loadtest/scenarios/login-refresh.js
//
// Login and refresh are throttled per IP (AuthRateLimiter: 30 per 15 min by
// default) and login also per email (10 per 15 min). From a single load
// generator this scenario WILL hit 429s unless the staging limits are raised
// for the run window — see README "Rate limits during a run".

import { check, sleep } from 'k6';
import { login, refresh, currentUser } from '../lib/api.js';
import { thresholds, thinkTime, EXPECTED_PEAK_IPS } from '../lib/config.js';

export const options = {
  scenarios: {
    auth: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 200,
      stages: [
        { target: Math.max(1, Math.ceil(EXPECTED_PEAK_IPS)), duration: '2m' },
        { target: Math.max(1, Math.ceil(EXPECTED_PEAK_IPS)), duration: '5m' },
        { target: 0, duration: '1m' },
      ],
    },
  },
  thresholds: thresholds(),
};

export default function () {
  const session = login(currentUser());
  check(session, { 'logged in': (s) => s !== null });
  if (!session) {
    return;
  }
  sleep(thinkTime());
  // Rotates the refresh cookie set by the login above (per-VU cookie jar).
  check(refresh(), { 'refreshed': (s) => s !== null });
}
