#!/usr/bin/env node
// Backend test-suite profile from one `mvn -B verify` run:
//
//   node perf/test-suite.mjs <mvn-verify.log> [surefire-reports-dir] [wall-seconds]
//
// - Spring contexts: every "Started <TestClass> in X seconds" line is one
//   ApplicationContext being built (a cache miss: a new configuration, or a
//   reload after an LRU eviction from the 32-entry context cache).
// - per-class times: the `time` attribute of each TEST-*.xml testsuite.
import fs from 'node:fs';
import path from 'node:path';
import { REPO_ROOT, machineInfo, printTable, writeResult } from './lib.mjs';

const [logFile, reportsArg, wallArg] = process.argv.slice(2);
if (!logFile) {
  console.error('usage: node perf/test-suite.mjs <mvn-verify.log> [surefire-reports-dir] [wall-seconds]');
  process.exit(2);
}
const reports = reportsArg || path.join(REPO_ROOT, 'target', 'surefire-reports');
const log = fs.readFileSync(logFile, 'utf8');

const contexts = [...log.matchAll(/Started (\S+) in ([\d.]+) seconds/g)].map((m) => ({ by: m[1], seconds: Number(m[2]) }));
const totalTime = log.match(/Total time:\s+(.+)/);
const testsLine = [...log.matchAll(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$/gm)].at(-1);

const classes = fs
  .readdirSync(reports)
  .filter((f) => /^TEST-.*\.xml$/.test(f))
  .map((f) => {
    const head = fs.readFileSync(path.join(reports, f), 'utf8').slice(0, 4000);
    const suite = head.match(/<testsuite\b[^>]*>/)?.[0] || '';
    const attr = (name) => suite.match(new RegExp(`\\b${name}="([^"]*)"`))?.[1];
    return { name: attr('name'), seconds: Number(attr('time') || 0), tests: Number(attr('tests') || 0) };
  })
  .sort((a, b) => b.seconds - a.seconds);

const result = {
  machine: machineInfo(),
  log: path.basename(logFile),
  mavenTotalTime: totalTime ? totalTime[1].trim() : null,
  wallSeconds: wallArg ? Number(wallArg) : null,
  tests: testsLine ? { run: +testsLine[1], failures: +testsLine[2], errors: +testsLine[3], skipped: +testsLine[4] } : null,
  springContextsStarted: contexts.length,
  springContextStartSeconds: Number(contexts.reduce((s, c) => s + c.seconds, 0).toFixed(1)),
  contexts,
  testClasses: classes.length,
  classSecondsSum: Number(classes.reduce((s, c) => s + c.seconds, 0).toFixed(1)),
  classes,
};
writeResult(process.env.PERF_TESTSUITE_NAME || 'test-suite.json', result);
console.log(
  `[perf] ${result.tests?.run ?? '?'} tests, ${result.testClasses} classes, ` +
    `${result.springContextsStarted} Spring contexts started (${result.springContextStartSeconds} s), maven ${result.mavenTotalTime}`
);
printTable(['slowest classes', 's', 'tests'], classes.slice(0, 15).map((c) => [c.name, c.seconds, c.tests]));
