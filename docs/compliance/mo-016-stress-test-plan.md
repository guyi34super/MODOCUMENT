# MO-016 — Stress Test Plan

| Task | Deliverable | Author | Reviewer / approver | Date | Version |
|---|---|---|---|---|---|
| MO-016 | Stress Test Plan | Agent A3 for Bhavish Nobeen (Software Engineering) | Rushal | 2026-10-19 | 1.0 |

This plan defines how MoDocument is load- and stress-tested: what is tested, on which
environment, with which tools and data, under which load profiles, and what counts as a pass.
The run of this plan on the test box is reported in
[MO-017 — Stress Testing Report](mo-017-stress-testing-report.md). The plan also feeds controls
[SEC-008](controls/SEC-008.md) (stress testing report), [SEC-002](controls/SEC-002.md) (peak load
and degradation) and [SEC-003](controls/SEC-003.md) (autoscaling).

**Data rule.** Every test uses synthetic data only: generated users
(`stress-uNNN-<run>@e2e.modocument.test`), generated CSV rows ("Stress Person N",
`@example.test` addresses) and generated PDF forms (`perf/stress/lib/fixtures.mjs`). No client
data, real or masked, is ever loaded.

## 1. Objectives

1. Measure response times (p50, p95, p99, max), throughput and error rates of the main user
   flows under realistic concurrency.
2. Find the **breaking point**: the concurrency at which errors or latency leave the thresholds
   of §7, and the resource that saturates first (the bottleneck).
3. Show that overload is handled **gracefully**: per-user rate limits answer 429 with
   `Retry-After`, an AI outage falls back to code instead of failing jobs, and the service
   recovers by itself when the load drops (no cascading failure, no restart needed).
4. Measure the resource cost (JVM CPU and RSS, database connections) per unit of load, so the
   production task size and autoscaling caps in `infra/terraform/modules/ecs-app` can be checked.
5. Produce raw, archived outputs a reviewer can re-check, as SEC-008 requires.

## 2. Scope

| In scope | Out of scope (and why) |
|---|---|
| Sign-in (`POST /api/auth/login`, Argon2id), home dashboard, lists, job polling | Browser rendering time (measured separately by `perf/frontend-load.mjs`) |
| Document creation: autosave `PUT /api/doccreation/instances/{id}/values`, Save and validate, Generate, Download PDF, page preview | The connector agent and bank integrations (no bank simulator in this run) |
| PDF form templates: upload, confirm (many regions) | OCR of scanned images and LibreOffice conversion (not on the doc-creation PDF path; not installed on the test box) |
| Ingestion: CSV upload to VALIDATED, row edits `PUT /api/ingestion/jobs/{id}/rows`, CSV/XLSX export | Real LLM latency: a fake LLM is used (`e2e/stubs/fake-llm.mjs`) |
| Overload controls: `ApiRateLimitInterceptor`, `AuthRateLimiter`, `AiCircuitBreaker`, Hikari pool | WAF and CloudFront rate rules (`infra/terraform/modules/edge`): `NOT IN REPO (live cloud)` |
| Vertical limit of one application instance | Horizontal autoscaling events (ECS): `NOT IN REPO (live cloud)` |

## 3. Test environment

### 3.1 Where the tests run (this plan's first execution)

| Item | Test box (MO-017 run) | Proof |
|---|---|---|
| Machine | Shared sandbox container: 4 vCPU (Intel Xeon 2.8 GHz), ~15 GiB RAM, no swap. Other agents run builds and tests on the same box at the same time. | `perf/results/stress-2026-10/*/run.json` (`machine`) |
| Stack | `e2e/run-local.sh`: fresh database per run, backend jar in the production profile (Flyway, RLS), fake LLM, SPA behind `vite preview` | `e2e/run-local.sh` |
| Database | PostgreSQL 16 on `127.0.0.1:55432`, the sandbox scratch cluster, started with `fsync=off`; `max_connections` 100, `shared_buffers` 128 MB | `e2e/run-local.sh` (start line) |
| DB access mode | `rls`: the backend connects as `modocument_app` (NOBYPASSRLS), production two-role model | `e2e/run-local.sh` (`E2E_DB_APP_ROLE`) |
| JVM | Java 21, `-Xmx1971m -XX:+UseG1GC` (the heap a production task gets: 70 % of the 2,816 MiB app container), passed through `E2E_JAVA_OPTS` | `docker-entrypoint.sh`, `infra/terraform/modules/ecs-app/variables.tf` |
| DB pool | Hikari `maximum-pool-size` 20, `connection-timeout` 10 s (defaults) | `src/main/resources/application.yml` |
| Load generator | Node 22 on the same box, `perf/stress/run.mjs`, calling the backend port directly (as `perf/api-bench.mjs` does) | `perf/stress/run.mjs` |
| Production-shaped variant | Same stack with the JVM pinned to one CPU (`taskset`) and `-XX:ActiveProcessorCount=1`, to approximate one ECS task (`app_cpu` 896 of 1,024 units) | `infra/terraform/modules/ecs-app/variables.tf` |

### 3.2 Target environment (go-live re-run)

The production design is in `infra/terraform`: ECS on EC2 (`m6i.large` nodes, 2–4 instances),
tasks of 1,024 CPU units / 3,072 MiB, 2–8 tasks with CPU target tracking at 60 %
(`infra/terraform/modules/ecs-app/main.tf`), RDS PostgreSQL `db.m6g.large` Multi-AZ
(`infra/terraform/envs/production/variables.tf`, `infra/terraform/modules/rds/main.tf`),
CloudFront + WAF with a per-IP rate rule (`infra/terraform/modules/edge/main.tf`). Staging is
smaller: 1–2 tasks, `db.t4g.medium`, single-AZ (`infra/terraform/envs/staging/main.tf`).

**Results from the test box are indicative, not a production capacity figure.** The box is
shared, the database runs with `fsync=off` on the same machine, there is no TLS, ALB, nginx or
network hop, and the LLM is a stub. The go-live run of this plan on staging is a follow-up
(MO-017 §10) using the k6 scenarios in `loadtest/` (`loadtest/README.md`), which target the
same flows.

## 4. Tools

| Tool | Use | Proof |
|---|---|---|
| `perf/stress/run.mjs` + `perf/stress/scenarios.mjs` | Load driver: closed-model virtual users (VUs) in stages, plain `fetch`, no new dependencies | `perf/stress/lib/engine.mjs` |
| Exact percentiles | Nearest-rank over every sample (no sampling or histogram approximation) | `perf/lib.mjs` (`percentile`) |
| Resource monitor | Every second: JVM CPU % and RSS from `/proc/<pid>`, JVM threads, box CPU %, load average, the generator's own CPU, the app's DB connections from `pg_stat_activity`; `jstat -gcutil` every 5 s for heap and GC | `perf/stress/lib/monitor.mjs` |
| Synthetic probe | `GET /api/probe/synthetic` every 2 s during every scenario (one DB round trip), to detect cascading failure | `perf/stress/run.mjs` |
| Fake LLM | Scripted modes `ok` (with `delayMs`), `error500`, `timeout` to drive the AI fallback and circuit breaker | `e2e/stubs/fake-llm.mjs`, `e2e/lib/llm.js` |
| k6 scenarios (staging) | The same flows for the go-live run on staging, with thresholds | `loadtest/scenarios/main-mixed.js`, `loadtest/lib/config.js` |

## 5. Test data

| Data | Volume | How it is made |
|---|---|---|
| Users | 60 synthetic `READ_WRITE` users in one bank (plus the bootstrap admin and the e2e operator) | Rows copied in SQL from the e2e operator (same bank, its current password hash, no forced change), as `e2e/lib/db.js` `seedClonedUser` does; then each user signs in through the API (`perf/stress/lib/setup.mjs`) |
| Flow templates | 1 per user (6 text fields), 1 wide template (120 fields), 1 key:value template per AI-test user | `POST /Templates/CreateWithFields` |
| Ingestion jobs | 1 per user, 200 rows, run to VALIDATED; more created by the scenarios | `POST /api/ingestion/jobs` |
| PDF form templates | 1 per user (one page, 8 fields); large: 20 pages × 15 fields = 300 fields | `perf/stress/lib/fixtures.mjs` (`contractForm`, `largeForm`), built with `e2e/fixtures/pdf-form.js` |
| Documents | 3 per user (more created for VUs above 60) | `POST /api/doccreation/instances` |
| Large files | CSV of 1,000 / 10,000 / 50,000 rows × 6 columns; CSV of 1,000 rows × 120 columns | `perf/stress/lib/fixtures.mjs` |

Every upload carries a unique tag so the duplicate-upload gate (C6) never refuses it.

## 6. Scenarios and load profiles

A **VU** is one signed-in user session looping its actions with a think time. The expected
production peak is not stated anywhere in the repository (`TO CONFIRM (organisational)`: number of
banks, users per bank and their busiest hour). This plan uses a **working assumption of 50
concurrent active users as 1× peak** (one instance), so 100 VUs is 2× peak, the SEC-002
acceptance point. When the business gives the real figure, the thresholds stay and the VU
counts scale.

| ID | Scenario | Load profile | Flows | Controls |
|---|---|---|---|---|
| S1 | Concurrent data entry | Ramp: 10 → 25 → 50 VUs (45/45/60 s), think 0.6–1.2 s (the builder's autosave debounce is 600 ms, `frontend/src/components/doc/useAutosave.js`) | Autosave of 1–2 document values per call; every 6th action re-read the document; about every 10th action edit one row of a 200-row ingestion job (`PUT .../rows`, the full row set) | SEC-008, SEC-002 |
| S2 | Concurrent report generation | Ramp: 5 → 10 → 20 → 40 VUs (45/45/60/45 s), think 1–2 s | Change a value → Save and validate → Generate → Download PDF; every 3rd loop a CSV export of the user's job, every 9th an XLSX export | SEC-008, SEC-002 |
| S3 | Large templates and files | Single user, timed, 3 repetitions; then 5 VUs for 60 s on the large template | (a) 20-page, 300-field PDF template: upload, confirm, fill all values, validate, generate, download, page previews. (b) 120-field flow template with a 1,000-row CSV. (c) CSV ingestion of 1,000, 10,000 and 50,000 rows, then job read and export (the 5,000-row export cap applies to `READ_WRITE`) | SEC-008 |
| S4 | Peak mixed load | Warm-up 25 VUs 60 s → **1× peak 50 VUs 180 s** → **2× peak 100 VUs 180 s** → spike 200 VUs 60 s → recovery 25 VUs 90 s; think 1–3 s | Weighted UI mix: dashboard 15, jobs list 12, documents list 10, templates list 5, job poll 13, profile + announcements 8, autosave 22, CSV upload and poll to VALIDATED 5, validate-generate-download 5, export 3, sign-in 1 | SEC-002, SEC-003, SEC-008 |
| Soak | Soak (short) | 50 VUs for 10 minutes, reported per minute | The S4 mix | SEC-008 |
| S5a | Rate limiting with default limits | One user, sequential calls past each bucket's limit; a second user right after | `doc-generate` (30/h), `ingestion` (60/h), `exports` (60/h), `uploads` (20/h), sign-in per e-mail | SEC-002 |
| S5b | AI degradation and circuit breaker | 10 users in parallel: model slow (2 s per call), then model failing (HTTP 500, 2 uploads each), then model healthy while the breaker is still open; after the open duration, 3 uploads | Key:value PDF ingestion (asks the model first), code fallback | SEC-002 |
| S5c | DB connection pool pressure | 200 VUs, no think time, 60 s | Autosave 50 %, jobs list 25 %, dashboard 25 % | SEC-002, SEC-003 |
| S6 | Breakpoint | Steps of 10, 25, 50, 100, 150, 200, 300, 400 VUs, 40 s each, think 0.2–0.5 s; stop when a step has > 10 % errors or p95 > 10 s; then 10 VUs for 60 s (recovery) | Read-heavy UI mix with autosave (25 %) and validate (5 %) | SEC-008, SEC-003 |
| One CPU: S4-lite, S2-break, S6 | Peak, generation and breakpoint on one CPU (≈ one production task) | JVM pinned to one CPU. S4-lite: 25 VUs 60 s → 50 VUs 120 s → 100 VUs 120 s → 10 VUs 60 s. S2-break: generation loops with no think time at 5, 10, 20, 40, 60 VUs (30 s each). S6: steps 5–300 VUs | As S4, S2 and S6 | SEC-003, SEC-008 |

Load profiles covered: **ramp** (S1, S2, S4), **steady** (S4 1× and 2×), **spike** (S4 200 VUs),
**soak-short** (Soak), **breakpoint** (S6), **recovery** (S4, S6).

## 7. Thresholds

There is no agreed latency SLO in the repository. `docs/security/sla-and-status.md` commits only
to availability (99.5 % monthly) and says the SLA's p95 assumptions are to be validated by the
gate E7 load test. The thresholds below are **proposals**; they are the same values as the k6
scenarios (`loadtest/lib/config.js`, `loadtest/README.md`) plus three classes the k6 set does
not have (interactive writes, job completion, p99). Rushal approves or changes them.

| Request class | Examples | p95 | p99 |
|---|---|---:|---:|
| `read` | dashboard, lists, job poll, profile | ≤ 500 ms | ≤ 1,500 ms |
| `write` (interactive) | autosave, validate, row edit, create | ≤ 1,000 ms | ≤ 3,000 ms |
| `auth` | sign-in (Argon2id) | ≤ 1,000 ms | ≤ 2,000 ms |
| `heavy` | generate, download PDF, export, upload | ≤ 3,000 ms | ≤ 8,000 ms |
| `job` | CSV upload until VALIDATED | ≤ 60 s | ≤ 120 s |

| Error budget | Limit |
|---|---|
| Errors (5xx, timeouts, connection errors, unexpected 4xx) per stage | < 1 % |
| 5xx at 1× peak | 0 |
| 429 in capacity scenarios (limits raised for the run) | 0; in S5a they are the expected result |
| Synthetic probe during any stage | 100 % HTTP 200 (no cascading failure) |
| Recovery after load drops | p95 back within the thresholds within 60 s, with no restart |

The thresholds are coded in `perf/stress/lib/engine.mjs` (`THRESHOLDS`) and each summary file
reports pass/fail per class.

## 8. Pass / fail criteria

| Criterion | Pass | Fail |
|---|---|---|
| **Capacity at 1× peak** (S4 50 VUs) | Every class within its p95 threshold, errors < 1 %, no 5xx | Any class over threshold or any 5xx |
| **2× peak** (S4 100 VUs; SEC-002 acceptance) | Errors < 1 %, probe 100 %, no cascading failure; p95 may degrade but stays within the p99 limits | Errors ≥ 1 %, probe failures, or a component that does not recover |
| **Spike and recovery** | Errors < 1 % during the spike; recovery stage within thresholds | Errors persist after the spike |
| **Breakpoint** (S6) | Reported, not passed or failed: the highest step within thresholds is the tested capacity; the first step over them is the breaking point | — |
| **Graceful degradation** (S5) | 429 with `Retry-After` once a bucket is spent, other users unaffected; AI failures give VALIDATED jobs via code fallback, the breaker opens and the LLM is no longer called; pool saturation queues instead of failing | Any 5xx, failed job or unhandled error caused by the control |
| **Soak** | No upward trend in p95 or RSS over the last minutes; errors < 1 % | Steady growth or rising errors |
| **Large templates** (S3) | Every step answers successfully; heavy steps within 3 s p95 for the 300-field template; 50,000-row job reaches VALIDATED | Errors or timeouts |

The run as a whole passes when every row passes. A failing row is a finding with an owner and a
date (MO-017 §7); a critical failure is retested after its fix (§11).

## 9. Monitoring during the test

| What | Where | Watch for |
|---|---|---|
| Latency and errors per request name | `perf/results/stress-2026-10/<label>/<scenario>.summary.json`, per-second `*.timeline.csv` | p95 drift, error bursts, 429 |
| JVM CPU, RSS, threads | `resources.csv`, the `resources` block of each stage | CPU at the CPU limit (saturation), RSS near the heap + overhead, thread growth (Tomcat max 200) |
| Heap and GC | `jstat-gcutil.txt` | old-generation growth, full GCs, GC time |
| DB connections of the app role | `resources.csv` (`db_conn_total` = open connections; `db_conn_active` needs a monitoring role allowed to read other roles' session state) | total at 20 = pool at its maximum size |
| Box load and generator CPU | `resources.csv` (`sys_cpu_pct`, `load1`, `node_cpu_pct`) | generator CPU near 100 % (the generator, not the app, would then be the limit) |
| Backend log | `e2e/.run/<run-id>/backend.log` (kept with `E2E_KEEP=1`) | Hikari timeouts, leak-detection WARN, AI circuit messages, `OutOfMemoryError` |
| Probe | `probe.synthetic` in every summary | any non-200 |

## 10. Risks of the test itself

| Risk | Mitigation |
|---|---|
| The box is shared: other builds steal CPU and add noise | Box CPU % and load average are recorded per stage; numbers are read as indicative; the staging re-run is the go-live evidence |
| Generator on the same box as the app competes for CPU | Generator CPU recorded; the 1-CPU variant pins the JVM so the generator and DB use other CPUs |
| Rate limits are per user per hour; capacity runs would hit them, not the system's limits | Limits raised for the capacity stack (within the settings' hard limits, `docs/settings/catalogue.md`), recorded in `run.json`; S5a runs on a second stack with the defaults |
| Auth limits per IP: all VUs come from one IP | The harness already raises them (`e2e/run-local.sh`, `SPRING_APPLICATION_JSON`); sign-in is 1 % of the mix |
| Synthetic data too small | Background data per user (200-row jobs, documents) plus the 50,000-row job; production-scale background data is part of the staging run (`loadtest/README.md`) |
| Real data leaks into a test | Only generated data; the stack is created and dropped per run (`e2e/run-local.sh`) |

## 11. Retest rules

1. A **critical** failure (5xx or failed jobs at ≤ 2× peak, no recovery, data loss) is fixed,
   then the failing scenario is rerun on a fresh stack with the same parameters and compared
   to the failing run.
2. A **non-critical** finding (latency over threshold, inefficiency) is logged with an owner and
   date; it is retested in the next full run.
3. The full plan is rerun: before go-live on staging (gate E7, SEC-008), after any major
   architecture change (instance size, pool sizes, job runner, autoscaling policy), and at least
   every 12 months (SEC-008 acceptance: report dated within 12 months).
4. Every rerun keeps the same scenario definitions (`perf/stress/scenarios.mjs`) so the results
   are comparable; changes to them are versioned in git.

## 12. How to run

```sh
# Capacity stack: limits raised (within hard limits), production heap
MODOCUMENT_RATELIMIT_UPLOADS=200 MODOCUMENT_RATELIMIT_INGESTION=600 \
MODOCUMENT_RATELIMIT_EXPORTS=600 MODOCUMENT_RATELIMIT_DOC_GENERATE=300 \
E2E_JAVA_OPTS="-Xmx1971m -XX:+UseG1GC" STRESS_LABEL=main \
SKIP_BUILD=1 E2E_RUN_CMD='node perf/stress/run.mjs' e2e/run-local.sh

# Default limits: rate-limit behaviour (S5a)
STRESS_LABEL=default-limits STRESS_USERS=5 STRESS_SCENARIOS=s5a \
E2E_JAVA_OPTS="-Xmx1971m -XX:+UseG1GC" SKIP_BUILD=1 E2E_RUN_CMD='node perf/stress/run.mjs' e2e/run-local.sh

# One CPU (one production task, approximately)
STRESS_LABEL=one-cpu STRESS_PIN_CPUS=3 STRESS_USERS=60 STRESS_SCENARIOS=s4-lite,s2-break,s6 \
STRESS_BREAKPOINT_STEPS=5,10,25,50,100,150,200,300 \
MODOCUMENT_RATELIMIT_UPLOADS=200 MODOCUMENT_RATELIMIT_INGESTION=600 \
MODOCUMENT_RATELIMIT_EXPORTS=600 MODOCUMENT_RATELIMIT_DOC_GENERATE=300 \
E2E_JAVA_OPTS="-Xmx1971m -XX:+UseG1GC -XX:ActiveProcessorCount=1" \
SKIP_BUILD=1 E2E_RUN_CMD='node perf/stress/run.mjs' e2e/run-local.sh
```

The exact commands, dates and output paths of the MO-017 run are in
[evidence/A3-stress-runs.md](evidence/A3-stress-runs.md).

## 13. Scenario to control map

| Control | Workbook acceptance | Scenarios |
|---|---|---|
| SEC-008 Submit stress testing report | Report dated within 12 months; raw outputs archived | All; raw outputs under `perf/results/stress-2026-10/` |
| SEC-002 Peak load and performance degradation | At 2× expected peak the error budget holds and no cascading failure occurs | S4 (2× and spike), S5a, S5b, S5c, S6 recovery |
| SEC-003 Autoscaling vertically and horizontally | Scale-out observed under synthetic load with zero 5xx | S6 and the one-CPU runs (vertical limit of one instance, the scaling signal); the scale-out itself is `NOT IN REPO (live cloud)` |

## To confirm

- `TO CONFIRM (organisational)`: the expected production peak (banks, active users per bank, busiest hour), which turns the 50-VU working assumption into a real 1× figure.
- `TO CONFIRM (organisational)`: approval of the thresholds in §7 by Rushal as the go-live SLOs.
- `NOT IN REPO (live cloud)`: the staging environment for the go-live run (gate E7) and its observability (CloudWatch, RDS Performance Insights).
