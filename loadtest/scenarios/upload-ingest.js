// Scenario: upload a small CSV and run it through the ingestion pipeline
// (parse -> map -> validate), waiting for the job to finish.
//
//   k6 run -e BASE_URL=https://staging.example -e USERS_FILE=users.json \
//          -e TEMPLATE_ID=42 loadtest/scenarios/upload-ingest.js
//
// Per-user limit: 60 ingestion runs per hour (modocument.ratelimit.ingestion).
// Size the user pool so no single user exceeds it — README "Sizing the run".

import { sleep } from 'k6';
import { uploadAndIngest, pollJob } from '../lib/api.js';
import { thresholds, thinkTime } from '../lib/config.js';

// Relative to this script (k6 resolves open() against the script's directory).
const CSV = open(__ENV.CSV_FILE || '../data/sample.csv');

export const options = {
  scenarios: {
    ingest: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 5),
      duration: __ENV.DURATION || '10m',
    },
  },
  thresholds: thresholds({ job_completion_ms: ['p(95)<60000'] }),
};

export default function () {
  const jobId = uploadAndIngest(CSV, 'VALIDATED');
  if (jobId) {
    pollJob(jobId);
  }
  sleep(thinkTime());
}
