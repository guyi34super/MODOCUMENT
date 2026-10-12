#!/usr/bin/env node
// Header checks against the real nginx config (driven by perf/nginx-check.sh,
// which renders frontend/nginx.conf and serves frontend/dist). Test plan §7:
//   C1/C2  /assets JS and CSS: Content-Encoding gzip, Vary: Accept-Encoding,
//          Cache-Control "public, max-age=31536000, immutable"
//   C3     a missing /assets chunk: 404 (never index.html), not cached immutable
//   C4     /, /index.html and a deep link (/admin/audit): 200 shell, no-cache
//   C5     API JSON through nginx: gzip above the min length, the backend's own
//          Cache-Control untouched (needs a live backend + perf seed user)
//   C6     the SPA security header set on every static answer (incl. the 404)
//          and exactly one CSP on the API answer
//   C8     /favicon.svg served (200, image/svg+xml)
// Also records the transferred (gzip) vs raw bytes of the initial JS/CSS.
// Writes the results JSON to argv[2]; exits 1 when a check fails.
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import zlib from 'node:zlib';
import { machineInfo, outDir, readResult } from './lib.mjs';

const BASE = (process.env.BASE || '').replace(/\/$/, '');
const HTML = process.env.PERF_NGINX_HTML;
const OUT = process.argv[2];
const SPA_HEADERS = [
  'content-security-policy',
  'x-content-type-options',
  'x-frame-options',
  'referrer-policy',
  'strict-transport-security',
  'permissions-policy',
  'cross-origin-opener-policy',
];
const IMMUTABLE = 'public, max-age=31536000, immutable';

function get(pathname, { method = 'GET', headers = {}, body = null } = {}) {
  const url = new URL(BASE + pathname);
  return new Promise((resolve, reject) => {
    const req = http.request(url, { method, headers: { 'Accept-Encoding': 'gzip', ...headers } }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const wire = Buffer.concat(chunks);
        const raw = res.headers['content-encoding'] === 'gzip' ? zlib.gunzipSync(wire) : wire;
        resolve({ status: res.statusCode, headers: res.headers, rawHeaders: res.rawHeaders, wire, raw });
      });
      res.on('error', reject);
    });
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

const checks = [];
function check(id, name, ok, detail) {
  checks.push({ id, name, ok: Boolean(ok), detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${name}${detail ? `  (${detail})` : ''}`);
}
const headerCount = (res, name) => res.rawHeaders.filter((h, i) => i % 2 === 0 && h.toLowerCase() === name).length;
const missingSpa = (res) => SPA_HEADERS.filter((h) => !res.headers[h]);

async function main() {
  const index = fs.readFileSync(path.join(HTML, 'index.html'), 'utf8');
  const entryJs = [...index.matchAll(/(?:src|href)="(\/assets\/[^"]+\.js)"/g)].map((m) => m[1]);
  const css = [...index.matchAll(/href="(\/assets\/[^"]+\.css)"/g)].map((m) => m[1]);
  const transfer = [];

  for (const asset of [...entryJs, ...css]) {
    const res = await get(asset);
    const id = asset.endsWith('.css') ? 'C2' : 'C1';
    transfer.push({ path: asset, status: res.status, rawBytes: res.raw.length, wireBytes: res.wire.length, encoding: res.headers['content-encoding'] || null });
    check(id, `${asset} gzip`, res.status === 200 && res.headers['content-encoding'] === 'gzip', `${res.wire.length} of ${res.raw.length} B`);
    check(id, `${asset} immutable`, res.headers['cache-control'] === IMMUTABLE, res.headers['cache-control']);
    check(id, `${asset} Vary`, /accept-encoding/i.test(res.headers.vary || ''), res.headers.vary);
    check('C6', `${asset} security headers`, missingSpa(res).length === 0, missingSpa(res).join(',') || 'all');
  }

  const missing = await get('/assets/does-not-exist-0000.js');
  check('C3', 'missing chunk is a real 404', missing.status === 404 && !missing.raw.toString().includes('<div id="root"'), `HTTP ${missing.status}`);
  check('C3', 'missing chunk not cached immutable', !String(missing.headers['cache-control'] || '').includes('immutable'), missing.headers['cache-control'] || 'none');
  check('C6', 'missing chunk security headers', missingSpa(missing).length === 0, missingSpa(missing).join(',') || 'all');

  for (const route of ['/', '/index.html', '/admin/audit', '/ingestion/42']) {
    const res = await get(route);
    check('C4', `${route} serves the shell`, res.status === 200 && res.raw.toString().includes('<div id="root"'), `HTTP ${res.status}`);
    check('C4', `${route} no-cache`, res.headers['cache-control'] === 'no-cache', res.headers['cache-control']);
    check('C6', `${route} security headers`, missingSpa(res).length === 0, missingSpa(res).join(',') || 'all');
  }

  const favicon = await get('/favicon.svg');
  check('C8', 'favicon served', favicon.status === 200 && /svg/.test(favicon.headers['content-type'] || ''), `HTTP ${favicon.status} ${favicon.headers['content-type']}`);
  check('C6', '/favicon.svg security headers', missingSpa(favicon).length === 0, missingSpa(favicon).join(',') || 'all');

  // C5: API JSON through nginx (needs the perf seed user of this stack).
  let api = { skipped: 'no perf seed in this results dir (run inside e2e/run-local.sh after perf/seed.mjs)' };
  const seed = readResult(outDir(), 'seed.json');
  if (seed) {
    const { getAccount } = await import('../e2e/lib/accounts.js');
    const { sessionFor } = await import('../e2e/lib/api.js');
    await sessionFor(seed.user);
    const { password } = getAccount(seed.user);
    const login = await get('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: seed.user, password }),
    });
    const token = JSON.parse(login.raw.toString()).token;
    const auth = { Authorization: `Bearer ${token}`, Accept: 'application/json' };
    api = {};
    for (const route of ['/api/ingestion/jobs?view=summary&page=0&size=50', '/api/dashboard/summary', `/api/ingestion/jobs/${seed.jobIds[0]}`]) {
      const res = await get(route, { headers: auth });
      const direct = await new Promise((resolve, reject) => {
        const req = http.request(new URL(process.env.E2E_API_URL + route), { headers: { ...auth, 'Accept-Encoding': 'gzip' } }, (r) => {
          r.resume();
          r.on('end', () => resolve(r.headers));
        });
        req.on('error', reject);
        req.end();
      });
      api[route] = { status: res.status, rawBytes: res.raw.length, wireBytes: res.wire.length, encoding: res.headers['content-encoding'] || null, cacheControl: res.headers['cache-control'] || null, backendCacheControl: direct['cache-control'] || null, backendEncoding: direct['content-encoding'] || null };
      check('C5', `${route} gzip through nginx`, res.status === 200 && res.headers['content-encoding'] === 'gzip', `${res.wire.length} of ${res.raw.length} B`);
      check('C5', `${route} Cache-Control is the backend's`, (res.headers['cache-control'] || null) === (direct['cache-control'] || null), `${res.headers['cache-control']} vs ${direct['cache-control']}`);
      check('C6', `${route} one CSP (the backend's)`, headerCount(res, 'content-security-policy') === 1, `${headerCount(res, 'content-security-policy')}`);
    }
  }

  const failed = checks.filter((c) => !c.ok);
  const output = { machine: machineInfo(), base: BASE, checks, transfer, api, failed: failed.length };
  fs.writeFileSync(OUT, JSON.stringify(output, null, 2) + '\n');
  console.log(`[perf] nginx checks: ${checks.length - failed.length}/${checks.length} passed -> ${OUT}`);
  process.exit(failed.length ? 1 : 0);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
