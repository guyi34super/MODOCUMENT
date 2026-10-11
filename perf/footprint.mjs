#!/usr/bin/env node
// Footprint: the app jar (target/modocument-*.jar, from
// `mvn -B -DskipTests package`), its 10 largest nested jars, and the app
// startup time ("Started ... in X seconds") from a backend log.
//
//   node perf/footprint.mjs [backend.log]      (default: $E2E_BACKEND_LOG)
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { REPO_ROOT, machineInfo, printTable, writeResult } from './lib.mjs';

const MiB = 2 ** 20;

function findJar() {
  const dir = path.join(REPO_ROOT, 'target');
  const jar = fs
    .readdirSync(dir)
    .filter((f) => /^modocument-.*\.jar$/.test(f) && !f.includes('-plain') && !f.endsWith('-sources.jar'))
    .map((f) => path.join(dir, f))[0];
  if (!jar) throw new Error('no target/modocument-*.jar - run `mvn -B -DskipTests package` first');
  return jar;
}

/** Entries of a zip with their uncompressed size (unzip -l). */
function listZip(jar) {
  const out = execFileSync('unzip', ['-l', jar], { maxBuffer: 256 * MiB }).toString();
  return out
    .split('\n')
    .map((line) => line.match(/^\s*(\d+)\s+\S+\s+\S+\s+(.+)$/))
    .filter(Boolean)
    .map((m) => ({ size: Number(m[1]), name: m[2].trim() }))
    .filter((e) => !e.name.endsWith('/'));
}

function startup(logFile) {
  if (!logFile || !fs.existsSync(logFile)) return null;
  const line = fs
    .readFileSync(logFile, 'utf8')
    .split('\n')
    .find((l) => /Started \S+ in [\d.]+ seconds/.test(l));
  if (!line) return null;
  const m = line.match(/Started (\S+) in ([\d.]+) seconds \(process running for ([\d.]+)\)/);
  return m ? { app: m[1], startedInSeconds: Number(m[2]), processRunningSeconds: Number(m[3]) } : null;
}

const jar = findJar();
const entries = listZip(jar);
const libs = entries.filter((e) => e.name.startsWith('BOOT-INF/lib/')).sort((a, b) => b.size - a.size);
const result = {
  machine: machineInfo(),
  jar: path.relative(REPO_ROOT, jar),
  jarBytes: fs.statSync(jar).size,
  jarMiB: Number((fs.statSync(jar).size / MiB).toFixed(1)),
  nestedJars: libs.length,
  nestedJarsMiB: Number((libs.reduce((s, e) => s + e.size, 0) / MiB).toFixed(1)),
  appClassesMiB: Number(
    (entries.filter((e) => e.name.startsWith('BOOT-INF/classes/')).reduce((s, e) => s + e.size, 0) / MiB).toFixed(1)
  ),
  top10: libs.slice(0, 10).map((e) => ({ jar: e.name.replace('BOOT-INF/lib/', ''), MiB: Number((e.size / MiB).toFixed(1)) })),
  startup: startup(process.argv[2] || process.env.E2E_BACKEND_LOG),
};
writeResult('footprint.json', result);
console.log(`[perf] ${result.jar}: ${result.jarMiB} MiB, ${result.nestedJars} nested jars (${result.nestedJarsMiB} MiB)`);
printTable(['nested jar', 'MiB'], result.top10.map((e) => [e.jar, e.MiB]));
if (result.startup) {
  console.log(`[perf] startup: Started in ${result.startup.startedInSeconds} s (process running for ${result.startup.processRunningSeconds} s)`);
}
