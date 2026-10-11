#!/usr/bin/env node
// API latency and payload benchmark (no dependencies; k6 is not installed).
//
// Talks to the backend directly (E2E_API_URL, i.e. no vite proxy hop) with a
// browser-like `Accept-Encoding: gzip, deflate, br`, and records for every
// case: p50/p95/min/max latency (time to the last body byte) and the response
// size on the wire and decoded. PERF_WARMUP (3) un-timed calls, then
// PERF_ITERATIONS (20) sequential timed calls per case.
//
// Cases: login, me, the ingestion list, one 1,000-row job (the poll), the
// dashboard's four list calls one by one and as the page fires them (in
// parallel), plus the Wave-2 endpoints (ingestion summary page, dashboard
// summary) whenever the server answers them with 200.
//
// Needs perf/seed.mjs to have run in the same stack (reads seed.json from
// the results dir and the operator's password from the e2e account book).
import http from 'node:http';
import https from 'node:https';
import zlib from 'node:zlib';
import { getAccount } from '../e2e/lib/accounts.js';
import { sessionFor } from '../e2e/lib/api.js';
import { machineInfo, outDir, percentile, printTable, readResult, round, writeResult, kb } from './lib.mjs';

const API = (process.env.E2E_API_URL || process.env.E2E_BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const WARMUP = Number(process.env.PERF_WARMUP || 3);
const ITERATIONS = Number(process.env.PERF_ITERATIONS || 20);

function decode(buffer, encoding) {
  switch ((encoding || '').toLowerCase()) {
    case 'gzip':
      return zlib.gunzipSync(buffer);
    case 'deflate':
      return zlib.inflateSync(buffer);
    case 'br':
      return zlib.brotliDecompressSync(buffer);
    default:
      return buffer;
  }
}

/** One HTTP exchange; resolves after the last body byte. */
function call(method, path, { token, json } = {}) {
  const url = new URL(API + path);
  const body = json === undefined ? null : Buffer.from(JSON.stringify(json));
  const headers = { Accept: 'application/json', 'Accept-Encoding': 'gzip, deflate, br' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body) {
    headers['Content-Type'] = 'application/json';
    headers['Content-Length'] = body.length;
  }
  const lib = url.protocol === 'https:' ? https : http;
  return new Promise((resolve, reject) => {
    const t0 = performance.now();
    const req = lib.request(url, { method, headers, agent: keepAlive(lib) }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const ms = performance.now() - t0;
        const wire = Buffer.concat(chunks);
        const encoding = res.headers['content-encoding'] || null;
        const raw = decode(wire, encoding);
        resolve({ status: res.statusCode, ms, wireBytes: wire.length, rawBytes: raw.length, encoding, raw, headers: res.headers });
      });
      res.on('error', reject);
    });
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

const agents = new Map();
function keepAlive(lib) {
  if (!agents.has(lib)) agents.set(lib, new lib.Agent({ keepAlive: true, maxSockets: 8 }));
  return agents.get(lib);
}

async function measure(name, fn, { optional = false } = {}) {
  const probe = await fn();
  if (probe.status !== 200) {
    if (optional) {
      console.log(`[perf] ${name}: HTTP ${probe.status}, not measured (endpoint absent on this build)`);
      return { name, status: probe.status, measured: false };
    }
    throw new Error(`${name}: HTTP ${probe.status} ${probe.raw?.toString().slice(0, 300)}`);
  }
  for (let i = 1; i < WARMUP; i += 1) await fn();
  const samples = [];
  let last = probe;
  for (let i = 0; i < ITERATIONS; i += 1) {
    last = await fn();
    if (last.status !== 200) throw new Error(`${name}: HTTP ${last.status} during the timed run`);
    samples.push(last.ms);
  }
  let shape = null;
  try {
    const parsed = JSON.parse(last.raw.toString('utf8'));
    shape = Array.isArray(parsed)
      ? `array[${parsed.length}]`
      : `object{${Object.keys(parsed).slice(0, 8).join(',')}}`;
  } catch {
    shape = 'non-json';
  }
  return {
    name,
    status: last.status,
    measured: true,
    iterations: ITERATIONS,
    p50ms: round(percentile(samples, 50)),
    p95ms: round(percentile(samples, 95)),
    minMs: round(Math.min(...samples)),
    maxMs: round(Math.max(...samples)),
    rawBytes: last.rawBytes,
    wireBytes: last.wireBytes,
    contentEncoding: last.encoding,
    cacheControl: last.headers['cache-control'] || null,
    shape,
  };
}

/** Several calls fired together (like Promise.allSettled in the SPA). */
function parallel(calls) {
  return async () => {
    const t0 = performance.now();
    const results = await Promise.all(calls.map((c) => c()));
    const failed = results.find((r) => r.status !== 200);
    return {
      status: failed ? failed.status : 200,
      ms: performance.now() - t0,
      wireBytes: results.reduce((s, r) => s + r.wireBytes, 0),
      rawBytes: results.reduce((s, r) => s + r.rawBytes, 0),
      encoding: [...new Set(results.map((r) => r.encoding || 'identity'))].join('+'),
      raw: Buffer.from('null'),
      headers: {},
    };
  };
}

async function main() {
  const seed = readResult(outDir(), 'seed.json');
  if (!seed) throw new Error(`no seed.json in ${outDir()} - run perf/seed.mjs first`);
  const email = seed.user;
  await sessionFor(email); // settles any pending password change
  const { password } = getAccount(email);
  const login = await call('POST', '/api/auth/login', { json: { email, password } });
  if (login.status !== 200) throw new Error(`login failed: ${login.status}`);
  const token = JSON.parse(login.raw.toString()).token;
  if (!token) throw new Error('login answered without a token (MFA user?) - the bench needs the non-MFA operator');

  // The poll target: an API-made job (full parsed/mapped/validated JSON).
  const jobId = seed.jobIds[0];
  const get = (p) => () => call('GET', p, { token });
  const dashboard = ['/Templates/GetAll', '/api/ingestion/jobs', '/api/extraction/jobs', '/ImportHistory/GetAll'];

  const results = [];
  results.push(await measure('POST /api/auth/login', () => call('POST', '/api/auth/login', { json: { email, password } })));
  results.push(await measure('GET /api/auth/me', get('/api/auth/me')));
  results.push(await measure('GET /api/ingestion/jobs', get('/api/ingestion/jobs')));
  results.push(await measure(`GET /api/ingestion/jobs/{id} (${seed.rowsPerJob} rows)`, get(`/api/ingestion/jobs/${jobId}`)));
  for (const path of dashboard) results.push(await measure(`dashboard: GET ${path}`, get(path)));
  results.push(await measure('dashboard: 4 calls in parallel (page load)', parallel(dashboard.map(get))));
  // Wave-2 endpoints (contracts §5); on the baseline build the summary query
  // parameters are ignored, so that row shows the legacy full list.
  results.push(
    await measure('GET /api/ingestion/jobs?page=0&size=50&view=summary', get('/api/ingestion/jobs?page=0&size=50&view=summary'), {
      optional: true,
    })
  );
  results.push(await measure('GET /api/dashboard/summary', get('/api/dashboard/summary'), { optional: true }));

  const output = {
    machine: machineInfo(),
    api: API,
    warmup: WARMUP,
    iterations: ITERATIONS,
    seed: { jobs: seed.jobs, rowsPerJob: seed.rowsPerJob, viaApi: seed.viaApi, viaSqlClone: seed.viaSqlClone, jobId },
    results,
  };
  writeResult('api.json', output);
  printTable(
    ['case', 'p50 ms', 'p95 ms', 'raw', 'wire', 'enc', 'shape'],
    results
      .filter((r) => r.measured)
      .map((r) => [r.name, r.p50ms, r.p95ms, kb(r.rawBytes), kb(r.wireBytes), r.contentEncoding || 'identity', r.shape])
  );
  for (const agent of agents.values()) agent.destroy();
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
