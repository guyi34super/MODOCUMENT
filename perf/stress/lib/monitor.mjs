// Resource monitor for the stress runs: once per interval it samples the
// backend JVM (CPU % from /proc/<pid>/stat, RSS and thread count from
// /proc/<pid>/status), the whole box (CPU busy % from /proc/stat, load
// average), the load generator's own CPU, and the database connections the
// app holds (pg_stat_activity of the run's database, by role and state).
// `jstat -gcutil` runs alongside for heap occupancy and GC time.
import fs from 'node:fs';
import os from 'node:os';
import { execSync, spawn } from 'node:child_process';
import { e2eRequire, round } from '../../lib.mjs';

const pg = e2eRequire('pg');
const CLK_TCK = 100;

/** PID of the backend JVM listening on `port` (run-local.sh starts it with --server.port=<port>). */
export function findJavaPid(port) {
  const out = execSync('ps -eo pid=,args=').toString();
  for (const line of out.split('\n')) {
    const m = line.trim().match(/^(\d+)\s+(.*)$/);
    if (!m) continue;
    const [, pid, args] = m;
    if (/\bjava\b/.test(args) && args.includes(`--server.port=${port}`) && args.includes('-jar')) return Number(pid);
  }
  return null;
}

function procCpuTicks(pid) {
  const stat = fs.readFileSync(`/proc/${pid}/stat`, 'utf8');
  const fields = stat.slice(stat.lastIndexOf(')') + 2).split(' ');
  return Number(fields[11]) + Number(fields[12]); // utime + stime
}

function procStatus(pid) {
  const status = fs.readFileSync(`/proc/${pid}/status`, 'utf8');
  const rss = Number(status.match(/VmRSS:\s+(\d+)/)?.[1] || 0);
  const threads = Number(status.match(/Threads:\s+(\d+)/)?.[1] || 0);
  return { rssMiB: round(rss / 1024, 1), threads };
}

function systemTicks() {
  const line = fs.readFileSync('/proc/stat', 'utf8').split('\n')[0];
  const v = line.trim().split(/\s+/).slice(1).map(Number);
  const idle = v[3] + (v[4] || 0);
  const total = v.reduce((a, b) => a + b, 0);
  return { idle, total };
}

export async function startMonitor({ javaPid, dbUrl, appRole = 'modocument_app', intervalMs = 1000, jstatFile }) {
  const rows = [];
  const db = new pg.Client({ connectionString: dbUrl });
  await db.connect();
  let prevJava = procCpuTicks(javaPid);
  let prevSys = systemTicks();
  let prevNode = process.cpuUsage();
  let prevT = performance.now();
  let busy = false;
  let jstat = null;
  if (jstatFile) {
    const out = fs.openSync(jstatFile, 'a');
    fs.writeSync(out, `# jstat -gcutil -t ${javaPid} ${intervalMs * 5} started ${new Date().toISOString()}\n`);
    jstat = spawn('jstat', ['-gcutil', '-t', String(javaPid), String(intervalMs * 5)], { stdio: ['ignore', out, out] });
  }

  const sample = async () => {
    if (busy) return;
    busy = true;
    try {
      const t = performance.now();
      const dt = (t - prevT) / 1000;
      const javaTicks = procCpuTicks(javaPid);
      const sys = systemTicks();
      const node = process.cpuUsage();
      const { rssMiB, threads } = procStatus(javaPid);
      let conns = { total: 0, active: 0, idle: 0, idleInTx: 0 };
      try {
        const { rows: r } = await db.query(
          `SELECT state, count(*)::int AS n FROM pg_stat_activity
            WHERE datname = current_database() AND usename = $1 GROUP BY state`,
          [appRole]
        );
        for (const { state, n } of r) {
          conns.total += n;
          if (state === 'active') conns.active += n;
          else if (state === 'idle') conns.idle += n;
          else if (state && state.startsWith('idle in transaction')) conns.idleInTx += n;
        }
      } catch {
        conns = null;
      }
      rows.push({
        at: Date.now(),
        javaCpuPct: round(((javaTicks - prevJava) / CLK_TCK / dt) * 100, 1),
        javaRssMiB: rssMiB,
        javaThreads: threads,
        sysCpuPct: round((1 - (sys.idle - prevSys.idle) / Math.max(1, sys.total - prevSys.total)) * 100, 1),
        load1: round(os.loadavg()[0], 2),
        nodeCpuPct: round(((node.user - prevNode.user + node.system - prevNode.system) / 1e6 / dt) * 100, 1),
        dbConnTotal: conns?.total ?? null,
        dbConnActive: conns?.active ?? null,
        dbConnIdleInTx: conns?.idleInTx ?? null,
      });
      prevJava = javaTicks;
      prevSys = sys;
      prevNode = node;
      prevT = t;
    } catch {
      /* the JVM may be gone at teardown */
    } finally {
      busy = false;
    }
  };

  const timer = setInterval(sample, intervalMs);
  return {
    rows,
    between(from, to) {
      return rows.filter((r) => r.at >= from && r.at < to);
    },
    async stop() {
      clearInterval(timer);
      jstat?.kill('SIGTERM');
      await db.end().catch(() => {});
    },
  };
}

/** Max / mean / last of the resource rows in a window. */
export function resourceSummary(rows) {
  if (!rows.length) return null;
  const stat = (key) => {
    const v = rows.map((r) => r[key]).filter((x) => typeof x === 'number');
    if (!v.length) return null;
    return { mean: round(v.reduce((a, b) => a + b, 0) / v.length, 1), max: round(Math.max(...v), 1), last: v[v.length - 1] };
  };
  return {
    samples: rows.length,
    javaCpuPct: stat('javaCpuPct'),
    javaRssMiB: stat('javaRssMiB'),
    javaThreads: stat('javaThreads'),
    sysCpuPct: stat('sysCpuPct'),
    load1: stat('load1'),
    nodeCpuPct: stat('nodeCpuPct'),
    dbConnTotal: stat('dbConnTotal'),
    dbConnActive: stat('dbConnActive'),
  };
}

export function writeResourceCsv(file, rows) {
  const keys = ['at', 'javaCpuPct', 'javaRssMiB', 'javaThreads', 'sysCpuPct', 'load1', 'nodeCpuPct', 'dbConnTotal', 'dbConnActive', 'dbConnIdleInTx'];
  fs.writeFileSync(file, [keys.join(','), ...rows.map((r) => keys.map((k) => r[k] ?? '').join(','))].join('\n') + '\n');
}
