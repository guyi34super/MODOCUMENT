// Shared helpers for the perf scripts (no dependencies beyond Node 20+ and
// the e2e package's own node_modules: @playwright/test, pg).
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

export const PERF_DIR = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.resolve(PERF_DIR, '..');
/** Resolves packages from e2e/node_modules (the perf dir has none of its own). */
export const e2eRequire = createRequire(path.join(REPO_ROOT, 'e2e', 'package.json'));

/** Where results go: PERF_OUT_DIR, default perf/results/<label>. */
export function outDir() {
  const label = process.env.PERF_LABEL || 'adhoc';
  const dir = process.env.PERF_OUT_DIR || path.join(PERF_DIR, 'results', label);
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

export function writeResult(name, data) {
  const file = path.join(outDir(), name);
  fs.writeFileSync(file, JSON.stringify(data, null, 2) + '\n');
  console.log(`[perf] wrote ${path.relative(REPO_ROOT, file)}`);
  return file;
}

export function readResult(dir, name) {
  const file = path.join(dir, name);
  return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : null;
}

/** Nearest-rank percentile of a numeric array. */
export function percentile(values, p) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const rank = Math.ceil((p / 100) * sorted.length);
  return sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))];
}

export const round = (n, digits = 1) => (n == null ? null : Number(n.toFixed(digits)));

export function kb(bytes) {
  return bytes == null ? '—' : `${(bytes / 1024).toFixed(1)} KiB`;
}

/** Commit, host and load, attached to every result file. */
export function machineInfo() {
  let commit = null;
  let dirty = null;
  try {
    commit = execSync('git rev-parse --short HEAD', { cwd: REPO_ROOT }).toString().trim();
    dirty = execSync('git status --porcelain --untracked-files=no', { cwd: REPO_ROOT }).toString().trim() !== '';
  } catch {
    /* not a git checkout */
  }
  return {
    at: new Date().toISOString(),
    commit,
    dirtyTree: dirty,
    node: process.version,
    cpus: os.cpus().length,
    memGiB: round(os.totalmem() / 2 ** 30, 1),
    loadavg: os.loadavg().map((l) => round(l, 2)),
  };
}

/** Prints an aligned text table. */
export function printTable(headers, rows) {
  const widths = headers.map((h, i) => Math.max(String(h).length, ...rows.map((r) => String(r[i] ?? '').length)));
  const line = (cells) => cells.map((c, i) => String(c ?? '').padEnd(widths[i])).join('  ');
  console.log(line(headers));
  console.log(widths.map((w) => '-'.repeat(w)).join('  '));
  rows.forEach((r) => console.log(line(r)));
}
