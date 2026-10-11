#!/usr/bin/env node
// Before/after table of two perf runs (perf/results/<label>/*.json).
//
//   node perf/compare.mjs baseline wave2 [--md]
//
// --md prints Markdown (paste into the sign-off); default is plain text.
// Rows whose data is missing on one side print "—" (e.g. the Wave-2
// summary endpoints on the baseline, or the suite timings when the run was
// made without PERF_TESTS=1).
import fs from 'node:fs';
import path from 'node:path';
import { PERF_DIR, printTable, readResult } from './lib.mjs';

const args = process.argv.slice(2);
const markdown = args.includes('--md');
const [beforeLabel, afterLabel] = args.filter((a) => !a.startsWith('--'));
if (!beforeLabel || !afterLabel) {
  console.error('usage: node perf/compare.mjs <before-label> <after-label> [--md]');
  process.exit(2);
}
const dirOf = (label) => (fs.existsSync(label) ? label : path.join(PERF_DIR, 'results', label));
const load = (label) => {
  const dir = dirOf(label);
  const meta = fs.existsSync(path.join(dir, 'run.meta'))
    ? Object.fromEntries(
        fs
          .readFileSync(path.join(dir, 'run.meta'), 'utf8')
          .split('\n')
          .filter((l) => l.includes('='))
          .map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)])
      )
    : {};
  return {
    api: readResult(dir, 'api.json'),
    frontend: readResult(dir, 'frontend.json'),
    build: readResult(dir, 'build.json'),
    footprint: readResult(dir, 'footprint.json'),
    suite: readResult(dir, 'test-suite.json'),
    meta,
  };
};
const before = load(beforeLabel);
const after = load(afterLabel);

const rows = [];
const fmt = (v, unit) => (v == null || Number.isNaN(v) ? '—' : `${typeof v === 'number' ? Number(v.toFixed(1)) : v}${unit ? ' ' + unit : ''}`);
const delta = (b, a) => (b == null || a == null || b === 0 ? '—' : `${a - b > 0 ? '+' : ''}${(((a - b) / b) * 100).toFixed(0)} %`);
function row(section, metric, b, a, unit = '') {
  rows.push([section, metric, fmt(b, unit), fmt(a, unit), delta(b, a)]);
}
const KB = (n) => (n == null ? null : n / 1024);

// --- API --------------------------------------------------------------------------------
const apiCase = (run, name) => run.api?.results.find((r) => r.name === name && r.measured);
const apiNames = [...new Set([...(before.api?.results || []), ...(after.api?.results || [])].map((r) => r.name))];
for (const name of apiNames) {
  const b = apiCase(before, name);
  const a = apiCase(after, name);
  row('API', `${name} p50`, b?.p50ms, a?.p50ms, 'ms');
  row('API', `${name} p95`, b?.p95ms, a?.p95ms, 'ms');
  row('API', `${name} bytes (raw)`, KB(b?.rawBytes), KB(a?.rawBytes), 'KiB');
  row('API', `${name} bytes (wire)`, KB(b?.wireBytes), KB(a?.wireBytes), 'KiB');
}

// Headlines: the Wave-2 replacement endpoints against what they replace.
// The jobs list "after" is the summary page when the build serves it as an
// envelope ({items,…}), else the full list; the dashboard "after" is the
// summary endpoint plus /Templates/GetAll when it exists, else the 4 calls.
{
  const summaryPage = (run) => {
    const r = apiCase(run, 'GET /api/ingestion/jobs?page=0&size=50&view=summary');
    return r && r.shape?.startsWith('object') ? r : apiCase(run, 'GET /api/ingestion/jobs');
  };
  const dashboardLoad = (run) => {
    const summary = apiCase(run, 'GET /api/dashboard/summary');
    return summary || apiCase(run, 'dashboard: 4 calls in parallel (page load)');
  };
  const b = apiCase(before, 'GET /api/ingestion/jobs');
  const a = summaryPage(after);
  row('Headline', `jobs list p50 (after: ${a?.name ?? '—'})`, b?.p50ms, a?.p50ms, 'ms');
  row('Headline', 'jobs list bytes (raw)', KB(b?.rawBytes), KB(a?.rawBytes), 'KiB');
  row('Headline', 'jobs list bytes (wire)', KB(b?.wireBytes), KB(a?.wireBytes), 'KiB');
  const db = dashboardLoad(before);
  const da = dashboardLoad(after);
  row('Headline', `dashboard data p50 (after: ${da?.name ?? '—'})`, db?.p50ms, da?.p50ms, 'ms');
  row('Headline', 'dashboard data bytes (raw)', KB(db?.rawBytes), KB(da?.rawBytes), 'KiB');
}

// --- Frontend ------------------------------------------------------------------------------
const fe = (run, fn) => (run.frontend ? fn(run.frontend) : null);
row('Frontend', 'cold login→dashboard requests', fe(before, (f) => f.total.requests), fe(after, (f) => f.total.requests));
for (const cat of ['js', 'css']) {
  row('Frontend', `${cat} requests`, fe(before, (f) => f.byCategory[cat]?.requests), fe(after, (f) => f.byCategory[cat]?.requests));
  row('Frontend', `${cat} raw`, fe(before, (f) => KB(f.byCategory[cat]?.rawBytes)), fe(after, (f) => KB(f.byCategory[cat]?.rawBytes)), 'KiB');
  row('Frontend', `${cat} as served`, fe(before, (f) => KB(f.byCategory[cat]?.servedBytes)), fe(after, (f) => KB(f.byCategory[cat]?.servedBytes)), 'KiB');
  row('Frontend', `${cat} gzip-6 est.`, fe(before, (f) => KB(f.byCategory[cat]?.gzipEstimate)), fe(after, (f) => KB(f.byCategory[cat]?.gzipEstimate)), 'KiB');
}
row('Frontend', 'initial JS (login page) raw', fe(before, (f) => KB(f.initialJs.rawBytes)), fe(after, (f) => KB(f.initialJs.rawBytes)), 'KiB');
row('Frontend', 'initial JS (login page) gzip est.', fe(before, (f) => KB(f.initialJs.gzipEstimate)), fe(after, (f) => KB(f.initialJs.gzipEstimate)), 'KiB');
row('Frontend', 'API calls on login→dashboard', fe(before, (f) => f.byCategory.api?.requests), fe(after, (f) => f.byCategory.api?.requests));
row('Frontend', 'API bytes on login→dashboard (raw)', fe(before, (f) => KB(f.byCategory.api?.rawBytes)), fe(after, (f) => KB(f.byCategory.api?.rawBytes)), 'KiB');
row('Frontend', 'login page ready (median)', fe(before, (f) => f.timingsMs.loginReady), fe(after, (f) => f.timingsMs.loginReady), 'ms');
row('Frontend', 'sign-in → dashboard visible (median)', fe(before, (f) => f.timingsMs.dashboardVisible), fe(after, (f) => f.timingsMs.dashboardVisible), 'ms');
row('Frontend', 'sign-in → dashboard data loaded (median)', fe(before, (f) => f.timingsMs.dashboardIdle), fe(after, (f) => f.timingsMs.dashboardIdle), 'ms');

// --- Build ------------------------------------------------------------------------------------
const bd = (run, fn) => (run.build ? fn(run.build) : null);
row('Build', 'JS chunks', bd(before, (b) => b.js.chunks), bd(after, (b) => b.js.chunks));
row('Build', 'JS total raw', bd(before, (b) => KB(b.js.rawBytes)), bd(after, (b) => KB(b.js.rawBytes)), 'KiB');
row('Build', 'JS total gzip', bd(before, (b) => KB(b.js.gzipBytes)), bd(after, (b) => KB(b.js.gzipBytes)), 'KiB');
row('Build', 'largest JS chunk raw', bd(before, (b) => KB(b.js.largestRawBytes)), bd(after, (b) => KB(b.js.largestRawBytes)), 'KiB');
row('Build', 'CSS raw', bd(before, (b) => KB(b.css.rawBytes)), bd(after, (b) => KB(b.css.rawBytes)), 'KiB');
row('Build', 'vite build time', bd(before, (b) => b.buildSeconds), bd(after, (b) => b.buildSeconds), 's');

// --- Footprint -----------------------------------------------------------------------------------
const fp = (run, fn) => (run.footprint ? fn(run.footprint) : null);
row('Footprint', 'app jar', fp(before, (f) => f.jarMiB), fp(after, (f) => f.jarMiB), 'MiB');
row('Footprint', 'nested jars', fp(before, (f) => f.nestedJars), fp(after, (f) => f.nestedJars));
row('Footprint', 'startup (Started in)', fp(before, (f) => f.startup?.startedInSeconds), fp(after, (f) => f.startup?.startedInSeconds), 's');
row('Footprint', 'startup (process running for)', fp(before, (f) => f.startup?.processRunningSeconds), fp(after, (f) => f.startup?.processRunningSeconds), 's');
const num = (v) => (v == null || v === '' ? null : Number(v));
row('Footprint', 'mvn -DskipTests package', num(before.meta.backend_package_seconds), num(after.meta.backend_package_seconds), 's');

// --- Test suites ------------------------------------------------------------------------------------
const ts = (run, fn) => (run.suite ? fn(run.suite) : null);
row('Tests', 'backend tests run', ts(before, (s) => s.tests?.run), ts(after, (s) => s.tests?.run));
row('Tests', 'Spring contexts started', ts(before, (s) => s.springContextsStarted), ts(after, (s) => s.springContextsStarted));
row('Tests', 'mvn -B verify wall', ts(before, (s) => s.wallSeconds), ts(after, (s) => s.wallSeconds), 's');
row('Tests', 'sum of class times', ts(before, (s) => s.classSecondsSum), ts(after, (s) => s.classSecondsSum), 's');
row('Tests', 'vitest run wall', num(before.meta.vitest_wall_seconds), num(after.meta.vitest_wall_seconds), 's');

const headers = ['area', 'metric', beforeLabel, afterLabel, 'Δ'];
const commits = `before ${before.api?.machine.commit ?? before.meta.commit ?? '?'} (load ${before.api?.machine.loadavg?.join(' ') ?? '?'}), ` +
  `after ${after.api?.machine.commit ?? after.meta.commit ?? '?'} (load ${after.api?.machine.loadavg?.join(' ') ?? '?'})`;
if (markdown) {
  console.log(`_${commits}_\n`);
  console.log(`| ${headers.join(' | ')} |`);
  console.log(`|${headers.map(() => '---').join('|')}|`);
  rows.forEach((r) => console.log(`| ${r.join(' | ')} |`));
} else {
  console.log(commits);
  printTable(headers, rows);
}
