// Load engine for the MO-016/MO-017 stress runs (no dependencies: Node 20+
// fetch). Closed-model virtual users (VUs) driven through stages, every
// request recorded as one sample {at, name, cls, ms, status, bytes, ok, err}.
// Percentiles are exact (nearest rank over all samples), not estimated.
import fs from 'node:fs';
import zlib from 'node:zlib';
import { percentile, round } from '../../lib.mjs';

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
/** Uniform think time between lo and hi milliseconds. */
export const think = (lo, hi) => sleep(lo + Math.random() * (hi - lo));

/**
 * Proposed thresholds (MO-016 §7): p95 per request class, and the error
 * budget. `heavy` = generation, download, export, upload; `job` = upload to
 * VALIDATED.
 */
export const THRESHOLDS = {
  p95: { read: 500, write: 1000, auth: 1000, heavy: 3000, job: 60000 },
  p99: { read: 1500, write: 3000, auth: 2000, heavy: 8000, job: 120000 },
  maxErrorRate: 0.01,
};

export class Recorder {
  constructor() {
    this.samples = [];
  }

  push(sample) {
    this.samples.push(sample);
  }

  between(from, to) {
    return this.samples.filter((s) => s.at >= from && s.at < to);
  }

  /** Raw samples as gzipped CSV (the archived raw output). */
  writeCsvGz(file, from = 0, to = Infinity) {
    const lines = ['epoch_ms,name,class,ms,status,bytes,ok,error'];
    for (const s of this.samples) {
      if (s.at < from || s.at >= to) continue;
      lines.push([s.at, s.name, s.cls, s.ms.toFixed(1), s.status, s.bytes, s.ok ? 1 : 0, s.err ?? ''].join(','));
    }
    fs.writeFileSync(file, zlib.gzipSync(lines.join('\n') + '\n'));
    return lines.length - 1;
  }
}

/** Summary of a set of samples: totals, throughput, latency percentiles, status codes. */
export function summarize(samples, seconds) {
  const ms = samples.map((s) => s.ms);
  const statuses = {};
  let errors = 0;
  let rateLimited = 0;
  let bytes = 0;
  for (const s of samples) {
    const key = s.err ? `ERR:${s.err}` : String(s.status);
    statuses[key] = (statuses[key] || 0) + 1;
    if (!s.ok) errors += 1;
    if (s.status === 429) rateLimited += 1;
    bytes += s.bytes || 0;
  }
  return {
    requests: samples.length,
    rps: seconds > 0 ? round(samples.length / seconds, 2) : null,
    p50: round(percentile(ms, 50), 1),
    p90: round(percentile(ms, 90), 1),
    p95: round(percentile(ms, 95), 1),
    p99: round(percentile(ms, 99), 1),
    max: round(ms.length ? Math.max(...ms) : null, 1),
    errors,
    errorRate: samples.length ? round(errors / samples.length, 4) : 0,
    rateLimited,
    statuses,
    avgBytes: samples.length ? Math.round(bytes / samples.length) : 0,
  };
}

/** Summary per request name and per class, plus thresholds verdicts per class. */
export function breakdown(samples, seconds) {
  const byName = {};
  const byClass = {};
  for (const s of samples) {
    (byName[s.name] ||= []).push(s);
    (byClass[s.cls] ||= []).push(s);
  }
  const names = Object.fromEntries(
    Object.entries(byName)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([k, v]) => [k, { class: v[0].cls, ...summarize(v, seconds) }])
  );
  const classes = Object.fromEntries(
    Object.entries(byClass).map(([k, v]) => {
      const sum = summarize(v, seconds);
      const p95Limit = THRESHOLDS.p95[k];
      const p99Limit = THRESHOLDS.p99[k];
      return [
        k,
        {
          ...sum,
          p95Limit,
          p99Limit,
          p95Pass: p95Limit == null ? null : sum.p95 <= p95Limit,
          p99Pass: p99Limit == null ? null : sum.p99 <= p99Limit,
        },
      ];
    })
  );
  const all = summarize(samples, seconds);
  const latencyPass = Object.values(classes).every((c) => c.p95Pass !== false);
  return {
    all,
    errorBudgetPass: all.errorRate < THRESHOLDS.maxErrorRate,
    latencyPass,
    classes,
    names,
  };
}

/**
 * HTTP client: Bearer token per user (logs in, and again before the 15-min
 * access token expires), JSON or multipart bodies, every call recorded.
 */
export class Client {
  constructor({ baseURL, rec, timeoutMs = 60_000 }) {
    this.baseURL = baseURL.replace(/\/$/, '');
    this.rec = rec;
    this.timeoutMs = timeoutMs;
  }

  async login(user, { name = 'auth.login' } = {}) {
    const r = await this.call(null, 'POST', '/api/auth/login', {
      json: { email: user.email, password: user.password },
      name,
      cls: 'auth',
    });
    if (r.status === 200 && r.body?.token) {
      user.token = r.body.token;
      user.exp = jwtExp(r.body.token) ?? Date.now() / 1000 + 600;
    }
    return r;
  }

  async ensureToken(user) {
    if (user.token && user.exp - Date.now() / 1000 > 90) return;
    if (!user.loginPromise) {
      user.loginPromise = this.login(user, { name: 'auth.login.refresh' }).finally(() => {
        user.loginPromise = null;
      });
    }
    await user.loginPromise;
  }

  /**
   * @returns {Promise<{status:number, body:any, headers:Headers|null, ok:boolean, ms:number, err:string|null}>}
   */
  async call(user, method, path, { json, form, expect = [200], name, cls = 'read', raw = false, timeoutMs, record = true } = {}) {
    if (user) await this.ensureToken(user);
    const headers = { Accept: 'application/json' };
    if (user?.token) headers.Authorization = `Bearer ${user.token}`;
    let body;
    if (json !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(json);
    } else if (form !== undefined) {
      body = typeof form === 'function' ? form() : form;
    }
    const at = Date.now();
    const start = performance.now();
    let status = 0;
    let bytes = 0;
    let parsed = null;
    let err = null;
    let resHeaders = null;
    try {
      const res = await fetch(this.baseURL + path, {
        method,
        headers,
        body,
        signal: AbortSignal.timeout(timeoutMs ?? this.timeoutMs),
      });
      status = res.status;
      resHeaders = res.headers;
      const buf = Buffer.from(await res.arrayBuffer());
      bytes = buf.length;
      if (raw) parsed = buf;
      else if ((res.headers.get('content-type') || '').includes('json') && buf.length) {
        try {
          parsed = JSON.parse(buf.toString('utf8'));
        } catch {
          parsed = buf.toString('utf8');
        }
      } else parsed = buf.length < 4096 ? buf.toString('utf8') : null;
    } catch (e) {
      err = e.name === 'TimeoutError' || e.name === 'AbortError' ? 'timeout' : e.cause?.code || e.name || 'error';
    }
    const ms = performance.now() - start;
    const ok = !err && expect.includes(status);
    if (record) this.rec.push({ at, name: name || `${method} ${path}`, cls, ms, status, bytes, ok, err });
    return { status, body: parsed, headers: resHeaders, ok, ms, err };
  }
}

export function jwtExp(token) {
  try {
    const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
    return typeof payload.exp === 'number' ? payload.exp : null;
  } catch {
    return null;
  }
}

/**
 * Closed-model stages: stage i keeps `vus` workers looping `iterate(ctx)`
 * for `seconds`. Workers above the new target stop after their current
 * iteration; new ones start at once. Returns the stage windows
 * [{label, vus, start, end}] (epoch ms) for per-stage statistics.
 * `shouldStop(stageSummary)` (optional) ends the run early (breakpoint).
 */
export async function runStages({ stages, iterate, users, onStageEnd, log = console.log }) {
  let target = 0;
  let stopping = false;
  const running = new Map();
  const marks = [];

  const worker = async (i) => {
    const ctx = { i, iter: 0, user: users[i % users.length] };
    while (!stopping && i < target) {
      try {
        await iterate(ctx);
      } catch (e) {
        log(`[stress] VU ${i} iteration error: ${e.message}`);
        await sleep(500);
      }
      ctx.iter += 1;
    }
    running.delete(i);
  };

  for (const stage of stages) {
    const mark = { label: stage.label ?? `${stage.vus} VUs`, vus: stage.vus, start: Date.now(), end: null };
    target = stage.vus;
    for (let i = 0; i < stage.vus; i += 1) {
      if (!running.has(i)) running.set(i, worker(i));
    }
    log(`[stress]   stage "${mark.label}" (${stage.vus} VUs, ${stage.seconds}s)`);
    await sleep(stage.seconds * 1000);
    mark.end = Date.now();
    marks.push(mark);
    if (onStageEnd && (await onStageEnd(mark)) === 'stop') {
      log(`[stress]   stop condition met after "${mark.label}"`);
      break;
    }
  }
  stopping = true;
  target = 0;
  const drainStart = Date.now();
  await Promise.all([...running.values()]);
  return { marks, drainMs: Date.now() - drainStart };
}

/** Weighted random pick: [[weight, value], ...]. */
export function pick(weighted) {
  const total = weighted.reduce((sum, [w]) => sum + w, 0);
  let r = Math.random() * total;
  for (const [w, value] of weighted) {
    r -= w;
    if (r <= 0) return value;
  }
  return weighted[weighted.length - 1][1];
}
