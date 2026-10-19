#!/usr/bin/env node
// MO-017 stress-run driver. Runs INSIDE e2e/run-local.sh (E2E_RUN_CMD), so
// the stack, its per-run secrets and its teardown are exactly the E2E ones:
//
//   SKIP_BUILD=1 E2E_KEEP=0 E2E_RUN_CMD='node perf/stress/run.mjs' e2e/run-local.sh
//
// Env:
//   STRESS_LABEL       results sub-folder (perf/results/stress-2026-10/<label>)
//   STRESS_SCENARIOS   comma list: s1,s2,s3,s5b,s4,soak,s5b-recovery,s5c,s6,s5a (default: main set)
//   STRESS_USERS       synthetic users to seed (60)
//   STRESS_PIN_CPUS    e.g. "3": pin the backend JVM to these CPUs (taskset) after start
//   STRESS_QUICK=1     tiny stages (smoke of the driver itself)
//   STRESS_STEP_S      breakpoint step length (40)
//   STRESS_SOAK_S      soak length (600)
//   STRESS_BREAKPOINT_STEPS  e.g. "5,10,25,50,100,150,200"
// Requests go straight to the backend port (E2E_API_URL), as perf/api-bench.mjs
// does: the SPA proxy (vite preview) is a test-only component, not the
// production nginx.
import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { machineInfo, PERF_DIR, REPO_ROOT, round } from '../lib.mjs';
import { breakdown, Client, Recorder, sleep, THRESHOLDS } from './lib/engine.mjs';
import { findJavaPid, resourceSummary, startMonitor, writeResourceCsv } from './lib/monitor.mjs';
import { loginAll, seedUsers, seedWorkingSet } from './lib/setup.mjs';
import { contractForm } from './lib/fixtures.mjs';
import { llm } from '../../e2e/lib/llm.js';
import * as S from './scenarios.mjs';

const LABEL = process.env.STRESS_LABEL || 'adhoc';
const OUT = path.join(PERF_DIR, 'results', 'stress-2026-10', LABEL);
fs.mkdirSync(OUT, { recursive: true });
const logFile = fs.openSync(path.join(OUT, 'run-output.txt'), 'a');
const log = (msg) => {
  const line = `${new Date().toISOString()} ${msg}`;
  console.log(line);
  fs.writeSync(logFile, line + '\n');
};

const SCENARIOS = {
  s1: ['S1 concurrent data entry', S.s1DataEntry],
  s2: ['S2 concurrent generation and export', S.s2Generation],
  s3: ['S3 large templates and files', S.s3Large],
  's2-break': ['S2-break generation only, no think time, stepped', S.s2GenerationBreak],
  s4: ['S4 peak mixed load (1x, 2x, spike, recovery)', S.s4Peak],
  's4-lite': ['S4-lite peak mix at 1x and 2x', (env) => S.s4Peak(env, { stages: [
    { vus: 25, seconds: 60, label: 'warm-up 25' },
    { vus: 50, seconds: 120, label: '1x peak 50' },
    { vus: 100, seconds: 120, label: '2x peak 100' },
    { vus: 10, seconds: 60, label: 'recovery 10' },
  ] })],
  soak: ['Soak (short) at 1x peak', S.soak],
  s5a: ['S5a rate limiting with default limits', S.s5RateLimit],
  s5b: ['S5b AI degradation and circuit breaker', S.s5Circuit],
  's5b-recovery': ['S5b circuit recovery after the open duration', S.s5CircuitRecovery],
  s5c: ['S5c DB connection pool pressure', S.s5DbPool],
  s6: ['S6 breakpoint (step load) and recovery', (env) => S.s6Breakpoint(env, {
    steps: process.env.STRESS_BREAKPOINT_STEPS ? process.env.STRESS_BREAKPOINT_STEPS.split(',').map(Number) : undefined,
  })],
};

function timeline(rec, mon, from, to) {
  const lines = ['second,requests,errors,rate_limited,p50_ms,p95_ms,max_ms,java_cpu_pct,java_rss_mib,java_threads,sys_cpu_pct,load1,db_conn_total,db_conn_active'];
  const samples = rec.between(from, to);
  const buckets = new Map();
  for (const s of samples) {
    const k = Math.floor((s.at - from) / 1000);
    (buckets.get(k) || buckets.set(k, []).get(k)).push(s);
  }
  const secs = Math.ceil((to - from) / 1000);
  for (let k = 0; k < secs; k += 1) {
    const b = buckets.get(k) || [];
    const ms = b.map((s) => s.ms).sort((x, y) => x - y);
    const q = (p) => (ms.length ? ms[Math.max(0, Math.ceil(ms.length * p) - 1)].toFixed(0) : '');
    const r = mon.rows.find((row) => row.at >= from + k * 1000 && row.at < from + (k + 1) * 1000) || {};
    lines.push([k, b.length, b.filter((s) => !s.ok).length, b.filter((s) => s.status === 429).length, q(0.5), q(0.95), ms.length ? ms[ms.length - 1].toFixed(0) : '',
      r.javaCpuPct ?? '', r.javaRssMiB ?? '', r.javaThreads ?? '', r.sysCpuPct ?? '', r.load1 ?? '', r.dbConnTotal ?? '', r.dbConnActive ?? ''].join(','));
  }
  return lines.join('\n') + '\n';
}

function probeLoop(client, rec) {
  // The public synthetic probe (one DB round trip), every 2 s: 30/min, under its 60/min per-IP cap.
  let stop = false;
  const done = (async () => {
    while (!stop) {
      await client.call(null, 'GET', '/api/probe/synthetic', { name: 'probe.synthetic', cls: 'probe', timeoutMs: 10_000 });
      await sleep(2000);
    }
  })();
  return async () => {
    stop = true;
    await done;
  };
}

async function main() {
  const quick = process.env.STRESS_QUICK === '1';
  const apiURL = process.env.E2E_API_URL;
  const port = new URL(apiURL).port;
  const javaPid = findJavaPid(port);
  if (!javaPid) throw new Error(`no backend JVM found for port ${port}`);
  const pin = process.env.STRESS_PIN_CPUS;
  if (pin) {
    execSync(`taskset -a -p -c ${pin} ${javaPid}`);
    log(`[stress] pinned backend JVM ${javaPid} to CPU(s) ${pin}`);
  }
  const rec = new Recorder();
  const client = new Client({ baseURL: apiURL, rec });
  const mon = await startMonitor({ javaPid, dbUrl: process.env.E2E_DB_URL, jstatFile: path.join(OUT, 'jstat-gcutil.txt') });
  const stopProbe = probeLoop(client, rec);
  const runId = process.env.E2E_RUN_ID || 'local';
  const jvmArgs = execSync(`ps -o args= -p ${javaPid}`).toString().trim().replace(/--server\.port=\d+/, '--server.port=<port>');
  const meta = {
    task: 'MO-017',
    label: LABEL,
    machine: machineInfo(),
    javaPid,
    jvm: { args: jvmArgs.split(' ').filter((a) => a.startsWith('-X') || a.startsWith('-XX')), affinity: execSync(`taskset -p ${javaPid}`).toString().trim().split(':').pop().trim() },
    dbAppRole: process.env.E2E_DB_APP_ROLE || 'rls',
    rateLimitOverrides: Object.fromEntries(Object.entries(process.env).filter(([k]) => k.startsWith('MODOCUMENT_RATELIMIT_'))),
    dbPoolMax: process.env.MODOCUMENT_DB_POOL_MAX || '20 (default)',
    thresholds: THRESHOLDS,
    startedAt: new Date().toISOString(),
    scenarios: [],
  };
  log(`[stress] label=${LABEL} java pid=${javaPid} affinity=${meta.jvm.affinity} jvm=${meta.jvm.args.join(' ')} overrides=${JSON.stringify(meta.rateLimitOverrides)}`);

  // ---- seed
  const seedStart = Date.now();
  const users = await seedUsers({ count: Number(process.env.STRESS_USERS || (quick ? 12 : 60)), runId });
  const loginMs = await loginAll(client, users, 10);
  await seedWorkingSet(client, users, { runId, docsPerUser: 3, rows: 200, log });
  const seedEnd = Date.now();
  const seedSummary = breakdown(rec.between(seedStart, seedEnd), (seedEnd - seedStart) / 1000);
  fs.writeFileSync(path.join(OUT, 'seed.json'), JSON.stringify({ users: users.length, loginAllMs: loginMs, seconds: round((seedEnd - seedStart) / 1000, 1), requests: seedSummary }, null, 2) + '\n');
  log(`[stress] seeded ${users.length} users in ${((seedEnd - seedStart) / 1000).toFixed(0)} s (initial logins ${loginMs} ms)`);

  const env = { client, rec, users, log, llm, quick, runId, formBytes: contractForm() };
  const wanted = (process.env.STRESS_SCENARIOS || 's1,s2,s3,s5b,s4,soak,s5b-recovery,s5c,s6').split(',').map((s) => s.trim()).filter(Boolean);
  for (const key of wanted) {
    const [title, fn] = SCENARIOS[key] || [];
    if (!fn) {
      log(`[stress] unknown scenario ${key}`);
      continue;
    }
    log(`[stress] === ${key}: ${title}`);
    await sleep(5000); // settle between scenarios
    const start = Date.now();
    let result;
    try {
      result = await fn(env);
    } catch (e) {
      log(`[stress] scenario ${key} aborted: ${e.stack}`);
      result = { windows: [], extra: { aborted: e.message } };
    }
    const end = Date.now();
    const stages = result.windows.map((w) => {
      const seconds = (w.end - w.start) / 1000;
      const samples = rec.between(w.start, w.end).filter((s) => s.cls !== 'probe');
      return {
        label: w.label,
        vus: w.vus,
        start: new Date(w.start).toISOString(),
        seconds: round(seconds, 1),
        ...breakdown(samples, seconds),
        probe: breakdown(rec.between(w.start, w.end).filter((s) => s.cls === 'probe'), seconds).all,
        resources: resourceSummary(mon.between(w.start, w.end)),
      };
    });
    const total = breakdown(rec.between(start, end).filter((s) => s.cls !== 'probe'), (end - start) / 1000);
    const summary = {
      key,
      title,
      start: new Date(start).toISOString(),
      end: new Date(end).toISOString(),
      seconds: round((end - start) / 1000, 1),
      total,
      resources: resourceSummary(mon.between(start, end)),
      stages,
      extra: result.extra ?? null,
    };
    fs.writeFileSync(path.join(OUT, `${key}.summary.json`), JSON.stringify(summary, null, 2) + '\n');
    fs.writeFileSync(path.join(OUT, `${key}.timeline.csv`), timeline(rec, mon, start, end));
    const n = rec.writeCsvGz(path.join(OUT, `${key}.requests.csv.gz`), start, end);
    meta.scenarios.push({ key, title, start: summary.start, end: summary.end, requests: n });
    for (const st of stages) {
      const a = st.all;
      log(`[stress]   ${st.label}: ${a.requests} req ${a.rps}/s p50 ${a.p50} p95 ${a.p95} p99 ${a.p99} max ${a.max} err ${(a.errorRate * 100).toFixed(2)}% 429=${a.rateLimited} cpu ${st.resources?.javaCpuPct?.mean}/${st.resources?.javaCpuPct?.max}% rss ${st.resources?.javaRssMiB?.max} MiB db ${st.resources?.dbConnTotal?.max}`);
    }
  }

  await stopProbe();
  await sleep(1500);
  await mon.stop();
  writeResourceCsv(path.join(OUT, 'resources.csv'), mon.rows);
  // jstat echoes the JVM's JAVA_TOOL_OPTIONS (environment, not results): keep only its own output.
  const jstatFile = path.join(OUT, 'jstat-gcutil.txt');
  if (fs.existsSync(jstatFile)) {
    fs.writeFileSync(jstatFile, fs.readFileSync(jstatFile, 'utf8').split('\n').filter((l) => !l.startsWith('Picked up')).join('\n'));
  }
  meta.endedAt = new Date().toISOString();
  meta.machineAtEnd = machineInfo();
  fs.writeFileSync(path.join(OUT, 'run.json'), JSON.stringify(meta, null, 2) + '\n');
  log(`[stress] done; results in ${path.relative(REPO_ROOT, OUT)}`);
}

main().catch((e) => {
  log(`[stress] FAILED: ${e.stack}`);
  process.exit(1);
});
