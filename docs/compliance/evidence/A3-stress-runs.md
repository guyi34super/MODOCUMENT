# A3 — Stress test runs (MO-016 / MO-017, SEC-002, SEC-003, SEC-008)

Runs made for this pack on 2026-10-19 by Agent A3, on the shared sandbox (4 vCPU Intel Xeon
2.8 GHz, 15.7 GiB RAM, Node 22.22.0, Java 21, PostgreSQL 16.14 scratch cluster on
`127.0.0.1:55432` with `fsync=off`). Synthetic data only. The stack is the e2e one
(`e2e/run-local.sh`): fresh database per run, production profile, RLS role `modocument_app`,
fake LLM; the database and run secrets are dropped at the end of each run.

**Build under test.** `target/modocument-1.2.0.jar` and `frontend/dist` copied from the main
checkout's current build (`SKIP_BUILD=1`): jar built 2026-10-18 12:35 UTC from commit `660cff7`
(`META-INF/build-info.properties`, `BOOT-INF/classes/git.properties` inside the jar),
185,084,467 bytes. `git diff 660cff7 eb53e97 -- src frontend/src pom.xml` is empty, so the
application code equals the pack commit `eb53e97`.

**Driver.** `perf/stress/run.mjs` (scenarios in `perf/stress/scenarios.mjs`, engine
`perf/stress/lib/engine.mjs`, monitor `perf/stress/lib/monitor.mjs`, seeding
`perf/stress/lib/setup.mjs`, synthetic inputs `perf/stress/lib/fixtures.mjs`). A quick smoke of
the driver itself (`STRESS_QUICK=1`) was run first (16:28–16:35 UTC); its output was discarded.

## Runs

| # | Label | Window (UTC) | Command (from the repo root) | Raw output |
|---|---|---|---|---|
| 1 | `main` | 16:35:40 – 17:11:55 | `STRESS_LABEL=main MODOCUMENT_RATELIMIT_UPLOADS=200 MODOCUMENT_RATELIMIT_INGESTION=600 MODOCUMENT_RATELIMIT_EXPORTS=600 MODOCUMENT_RATELIMIT_DOC_GENERATE=300 E2E_JAVA_OPTS="-Xmx1971m -XX:+UseG1GC" SKIP_BUILD=1 E2E_RUN_CMD='node perf/stress/run.mjs' e2e/run-local.sh` | `perf/results/stress-2026-10/main/` |
| 2 | `default-limits` | 17:12:35 – 17:13:09 | `STRESS_LABEL=default-limits STRESS_USERS=5 STRESS_SCENARIOS=s5a E2E_JAVA_OPTS="-Xmx1971m -XX:+UseG1GC" SKIP_BUILD=1 E2E_RUN_CMD='node perf/stress/run.mjs' e2e/run-local.sh` | `perf/results/stress-2026-10/default-limits/` |
| 3 | `one-cpu` | 17:13:45 – 17:30:04 | `STRESS_LABEL=one-cpu STRESS_PIN_CPUS=3 STRESS_USERS=60 STRESS_SCENARIOS=s4-lite,s2-break,s6 STRESS_BREAKPOINT_STEPS=5,10,25,50,100,150,200,300 MODOCUMENT_RATELIMIT_UPLOADS=200 MODOCUMENT_RATELIMIT_INGESTION=600 MODOCUMENT_RATELIMIT_EXPORTS=600 MODOCUMENT_RATELIMIT_DOC_GENERATE=300 E2E_JAVA_OPTS="-Xmx1971m -XX:+UseG1GC -XX:ActiveProcessorCount=1" SKIP_BUILD=1 E2E_RUN_CMD='node perf/stress/run.mjs' e2e/run-local.sh` | `perf/results/stress-2026-10/one-cpu/` |
| 4 | `retest-s5b` | 17:30:47 – 17:31:25 | `STRESS_LABEL=retest-s5b STRESS_USERS=20 STRESS_SCENARIOS=s5b MODOCUMENT_RATELIMIT_UPLOADS=200 MODOCUMENT_RATELIMIT_INGESTION=600 MODOCUMENT_RATELIMIT_EXPORTS=600 MODOCUMENT_RATELIMIT_DOC_GENERATE=300 E2E_JAVA_OPTS="-Xmx1971m -XX:+UseG1GC" SKIP_BUILD=1 E2E_RUN_CMD='node perf/stress/run.mjs' e2e/run-local.sh` | `perf/results/stress-2026-10/retest-s5b/` |

Every run exited 0 (`playwright exit status: 0` from the harness, which reports the run
command's status).

**Configuration changes for the runs.** Rate limits raised in runs 1, 3, 4 through the
environment, within each setting's hard maximum (`docs/settings/catalogue.md`): uploads 200,
ingestion 600, exports 600, doc-generate 300 per user per hour (defaults 20, 60, 60, 30). Reason:
60 users × 45 minutes would otherwise measure the hourly buckets, not the system. Run 2 used the
defaults to prove the 429 behaviour. Sign-in limits were the harness values for every e2e run
(per IP 1,000, per e-mail 200 per 15 minutes, `e2e/run-local.sh`). Heap 1,971 MiB = 70 % of the
production app container (2,816 MiB). Nothing was changed in application code.

## Files per run

| File | Content |
|---|---|
| `run.json` | Start/end, machine and load average at both ends, JVM flags and CPU affinity, DB role mode, rate-limit overrides, pool size, thresholds, per-scenario windows and request counts |
| `run-output.txt` | Driver console log, one line per stage |
| `seed.json` | Seed duration and request statistics |
| `<scenario>.summary.json` | Per stage: totals, p50/p90/p95/p99/max, status codes, per request name, per class with threshold verdicts, probe, resource summary; scenario extras (S3 sizes, S5a probes, S5b outcomes) |
| `<scenario>.requests.csv.gz` | Raw per-request samples: `epoch_ms,name,class,ms,status,bytes,ok,error` |
| `<scenario>.timeline.csv` | Per-second requests, errors, 429s, p50/p95/max, JVM CPU %, RSS, threads, box CPU %, load, DB connections |
| `resources.csv` | 1-second resource samples for the whole run |
| `jstat-gcutil.txt` | `jstat -gcutil -t <pid> 5000` for the whole run |

Scenario keys: `s1`, `s2`, `s2-break`, `s3`, `s4`, `s4-lite`, `soak`, `s5a`, `s5b`,
`s5b-recovery`, `s5c`, `s6` (definitions in `docs/compliance/mo-016-stress-test-plan.md` §6).

## Backend log observations (not committed; kept in `e2e/.run/<run-id>/backend.log`, git-ignored)

| Run | Observation |
|---|---|
| `main` | 0 ERROR lines; 1 Hikari "Apparent connection leak detected" at 16:44:16 from `TemplateRecordIndexer.reindex` on the pipeline thread after the 50,000-row job, returned at 16:44:21 ("unleaked"); 1 "AI circuit OPENED after 5 consecutive transport/server failures" (S5b); 157 `export-burst` security alerts (expected: one user exporting repeatedly in S2/S4) |
| `default-limits` | 0 ERROR lines |
| `one-cpu` | 0 ERROR lines, no leak warning |
| `retest-s5b` | 0 ERROR lines; 1 "AI circuit OPENED" (S5b), no leak warning |

## Re-reading the numbers

`python3` over a summary prints any table of the report, e.g.
`python3 -c "import json;d=json.load(open('perf/results/stress-2026-10/main/s6.summary.json'));[print(s['label'],s['all']['rps'],s['all']['p95'],s['all']['errorRate']) for s in d['stages']]"`.
Raw samples: `zcat perf/results/stress-2026-10/main/s4.requests.csv.gz | head`.
