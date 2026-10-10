// MAIN scenario (E7 / SEC-008): a production-like mix of user actions at an
// open arrival rate, ramping to TWICE the expected peak and holding there.
//
//   k6 run -e BASE_URL=https://staging.example -e USERS_FILE=/abs/users.json \
//          -e TEMPLATE_ID=42 -e EXPECTED_PEAK_IPS=3 \
//          --out json=results/main-mixed.json loadtest/scenarios/main-mixed.js
//
// One iteration = one user action drawn from the mix below. EXPECTED_PEAK_IPS
// is the busiest hour's action rate per second from the capacity model; the
// stages walk 0.5x -> 1x -> 2x and hold, so the report shows where latency
// bends and whether 2x peak still meets the thresholds (README "Capacity
// report").

import { sleep } from 'k6';
import { browse, exportFile, pollJob, uploadAndIngest } from '../lib/api.js';
import { EXPECTED_PEAK_IPS, thresholds, thinkTime } from '../lib/config.js';

const CSV = open(__ENV.CSV_FILE || '../data/sample.csv');

// Action mix (weights in percent). Tune to the observed production profile.
const MIX = [
  { name: 'browse', weight: Number(__ENV.MIX_BROWSE || 60) },
  { name: 'ingest', weight: Number(__ENV.MIX_INGEST || 25) },
  { name: 'export', weight: Number(__ENV.MIX_EXPORT || 15) },
];

const peak = EXPECTED_PEAK_IPS;
const RAMP = __ENV.RAMP || '5m';
const HOLD = __ENV.HOLD || '10m';

export const options = {
  scenarios: {
    mixed: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: Number(__ENV.PRE_VUS || 50),
      maxVUs: Number(__ENV.MAX_VUS || 500),
      stages: [
        { target: rate(peak * 0.5), duration: RAMP }, // warm-up
        { target: rate(peak), duration: RAMP },       // to expected peak
        { target: rate(peak), duration: HOLD },       // hold at peak
        { target: rate(peak * 2), duration: RAMP },   // to 2x peak
        { target: rate(peak * 2), duration: HOLD },   // hold at 2x peak
        { target: 0, duration: '2m' },                // ramp down
      ],
    },
  },
  thresholds: thresholds({
    job_completion_ms: [`p(95)<${Number(__ENV.P95_JOB_MS || 60000)}`],
    // Abort early if the system is clearly falling over (saves the run).
    'http_req_failed{flow:read}': [{ threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: '1m' }],
  }),
};

// Per-VU: the most recent completed job, reused by the export action.
let lastJobId = null;

export default function () {
  switch (pick()) {
    case 'ingest': {
      const id = uploadAndIngest(CSV, 'VALIDATED');
      const job = id ? pollJob(id) : null;
      if (job && job.status === 'COMPLETED') {
        lastJobId = id;
      }
      break;
    }
    case 'export':
      if (lastJobId !== null) {
        exportFile(lastJobId, 'csv');
      } else {
        browse(); // nothing exportable yet for this VU
      }
      break;
    default:
      browse();
  }
  sleep(thinkTime());
}

function pick() {
  const total = MIX.reduce((sum, m) => sum + m.weight, 0);
  let roll = Math.random() * total;
  for (const m of MIX) {
    roll -= m.weight;
    if (roll < 0) {
      return m.name;
    }
  }
  return MIX[0].name;
}

function rate(value) {
  return Math.max(1, Math.round(value));
}
