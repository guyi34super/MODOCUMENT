#!/usr/bin/env node
// Seeds the perf data set on a running local stack (e2e/run-local.sh env):
// one bank user (the e2e READ_WRITE operator, no MFA) owning PERF_JOBS
// ingestion jobs of PERF_ROWS rows each, run through VALIDATED.
//
// Jobs go through the real API (upload -> parse -> map -> validate). If the
// API route is slower than PERF_SEED_API_BUDGET_S in total, the rest are
// cloned in SQL from the jobs already made (same owner, bank and JSON), so
// the read paths see identical payloads either way. The result file says
// how many came from each route.
//
// Env: the run-local.sh exports (E2E_BASE_URL, E2E_DB_URL, E2E_BOOTSTRAP_*,
// E2E_STATE_DIR, E2E_RUN_ID), PERF_JOBS (50), PERF_ROWS (1000),
// PERF_SEED_API_BUDGET_S (600), PERF_LABEL.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { bootstrapAdmin, operator, OPERATOR } from '../e2e/lib/seed.js';
import { createTemplate, uploadForIngestion } from '../e2e/lib/api.js';
import { withDb } from '../e2e/lib/db.js';
import { machineInfo, writeResult } from './lib.mjs';

const JOBS = Number(process.env.PERF_JOBS || 50);
const ROWS = Number(process.env.PERF_ROWS || 1000);
const API_BUDGET_MS = Number(process.env.PERF_SEED_API_BUDGET_S || 600) * 1000;

const DEPARTMENTS = ['Engineering', 'Research', 'Finance', 'Operations', 'Legal', 'Sales'];

/** Distinct content per job: the upload gate refuses a re-upload of identical content. */
function csv(rows, job) {
  const lines = ['EmployeeId,FullName,Department,Email,Salary,StartDate'];
  for (let i = 1; i <= rows; i += 1) {
    const day = String((i % 28) + 1).padStart(2, '0');
    const month = String((i % 12) + 1).padStart(2, '0');
    lines.push(
      [
        `E-${job}-${100000 + i}`,
        `Perf Person ${i}`,
        DEPARTMENTS[i % DEPARTMENTS.length],
        `perf.person${i}@example.test`,
        (30000 + ((i * 7919) % 90000)).toFixed(2),
        `20${String(10 + (i % 15)).padStart(2, '0')}-${month}-${day}`,
      ].join(',')
    );
  }
  return lines.join('\n') + '\n';
}

async function cloneJobs(sourceIds, count) {
  return withDb(async (db) => {
    const { rows: cols } = await db.query(
      `SELECT column_name FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'ingestionjob'
          AND column_name <> 'ingestionjobid' AND is_generated = 'NEVER'
        ORDER BY ordinal_position`
    );
    const names = cols.map((c) => c.column_name);
    const select = names.map((n) => (n === 'filename' ? `'clone-' || $2 || '-' || FileName` : n));
    const ids = [];
    for (let i = 0; i < count; i += 1) {
      const source = sourceIds[i % sourceIds.length];
      const { rows } = await db.query(
        `INSERT INTO IngestionJob (${names.join(', ')})
         SELECT ${select.join(', ')} FROM IngestionJob WHERE IngestionJobId = $1
         RETURNING IngestionJobId`,
        [source, String(i + 1)]
      );
      ids.push(rows[0].ingestionjobid);
    }
    return ids;
  });
}

async function main() {
  const started = Date.now();
  await bootstrapAdmin(); // first sign-in: forced password change + MFA enrolment
  const api = await operator(); // READ_WRITE bank user, cloned from the admin, no MFA
  const template = await createTemplate(api, {
    name: `Perf Employees ${process.env.E2E_RUN_ID || 'local'}`,
    fields: ['EmployeeId', 'FullName', 'Department', 'Email', 'Salary', 'StartDate'],
  });

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'modoc-perf-'));

  const apiIds = [];
  const uploadMs = [];
  while (apiIds.length < JOBS && Date.now() - started < API_BUDGET_MS) {
    const file = path.join(dir, `employees-${ROWS}-job${apiIds.length + 1}.csv`);
    fs.writeFileSync(file, csv(ROWS, apiIds.length + 1));
    const t0 = performance.now();
    const job = await uploadForIngestion(api, {
      filePath: file,
      templateId: template.templateId,
      runUntil: 'VALIDATED',
      wait: true,
    });
    uploadMs.push(Math.round(performance.now() - t0));
    apiIds.push(job.ingestionJobId);
    console.log(
      `[perf] job ${apiIds.length}/${JOBS} id=${job.ingestionJobId} step=${job.currentStep} status=${job.status} ${uploadMs.at(-1)} ms`
    );
  }
  const clonedIds = apiIds.length < JOBS ? await cloneJobs(apiIds, JOBS - apiIds.length) : [];
  fs.rmSync(dir, { recursive: true, force: true });

  const result = {
    machine: machineInfo(),
    user: OPERATOR(),
    templateId: template.templateId,
    jobs: JOBS,
    rowsPerJob: ROWS,
    viaApi: apiIds.length,
    viaSqlClone: clonedIds.length,
    uploadMs,
    jobIds: [...apiIds, ...clonedIds],
    seedSeconds: Math.round((Date.now() - started) / 1000),
  };
  writeResult('seed.json', result);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
