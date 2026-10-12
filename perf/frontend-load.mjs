#!/usr/bin/env node
// Frontend cold load: a fresh browser context (empty cache) opens /login,
// signs in as the perf operator and waits for the dashboard ("Home") and the
// network to go idle. Records every response: type, raw (decoded) bytes,
// bytes as served (encoded body, + headers), the content-encoding and the
// cache-control the server sent. Also a gzip -6 estimate of each JS/CSS
// body (what nginx with gzip on would send). PERF_RUNS (3) cold runs; the
// byte counts are identical across runs, the timings are the median.
//
// Uses the e2e package's Playwright and its preinstalled Chromium
// (PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers); never `playwright install`.
import zlib from 'node:zlib';
import { getAccount } from '../e2e/lib/accounts.js';
import { sessionFor } from '../e2e/lib/api.js';
import { e2eRequire, machineInfo, outDir, percentile, printTable, readResult, writeResult, kb } from './lib.mjs';

const { chromium } = e2eRequire('@playwright/test');
const BASE = (process.env.E2E_BASE_URL || 'http://127.0.0.1:4173').replace(/\/$/, '');
const RUNS = Number(process.env.PERF_RUNS || 3);

function category(resp) {
  const type = resp.request().resourceType();
  const url = new URL(resp.url());
  if (type === 'script' || url.pathname.endsWith('.js')) return 'js';
  if (type === 'stylesheet' || url.pathname.endsWith('.css')) return 'css';
  if (type === 'document') return 'document';
  if (type === 'fetch' || type === 'xhr') return 'api';
  return type; // image, font, other
}

async function coldRun(email, password) {
  const browser = await chromium.launch();
  const context = await browser.newContext();
  const page = await context.newPage();
  const responses = [];
  let phase = 'login-page';
  page.on('response', (resp) => responses.push({ resp, phase }));
  // Own idle tracking: Playwright's 'networkidle' load state is reached once
  // per document, so after the SPA's client-side navigation to the
  // dashboard it would resolve at once, before the dashboard's API calls.
  let inFlight = 0;
  let lastActivity = Date.now();
  page.on('request', () => {
    inFlight += 1;
    lastActivity = Date.now();
  });
  const settled = () => {
    inFlight -= 1;
    lastActivity = Date.now();
  };
  page.on('requestfinished', settled);
  page.on('requestfailed', settled);
  const networkQuiet = async (quietMs = 500, timeoutMs = 120_000) => {
    const deadline = Date.now() + timeoutMs;
    while (inFlight > 0 || Date.now() - lastActivity < quietMs) {
      if (Date.now() > deadline) throw new Error(`network not quiet after ${timeoutMs} ms (${inFlight} in flight)`);
      await new Promise((resolve) => setTimeout(resolve, 25));
    }
    return lastActivity;
  };

  const t0 = Date.now();
  await page.goto(`${BASE}/login`);
  await page.getByRole('button', { name: 'Sign in' }).waitFor();
  const loginReadyMs = (await networkQuiet()) - t0;
  phase = 'sign-in+dashboard';
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  const t1 = Date.now();
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByRole('heading', { name: 'Home', level: 1 }).waitFor({ timeout: 60_000 });
  const dashboardVisibleMs = Date.now() - t1;
  // Last network activity of the dashboard load (its data calls done).
  const dashboardIdleMs = (await networkQuiet()) - t1;

  const rows = [];
  for (const { resp, phase: p } of responses) {
    const url = new URL(resp.url());
    let raw = null;
    try {
      raw = (await resp.body()).length;
    } catch {
      /* redirects / bodies not kept */
    }
    let gzipEstimate = null;
    const cat = category(resp);
    if ((cat === 'js' || cat === 'css') && raw != null) {
      gzipEstimate = zlib.gzipSync(await resp.body(), { level: 6 }).length;
    }
    const sizes = await resp.request().sizes().catch(() => null);
    const headers = await resp.allHeaders();
    rows.push({
      phase: p,
      category: cat,
      path: url.origin === BASE ? url.pathname + url.search : url.href,
      status: resp.status(),
      rawBytes: raw,
      servedBodyBytes: sizes ? sizes.responseBodySize : null,
      servedHeaderBytes: sizes ? sizes.responseHeadersSize : null,
      contentEncoding: headers['content-encoding'] || null,
      cacheControl: headers['cache-control'] || null,
      gzipEstimate,
    });
  }
  await browser.close();
  return { loginReadyMs, dashboardVisibleMs, dashboardIdleMs, rows };
}

function totals(rows, filter = () => true) {
  const pick = rows.filter(filter);
  const sum = (key) => pick.reduce((s, r) => s + (r[key] || 0), 0);
  return {
    requests: pick.length,
    rawBytes: sum('rawBytes'),
    servedBytes: sum('servedBodyBytes'),
    gzipEstimate: sum('gzipEstimate'),
  };
}

async function main() {
  const seed = readResult(outDir(), 'seed.json');
  if (!seed) throw new Error(`no seed.json in ${outDir()} - run perf/seed.mjs first`);
  await sessionFor(seed.user);
  const { password } = getAccount(seed.user);

  const runs = [];
  for (let i = 0; i < RUNS; i += 1) runs.push(await coldRun(seed.user, password));
  const rows = runs[0].rows;
  const byCat = {};
  for (const cat of [...new Set(rows.map((r) => r.category))]) byCat[cat] = totals(rows, (r) => r.category === cat);
  const output = {
    machine: machineInfo(),
    base: BASE,
    runs: RUNS,
    timingsMs: {
      loginReady: percentile(runs.map((r) => r.loginReadyMs), 50),
      dashboardVisible: percentile(runs.map((r) => r.dashboardVisibleMs), 50),
      dashboardIdle: percentile(runs.map((r) => r.dashboardIdleMs), 50),
    },
    total: totals(rows),
    loginPage: totals(rows, (r) => r.phase === 'login-page'),
    byCategory: byCat,
    initialJs: totals(rows, (r) => r.category === 'js' && r.phase === 'login-page'),
    responses: rows,
  };
  writeResult('frontend.json', output);
  printTable(
    ['category', 'requests', 'raw', 'as served', 'gzip-6 est.'],
    Object.entries(byCat).map(([cat, t]) => [cat, t.requests, kb(t.rawBytes), kb(t.servedBytes), t.gzipEstimate ? kb(t.gzipEstimate) : '—'])
  );
  console.log(
    `[perf] total requests ${output.total.requests}; login page ready ${output.timingsMs.loginReady} ms; ` +
      `dashboard visible ${output.timingsMs.dashboardVisible} ms, data loaded ${output.timingsMs.dashboardIdle} ms after sign-in (median of ${RUNS})`
  );
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
