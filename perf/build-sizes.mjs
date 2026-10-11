#!/usr/bin/env node
// Frontend production build: runs `npm run build` in frontend/ (timed;
// PERF_SKIP_FRONTEND_BUILD=1 reuses frontend/dist) and lists every emitted
// asset with raw, gzip -6 and brotli sizes.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { execSync } from 'node:child_process';
import { REPO_ROOT, kb, machineInfo, printTable, writeResult } from './lib.mjs';

const FRONTEND = path.join(REPO_ROOT, 'frontend');
let buildSeconds = null;
if (process.env.PERF_SKIP_FRONTEND_BUILD !== '1') {
  const t0 = Date.now();
  execSync('npm run build', { cwd: FRONTEND, stdio: ['ignore', 'inherit', 'inherit'] });
  buildSeconds = Number(((Date.now() - t0) / 1000).toFixed(1));
}

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((d) =>
    d.isDirectory() ? walk(path.join(dir, d.name)) : [path.join(dir, d.name)]
  );
}

const dist = path.join(FRONTEND, 'dist');
const assets = walk(dist)
  .map((file) => {
    const body = fs.readFileSync(file);
    return {
      file: path.relative(dist, file),
      kind: path.extname(file).slice(1),
      rawBytes: body.length,
      gzipBytes: zlib.gzipSync(body, { level: 6 }).length,
      brotliBytes: zlib.brotliCompressSync(body).length,
    };
  })
  .sort((a, b) => b.rawBytes - a.rawBytes);

const sum = (list, key) => list.reduce((s, a) => s + a[key], 0);
const js = assets.filter((a) => a.kind === 'js');
const css = assets.filter((a) => a.kind === 'css');
const result = {
  machine: machineInfo(),
  buildSeconds,
  files: assets.length,
  js: { chunks: js.length, rawBytes: sum(js, 'rawBytes'), gzipBytes: sum(js, 'gzipBytes'), largestRawBytes: js[0]?.rawBytes ?? 0 },
  css: { files: css.length, rawBytes: sum(css, 'rawBytes'), gzipBytes: sum(css, 'gzipBytes') },
  assets,
};
writeResult('build.json', result);
printTable(
  ['asset', 'raw', 'gzip', 'brotli'],
  assets.filter((a) => a.kind === 'js' || a.kind === 'css' || a.kind === 'html').slice(0, 25).map((a) => [a.file, kb(a.rawBytes), kb(a.gzipBytes), kb(a.brotliBytes)])
);
console.log(`[perf] ${js.length} JS chunks, ${kb(result.js.rawBytes)} raw / ${kb(result.js.gzipBytes)} gzip; build ${buildSeconds ?? 'skipped'} s`);
