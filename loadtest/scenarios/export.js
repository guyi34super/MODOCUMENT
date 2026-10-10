// Scenario: export validated rows as a file. Each VU ingests one CSV first
// (so it owns an exportable job — exports are owner-scoped), then exports it
// repeatedly in the configured formats.
//
//   k6 run -e BASE_URL=https://staging.example -e USERS_FILE=users.json \
//          -e TEMPLATE_ID=42 loadtest/scenarios/export.js
//
// Per-user limit: 60 exports per hour (modocument.ratelimit.exports), shared
// by file and database exports. With the default 3 s think time one VU makes
// ~20 exports a minute — so either give each VU its own user AND keep the
// run per user under the limit, or raise MODOCUMENT_RATELIMIT_EXPORTS on
// staging for the run (README "Rate limits during a run").

import { fail, sleep } from 'k6';
import { exportFile, pollJob, uploadAndIngest } from '../lib/api.js';
import { thresholds, thinkTime } from '../lib/config.js';

const CSV = open(__ENV.CSV_FILE || '../data/sample.csv');
const FORMATS = (__ENV.EXPORT_FORMATS || 'csv,xlsx,json').split(',');

export const options = {
  scenarios: {
    export: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 5),
      duration: __ENV.DURATION || '10m',
    },
  },
  thresholds: thresholds(),
};

// Per-VU: the job this VU exports from (created on its first iteration).
let jobId = null;

export default function () {
  if (jobId === null) {
    const created = uploadAndIngest(CSV, 'VALIDATED');
    const job = created ? pollJob(created) : null;
    if (!job || job.status !== 'COMPLETED') {
      fail('Could not prepare an exportable job (check TEMPLATE_ID and the CSV headers).');
    }
    jobId = created;
  }
  exportFile(jobId, FORMATS[__ITER % FORMATS.length].trim());
  sleep(thinkTime());
}
