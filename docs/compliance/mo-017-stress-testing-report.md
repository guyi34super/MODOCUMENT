# MO-017 — Stress Testing Report

| Task | Deliverable | Author | Reviewer | Approver | Test date | Report date | Version |
|---|---|---|---|---|---|---|---|
| MO-017 | Stress Testing Report | Agent A3 for Bhavish Nobeen (Software Engineering) | Rushal | Reezvi Pydiah | 2026-10-19 | 2026-10-19 | 1.0 |

This report records the run of the [MO-016 Stress Test Plan](mo-016-stress-test-plan.md): what
was run, the measured response times, throughput, error rates and resource use, the breaking
points and bottlenecks, the findings with their remediation, and the retest. It is the evidence
for [SEC-008](controls/SEC-008.md) and quantifies the limits for [SEC-002](controls/SEC-002.md)
and [SEC-003](controls/SEC-003.md). Every number below comes from the archived raw output under
`perf/results/stress-2026-10/`; the commands are in
[evidence/A3-stress-runs.md](evidence/A3-stress-runs.md). All data was synthetic.

## 1. Executive summary

**Verdict on the test box: PASS for 1× and 2× the assumed peak, with findings.** One MoDocument
instance (production profile, row-level security on, production heap size) on a shared 4-vCPU
test machine served the realistic user mix at 1× (50 users), 2× (100 users) and a 4× spike
(200 users) with **0 errors in 26,438 requests across the S4 stages (26,439 including one request between stages)**, every request class inside its latency
threshold, and full recovery after the spike. A 10-minute soak at 1× had 0 errors, resident memory
between 1.35 and 1.43 GiB and no full garbage collection. The overload controls behaved as designed: every rate limit answered **429 with
`Retry-After` exactly at its limit**, the AI circuit breaker opened after 5 failures and every
ingestion job still completed by code, and a full database pool (20 connections) under 200
no-think users slowed requests down without failing any.

| Headline | Result (4 vCPU shared test box, one instance) |
|---|---|
| 1× peak, 50 users (S4) | 30.7 req/s; p95 read 13 ms, write 19 ms, heavy 56 ms, sign-in 72 ms; 0 errors |
| 2× peak, 100 users (S4) | 61.7 req/s; p95 read 21 ms, write 52 ms, heavy 67 ms, sign-in 92 ms; 0 errors |
| Spike, 200 users (S4) | 125.9 req/s; p95 read 47 ms, write 186 ms; 0 errors; recovery to the 1× level in the next stage |
| Breakpoint (S6, fast-clicking users) | 0 errors at every step up to 400 users; throughput plateau **≈ 715–744 req/s from 300 users**; **latency breaking point at 400 users** (dashboard p95 609 ms > 500 ms); the machine's CPU at 97–99 % was the bottleneck |
| One CPU, production-sized task (S4-lite, S6, S2-break) | 2× peak (100 users) inside every threshold at 73 % mean CPU, 0 errors; breakpoint capacity ≈ 236 req/s at 100 fast users, latency break at 150; generation saturates at ≈ 15–17 flows/s |
| Concurrent generation (S2) | 24 validate-generate-download flows/s at 40 users, generate p95 153 ms, 0 errors |
| Large templates (S3) | 300-field, 20-page PDF template: upload + confirm ≤ 0.5 s, validate + generate + download ≤ 0.64 s; 50,000-row CSV to VALIDATED in 5.8 s |
| Rate limiting (S5a, default limits) | 429 at the limit for all 5 buckets tested, `Retry-After` given, other users unaffected, a 429 costs 4–10 ms |
| AI outage (S5b) | 20/20 jobs VALIDATED during a model outage; breaker opened, model calls stopped; model used again after 2 minutes |
| Errors / 5xx in all capacity runs | **0** |

**Main findings** (§7): the full ingestion-job read returns every row (17.9 MB for a
50,000-row job, F-01); the ingestion pipeline has only 2 worker threads with an unbounded queue
(F-02), and a large job's record re-index holds a worker and a DB connection for about 35 s
(F-03); the JVM's resident memory reached 2,656 MiB, 160 MiB under the production container
limit (F-04); report generation is synchronous and CPU-bound, with no queue (F-05); under
saturation there is no early shedding, latency grows instead (F-06). None is a functional
failure; none produced an error in this run.

**What this report does not prove.** Production capacity. The test box is shared with other
build jobs, runs the database with `fsync=off` on the same machine, has no TLS, ALB, nginx or
network hop, and uses a fake LLM. The expected production peak is not known
(`TO CONFIRM (organisational)`). The go-live evidence for gate E7 is the same plan re-run on
staging (§10).

## 2. Environment

| Item | Value | Proof |
|---|---|---|
| Application | MoDocument 1.2.0, jar built 2026-10-18 12:35 UTC from commit `660cff7`; no application change between `660cff7` and the pack commit `eb53e97` (`git diff 660cff7 eb53e97 -- src frontend/src pom.xml` is empty) | jar `META-INF/build-info.properties`, `git.properties` |
| Profile | Production profile: Flyway on PostgreSQL, RLS with the `modocument_app` role (`E2E_DB_APP_ROLE=rls`), bootstrap admin with MFA | `e2e/run-local.sh` |
| Machine | Shared sandbox: 4 vCPU Intel Xeon 2.8 GHz, 15.7 GiB RAM; other agents' Maven builds and tests ran at the same time (load average 8.5 at the start of the main run, 10.2 at its end) | `perf/results/stress-2026-10/main/run.json` |
| JVM | Java 21, `-Xmx1971m -XX:+UseG1GC` (production heap: 70 % of the 2,816 MiB app container); one-CPU run adds `-XX:ActiveProcessorCount=1` and `taskset` to CPU 3 | `run.json` (`jvm`), `docker-entrypoint.sh`, `infra/terraform/modules/ecs-app/variables.tf` |
| Database | PostgreSQL 16.14 scratch cluster on the same box, `fsync=off`, `max_connections` 100, `shared_buffers` 128 MB, fresh database per run | `e2e/run-local.sh` |
| DB pool / web threads | Hikari max 20 (default), Tomcat default 200 worker threads | `src/main/resources/application.yml` |
| AI | Fake OpenAI-compatible LLM (`e2e/stubs/fake-llm.mjs`), AI read timeout 5 s (harness) | `e2e/run-local.sh` |
| Load generator | `perf/stress/run.mjs` (Node 22, same box), straight to the backend port; its own CPU is recorded (max 83 % of one core in the main run, so it was not the limit) | `perf/stress/lib/engine.mjs`, `resources.csv` |
| Rate limits | Capacity runs: `MODOCUMENT_RATELIMIT_UPLOADS=200`, `_INGESTION=600`, `_EXPORTS=600`, `_DOC_GENERATE=300` (the settings' hard maxima, so the per-user hourly buckets do not cap a 45-minute run of 60 users). Sign-in limits as raised by the harness for every e2e run (per IP 1,000 / 15 min, per e-mail 200 / 15 min). S5a ran with **all API limits at their defaults** | `run.json` (`rateLimitOverrides`), `e2e/run-local.sh`, `docs/settings/catalogue.md` |

Production design for comparison: 2–8 tasks of 1,024 CPU units / 3,072 MiB (app container 896
units / 2,816 MiB) on `m6i.large`, RDS `db.m6g.large` Multi-AZ, CloudFront + WAF
(`infra/terraform/modules/ecs-app/variables.tf`, `infra/terraform/envs/production/variables.tf`).

## 3. What was run

| Run (label) | Started (UTC) | Stack | Scenarios | Requests recorded |
|---|---|---|---|---|
| `main` | 2026-10-19 16:35:40 – 17:11:55 | 4 CPUs, limits raised | Seed; S1, S2, S3, S5b, S4, Soak, S5b recovery, S5c, S6 | 210,906 in the scenarios + 802 seed (per scenario in `run.json`) |
| `default-limits` | 2026-10-19 17:12:35 – 17:13:09 | 4 CPUs, default limits | S5a | 391 |
| `one-cpu` | 2026-10-19 17:13:45 – 17:30:04 | JVM pinned to 1 CPU | Seed; S4-lite, S2-break, S6 | 69,232 (12,343 + 11,771 + 45,118) |
| `retest-s5b` | 2026-10-19 17:30:47 – 17:31:25 | 4 CPUs, limits raised | Seed (20 users); S5b alone (retest of F-02/F-03) | 210 |

Seed (main): 60 synthetic `READ_WRITE` users, each with a flow template, two 200-row ingestion
jobs (one for edits, one for exports), a confirmed one-page 8-field PDF template and 3 documents:
802 requests in 46.8 s, 0 errors (`perf/results/stress-2026-10/main/seed.json`).

Request classes and thresholds are those of MO-016 §7: `read` p95 ≤ 500 ms, `write` ≤ 1,000 ms,
`auth` ≤ 1,000 ms, `heavy` ≤ 3,000 ms, `job` (upload until VALIDATED) ≤ 60 s; errors < 1 %.
Latencies below are milliseconds, measured at the client, per request, nearest-rank percentiles
over every sample. "Probe" is the public synthetic probe polled every 2 s during each stage
(answered / sent).

## 4. Results per scenario (main run, 4 CPUs)

### 4.1 S1 — Concurrent data entry

Autosave of document values, document re-reads, and about every 10th action an edit of one row
of a 200-row ingestion job followed by re-validation.

| Stage | Requests | req/s | p50 | p95 | p99 | max | Errors | Status codes | JVM CPU % mean / max | RSS MiB max | DB conns max | Probe |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 users | 671 | 14.9 | 16.0 | 173.1 | 218.4 | 279.3 | 0 % | 200 × 671 | 59 / 165 | 952 | 10 | 22/22 |
| 25 users | 1,669 | 37.1 | 12.5 | 165.2 | 199.4 | 276.4 | 0 % | 200 × 1,669 | 59 / 111 | 938 | 10 | 23/23 |
| 50 users | 4,346 | 72.4 | 13.0 | 175.6 | 241.2 | 1,192.4 | 0 % | 200 × 4,346 | 99 / 170 | 916 | 17 | 30/30 |

| Request (whole scenario) | Requests | p50 | p95 | p99 | max |
|---|---|---|---|---|---|
| `PUT …/instances/{id}/values` (autosave) | 4,876 | 12.5 | 48.4 | 103.8 | 189.3 |
| `GET …/instances/{id}` | 786 | 9.6 | 32.1 | 66.7 | 101.4 |
| `PUT /api/ingestion/jobs/{id}/rows` (200 rows) | 487 | 26.1 | 65.3 | 152.2 | 292.1 |
| `POST /api/ingestion/jobs/{id}/revalidate` (200 rows) | 487 | 183.9 | 312.1 | 868.1 | 1,192.4 |

All classes pass (write p95 182 ms at 50 users against 1,000 ms). The re-validation of 200 rows
is the slowest data-entry step (p50 184 ms) and its p95 rose from 222 ms at 25 users to 488 ms
at 50 users (`perf/results/stress-2026-10/main/s1.summary.json`).

### 4.2 S2 — Concurrent report generation and export

Each loop: change a value → Save and validate → Generate → Download PDF; every 3rd loop a CSV
export, every 9th an XLSX export of a 200-row job.

| Stage | Requests | req/s | Flows/s | Flow p95 | `generate` p95 | Heavy p95 / p99 | Errors | JVM CPU % mean / max | Box CPU % | RSS MiB max |
|---|---|---|---|---|---|---|---|---|---|---|
| 5 users | 724 | 16.1 | 3.0 | 276.7 | 201.1 | 201 / 1,916 | 0 % | 82 / 331 | 29 | 1,344 |
| 10 users | 1,520 | 33.8 | 6.3 | 145.0 | 86.2 | 78 / 130 | 0 % | 70 / 155 | 28 | 1,409 |
| 20 users | 3,975 | 66.3 | 12.4 | 170.7 | 100.2 | 95 / 154 | 0 % | 167 / 262 | 56 | 1,647 |
| 40 users | 5,795 | 128.8 | 24.2 | 242.0 | 152.7 | 140 / 452 | 0 % | 231 / 295 | 76 | 2,072 |

| Request (whole scenario) | Requests | p50 | p95 | p99 | max | avg bytes |
|---|---|---|---|---|---|---|
| `POST …/validate` | 2,252 | 14.5 | 47.4 | 70.0 | 198.0 | 4,644 |
| `POST …/generate` | 2,252 | 52.5 | 131.2 | 337.4 | 1,038.9 | 112 |
| `GET …/download` (PDF) | 2,252 | 9.2 | 28.9 | 53.1 | 182.4 | 10,551 |
| `GET …/export/file?format=csv` | 494 | 22.6 | 63.9 | 92.5 | 113.1 | 14,346 |
| `GET …/export/file?format=xlsx` | 270 | 95.1 | 721.1 | 1,920.8 | 1,945.3 | 10,958 |

Generation throughput grew linearly with users (3 → 24 flows/s) with 0 errors; JVM CPU rose
to 2.3 cores, so generation is CPU-bound. The 5-user stage's p99 (about 1.9 s) comes from the first
XLSX exports of the run (likely warm-up of the spreadsheet writer; later stages stay under
0.8 s); XLSX is about 4× slower than CSV at p50.

### 4.3 S3 — Large templates and files

| Case | Size | Result |
|---|---|---|
| Large PDF template, 3 repetitions | 20 pages × 15 fields = 300 fields, 27,028-byte PDF | Upload + confirm 300 regions: 368 / 499 / 321 ms. Fill all 300 values: 25–35 ms. Validate + generate + download: 638 / 490 / 630 ms. Page preview (PNG): 146–324 ms. All OK |
| 5 users generating the 300-field template (60 s) | 220 loops | `generate` p50 514 ms, p95 696 ms, max 1,225 ms; `validate` p95 82 ms; 0 errors; JVM CPU 249 % mean, RSS **2,656 MiB** max |
| Wide flow template | 120 fields, 1,000 rows (1.2 MB CSV) | Upload to VALIDATED 1,328 ms; job read 258 ms / **4.6 MB**; CSV export 681 ms |
| CSV ingestion, 1,000 rows | 95 kB | VALIDATED in 585 ms; job read 51 ms / 0.35 MB; export 94 ms |
| CSV ingestion, 10,000 rows | 0.98 MB | VALIDATED in 1,333 ms; job read 154 ms / 3.6 MB; export refused 403 (READ_WRITE cap 5,000 rows) then 293 ms with a stated reason |
| CSV ingestion, 50,000 rows | 4.97 MB | VALIDATED in **5,780 ms**; job read **765 ms / 17.9 MB**; export refused 403, then 792 ms / 3.6 MB with a reason; jobs list (summary view) 74 ms |

Generation time grows with the number of fields (p50 ≈ 52 ms for 8 fields, 514 ms for 300
fields under 5 users). The 50,000-row job meets the job threshold (5.8 s ≤ 60 s), but its full
read (`GET /api/ingestion/jobs/{id}`) fails the read threshold (765 ms > 500 ms) — finding F-01.
After the job was VALIDATED, the pipeline thread kept a database connection for about 35 s
re-indexing the job's records; Hikari logged "Apparent connection leak detected"
(`TemplateRecordIndexer.reindex`, backend log 16:44:16, returned 16:44:21) — finding F-03.

### 4.4 S4 — Peak mixed load

The UI mix of MO-016 §6 with think time 1–3 s.

| Stage | Requests | req/s | p95 read | p95 write | p95 heavy | p95 sign-in | Job to VALIDATED p95 | Errors | Status codes | JVM CPU % mean / max | Box CPU % | Threads max | DB conns max | Probe |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Warm-up 25 | 899 | 15.0 | 17.3 | 23.3 | 69.8 | 116.1 | 2,103 | 0 % | 200 × 862, 202 × 37 | 25 / 99 | 19 | 76 | 18 | 30/30 |
| **1× peak 50** | 5,529 | 30.7 | 13.1 | 19.0 | 56.1 | 71.9 | 2,047 | **0 %** | 200 × 5,316, 202 × 213 | 36 / 113 | 21 | 75 | 18 | 90/90 |
| **2× peak 100** | 11,103 | 61.7 | 21.3 | 51.7 | 66.7 | 92.4 | 2,072 | **0 %** | 200 × 10,554, 202 × 429, 201 × 120 | 81 / 187 | 39 | 94 | 20 | 90/90 |
| Spike 200 | 7,552 | 125.9 | 46.6 | 186.3 | 121.6 | 156.4 | 2,111 | 0 % | 200 × 6,951, 202 × 301, 201 × 300 | 167 / 230 | 69 | 155 | 20 | 30/30 |
| Recovery 25 | 1,355 | 15.1 | 12.7 | 18.6 | 43.5 | 55.8 | 2,037 | 0 % | 200 × 1,308, 202 × 47 | 16 / 70 | 10 | 154 | 20 | 44/44 |

"Job to VALIDATED" is measured by polling every 2 s, so about 2,030 ms means "done by the first
poll". The p99 of all requests (about 2,030 ms in every stage) is these job samples, not slow
HTTP calls. The slowest HTTP request in the whole scenario was 579 ms (document create during the
spike). The 201s are documents created on first use by the VUs above 60. Per request
(`perf/results/stress-2026-10/main/s4.summary.json`): dashboard p95 33 ms, jobs list 27 ms, job
read (200 rows, 70 kB) 33 ms, autosave 40 ms, generate 105 ms, CSV upload 60 ms, sign-in 118 ms.

### 4.5 Soak (10 minutes at 1×, 50 users)

| Minute | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| Requests | 1,867 | 1,841 | 1,824 | 1,805 | 1,759 | 1,818 | 1,780 | 1,810 | 1,826 | 1,835 |
| p95 all (ms) | 57.7 | 50.5 | 45.4 | 45.2 | 49.3 | 44.1 | 72.7 | 62.3 | 66.0 | 73.2 |
| p95 read (ms) | 14.0 | 11.2 | 12.7 | 12.3 | 12.7 | 13.2 | 25.5 | 15.2 | 19.2 | 26.3 |
| JVM RSS max (MiB) | 1,348 | 1,372 | 1,372 | 1,359 | 1,379 | 1,381 | 1,369 | 1,381 | 1,395 | 1,428 |
| Box CPU % mean | 17.6 | 17.3 | 16.9 | 16.5 | 17.9 | 17.3 | 26.0 | 21.3 | 72.0 | 79.0 |

18,165 requests, 0 errors, probe 300/300. RSS stayed between 1,348 and 1,428 MiB; `jstat`
recorded **0 full GCs** over the whole main run (1,180 young and 1,354 concurrent cycles, 23.7 s
GC time in 2,211 s of JVM uptime, `jstat-gcutil.txt`). The p95 rise in minutes 7–10 coincides
with the box CPU jumping from 17 % to 79 % while the app's own load was constant: another
tenant's build. It is not attributable to the application, but a soak on a quiet staging box is
needed to rule out drift (§10).

### 4.6 S5 — Overload behaviour

**S5a Rate limiting, default limits** (`perf/results/stress-2026-10/default-limits/s5a.summary.json`):

| Bucket (default) | Calls | Answered | First 429 at call | `Retry-After` | Median latency served / 429 |
|---|---|---|---|---|---|
| `doc-generate` (30/user/h) | 35 | 200 × 30, 429 × 5 | 31 | 3,597 s | 99.0 / 8.3 ms |
| `ingestion` (60/user/h; seed used 2) | 63 | 201 × 58, 429 × 5 | 59 | 3,585 s | 30.3 / 6.8 ms |
| `exports` (60/user/h) | 63 | 200 × 60, 429 × 3 | 61 | 3,598 s | 29.6 / 8.8 ms |
| `uploads` (20/user/h; seed used 1) | 22 | 201 × 19, 429 × 3 | 20 | 3,585 s | 62.5 / 9.5 ms |
| Sign-in per e-mail (harness value 200 / 15 min; seed used 1) | 205 | 200 × 199, 429 × 6 | 200 | 870 s | 54.6 / 4.3 ms |
| `doc-generate`, a second user of the same bank, right after | 1 | 200 | — | — | 161.8 ms |

Every limit held exactly, each 429 carried `Retry-After` and a plain-language message, and
refusals were 4–10× cheaper than served calls (shedding protects the instance).

**S5b AI degradation and circuit breaker** (10 users, key:value PDFs that ask the model first,
`perf/results/stress-2026-10/main/s5b.summary.json`):

| Phase | Jobs | VALIDATED | Parsed by / reason | Model calls | Job completion median / max |
|---|---|---|---|---|---|
| Model slow (2 s per call), 10 parallel uploads | 10 | 10 | AI × 10 | 10 | 12.4 s / **21.6 s** |
| Model failing (HTTP 500), 10 users × 2 | 20 | 20 | CODE/ERROR × 6, CODE/CIRCUIT_OPEN × 14 | 12 (6 jobs × 2 attempts, then none) | 3.1 s / 7.2 s |
| Model healthy again, breaker still open | 5 | 5 | CODE/CIRCUIT_OPEN × 5 | **0** | 1.07 s / 1.08 s |
| After the 2-minute open duration (17:04) | 3 | 3 | AI × 3 | 3 | 0.52 s each |

The breaker opened once ("AI circuit OPENED after 5 consecutive transport/server failures",
backend log; alert `ai-circuit-opened` raised), no job failed, and the trial call closed it
again. The slow-model phase shows queueing: 10 jobs wait for the 2 pipeline threads (F-02), and
one of the two was still busy with the 50,000-row re-index from S3 (F-03); see the retest in §8.

**S5c Database pool pressure** (200 users, no think time, 60 s):

| Requests | req/s | p50 | p95 | p99 | max | Errors | Read p95 | Write p95 | DB conns open | JVM threads | Box CPU % |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 26,210 | 436.8 | 391.6 | 1,080.9 | 1,566.6 | 2,677.6 | **0 %** | 1,205 (fail) | 855 | **20 (pool maximum)** | 247 (Tomcat's 200 workers busy) | 99.6 |

The pool was at its maximum of 20 open connections and Tomcat's 200 workers were all busy: with
200 concurrent requests at most 20 can hold a connection at once, so the rest waited (the log has
no "Connection is not available" timeout) and none failed. Latency, not errors, absorbed the
overload (F-06).

### 4.7 S6 — Breakpoint (step load, think 0.2–0.5 s)

| Step | Requests | req/s | p50 | p95 | p99 | max | Errors | Read p95 | Write p95 | Dashboard p95 | JVM CPU % mean | Box CPU % | DB conns | Probe |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 1,117 | 27.9 | 9.1 | 18.3 | 30.6 | 65.2 | 0 % | 15.8 | 23.0 | 19.0 | 15 | 70* | 20 | 20/20 |
| 25 | 2,782 | 69.6 | 9.7 | 21.5 | 40.3 | 135 | 0 % | 18.5 | 25.0 | 21.7 | 34 | 78* | 18 | 20/20 |
| 50 | 5,568 | 139.2 | 7.9 | 20.3 | 33.5 | 94.7 | 0 % | 17.2 | 24.4 | 20.4 | 58 | 54 | 18 | 20/20 |
| 100 | 11,188 | 279.7 | 7.5 | 19.7 | 42.5 | 172.3 | 0 % | 16.9 | 23.5 | 20.7 | 105 | 50 | 17 | 20/20 |
| 150 | 16,641 | 416.0 | 8.4 | 27.0 | 54.2 | 128.8 | 0 % | 23.0 | 34.9 | 27.4 | 145 | 65 | 18 | 20/20 |
| 200 | 21,786 | 544.5 | 11.5 | 51.9 | 97.7 | 228.3 | 0 % | 45.4 | 60.9 | 54.2 | 178 | 80 | 20 | 20/20 |
| **300** | 28,621 | **715.4** | 54.1 | 201.2 | 317.8 | 840.9 | 0 % | 196.8 | 210.9 | 247.9 | 217 | **97** | 20 | 20/20 |
| **400** | 29,764 | **744.0** | 152.6 | 458.0 | 650.0 | 1,167.1 | 0 % | 478.9 | 405.7 | **608.9** | 216 | **99** | 20 | 18/18 |
| Recovery 10 | 1,672 | 27.9 | 8.0 | 14.2 | 18.8 | 133.5 | 0 % | 11.3 | 16.4 | 12.3 | 13 | 10 | 20 | 30/30 |

\* the first two steps overlapped another tenant's build (load average 16.5 and 10.3).

Reading: throughput grew linearly up to 200 users (≈ 2.7 req/s per user) and flattened between
300 and 400 users (+4 %) while p50 tripled. **Tested capacity within all thresholds: 300 users
(≈ 715 req/s). Breaking point: 400 users**, where the dashboard p95 (609 ms) leaves the read
threshold and the class p95 sits at 479 ms. No step produced an error, so the system degrades
by latency, not by failure. The JVM never used more than 2.4 of the 4 cores: PostgreSQL, the
load generator (up to 83 % of a core) and other tenants used the rest, and the box CPU reached
97–99 % — the machine, not a software limit, was the bottleneck. Recovery at 10 users was
immediate (read p95 11.3 ms, below the 10-user step before the load).

## 5. One production-sized CPU (`one-cpu` run)

Run `one-cpu` (2026-10-19 17:13:45 – 17:30:04 UTC): the same stack with the JVM pinned to one
CPU (`taskset`, affinity mask `8` = CPU 3) and `-XX:ActiveProcessorCount=1`, heap 1,971 MiB —
roughly one production task (`app_cpu` 896 of 1,024 units, `infra/terraform/modules/ecs-app/variables.tf`).
PostgreSQL and the generator ran on the other CPUs. Results in `perf/results/stress-2026-10/one-cpu/`.

**S4-lite (peak mix, think 1–3 s):**

| Stage | Requests | req/s | p95 read | p95 write | p95 heavy | p95 sign-in | Errors | JVM CPU % mean / max | RSS MiB max | Probe |
|---|---|---|---|---|---|---|---|---|---|---|
| Warm-up 25 | 890 | 14.8 | 195.3 | 166.8 | 500.0 | 172.1 | 0 % | 61 / 95 | 956 | 29/29 |
| **1× peak 50** | 3,652 | 30.4 | 56.3 | 123.1 | 274.4 | 378.3 | **0 %** | **53** / 99 | 1,078 | 60/60 |
| **2× peak 100** | 7,240 | 60.3 | 93.2 | 199.6 | 283.2 | 622.3 | **0 %** | **73** / 101 | 1,241 | 60/60 |
| Recovery 10 | 380 | 6.3 | 14.2 | 18.1 | 76.7 | 155.4 | 0 % | 9 / 54 | 1,018 | 29/29 |

One CPU carries 2× the assumed peak inside every threshold, at 73 % mean CPU. The warm-up stage
is slower than 1× (JIT compilation on a single core right after start-up).

**S2-break (validate → generate → download, no think time):**

| Users | Requests | req/s | Flows/s | `generate` p50 / p95 | Heavy p95 | Flow p95 | Errors | JVM CPU % mean | RSS MiB max |
|---|---|---|---|---|---|---|---|---|---|
| 5 | 2,088 | 69.6 | 14.0 | 206 / 314 | 290 | 543 | 0 % | 93 | 2,018 |
| 10 | 2,272 | 75.7 | 15.2 | 377 / 531 | 496 | 872 | 0 % | 94 | 2,436 |
| 20 | 2,579 | 86.0 | 17.3 | 672 / 897 | 828 | 1,484 | 0 % | 94 | 2,442 |
| 40 | 2,377 | 79.2 | 15.9 | 1,458 / 2,225 | 2,013 | 3,592 | 0 % | 94 | 2,436 |
| **60** | 2,303 | 76.8 | 15.6 | 1,959 / **3,601** | **3,140** | 5,627 | 0 % | 93 | 2,430 |

Generation saturates one CPU at about **15–17 complete flows per second**; beyond that, latency
grows linearly with the number of users. The heavy-class threshold (p95 3 s) breaks at **60
continuously generating users**. RSS reached **2,442 MiB** (F-04).

**S6 (breakpoint, think 0.2–0.5 s):**

| Step | Requests | req/s | p50 | p95 | p99 | max | Errors | Read p95 | Dashboard p95 | JVM CPU % mean | JVM threads | Probe |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 5 | 559 | 14.0 | 9.6 | 19.8 | 52.2 | 80.2 | 0 % | 15.0 | 19.1 | 10 | 104 | 20/20 |
| 10 | 1,111 | 27.8 | 8.8 | 17.8 | 27.4 | 36.9 | 0 % | 14.7 | 17.8 | 17 | 104 | 20/20 |
| 25 | 2,796 | 69.9 | 8.8 | 20.2 | 34.7 | 79.8 | 0 % | 15.9 | 19.0 | 33 | 104 | 20/20 |
| 50 | 5,470 | 136.8 | 10.3 | 31.7 | 76.4 | 192.6 | 0 % | 25.6 | 31.2 | 56 | 104 | 20/20 |
| **100** | 9,458 | **236.4** | 48.6 | 231.2 | 365.3 | 686.9 | 0 % | 222.3 | 274.6 | 90 | 119 | 20/20 |
| **150** | 8,589 | 214.7 | 281.7 | 912.9 | 1,438.2 | 3,585.8 | 0 % | **907.7** | 1,090.1 | 94 | 172 | 18/18 |
| 200 | 7,225 | 180.6 | 634.9 | 1,977.3 | 2,909.8 | 7,454.6 | 0 % | 2,168.2 | 2,742.1 | 93 | 217 | 15/15 |
| 300 | 8,041 | 201.0 | 898.7 | 3,196.3 | 4,707.4 | 8,641.7 | 0 % | 3,406.5 | 4,023.0 | 94 | 243 | 18/18 |
| Recovery 10 | 1,687 | 28.1 | 9.1 | 17.3 | 29.0 | 86.9 | 0 % | 13.8 | 16.8 | 16 | 243 | 30/30 |

**One CPU: capacity within thresholds 100 fast users ≈ 236 req/s; breaking point 150 users**
(read p95 908 ms). Past saturation, throughput falls back (215 → 181–201 req/s) as up to 243
threads contend for one core, but there is still **no error** at 300 users (max 8.6 s), and the
service recovered at once (read p95 13.8 ms at 10 users). The probe kept answering at every step.

## 6. Breaking points and bottlenecks

| Resource | Observation | Root cause | Evidence |
|---|---|---|---|
| CPU (box) | Throughput plateau at ≈ 715–744 req/s from 300 users; p95 × 9 between 200 and 400 users | Machine CPU at 97–99 % (JVM ≈ 2.2 cores, PostgreSQL, generator, other tenants); the work per request is CPU-bound (JSON, PDF rendering, Argon2id, RLS queries) | `s6.summary.json`, `resources.csv` |
| CPU (one task) | 236 req/s peak at 100 fast users, then throughput falls back to 181–201 req/s; generation flat at 15–17 flows/s from 5 users; JVM at 90–94 % of its core | One core for request handling, GC and the pipeline threads | `perf/results/stress-2026-10/one-cpu/` |
| DB connection pool | Grown to its maximum of 20 open connections from the S4 2× stage on (and kept open); no connection timeout in the log | Pool sized 20 per instance (`MODOCUMENT_DB_POOL_MAX`); above 20 concurrent DB-bound requests the rest wait | `s5c.summary.json` (`dbConnTotal` 20) |
| Web threads | 247 JVM threads under S5c/S6 (from 75 at 1×): Tomcat's 200 workers all busy | Closed-model users above 200 queue in the accept backlog | `resources.csv` (`javaThreads`) |
| Ingestion pipeline | 10 parallel AI uploads took up to 21.6 s; jobs wait in line | `IngestionJobRunner` fixed pool of 2 threads, unbounded queue; one thread held ~35 s by a 50,000-row re-index | `s5b.summary.json`, backend log, `src/main/java/com/modocument/infrastructure/ingestion/IngestionJobRunner.java` |
| Payload size | 17.9 MB / 765 ms for one 50,000-row job read; 4.6 MB for 120 × 1,000 | `GET /api/ingestion/jobs/{id}` returns parsed and mapped tables in full | `s3.summary.json` |
| Memory | RSS up to 2,656 MiB with a 1,971 MiB heap during large-template generation; 1.3–1.4 GiB in normal load | Heap + metaspace + thread stacks + direct buffers (PDF rendering) | `s3.summary.json`, `resources.csv` |

## 7. Findings and remediation

No test produced a 5xx, a timeout or a failed job, so there is **no critical failure**. The
findings are capacity risks. Code is not changed by this report; each finding has an owner and
a proposed date (all `proposed`, to be confirmed by Rushal).

| ID | Severity | Finding | Remediation | Owner | Proposed date |
|---|---|---|---|---|---|
| F-01 | Medium | `GET /api/ingestion/jobs/{id}` returns every row: 17.9 MB and 765 ms for 50,000 rows (fails the 500 ms read threshold); the SPA polls this route | Page the job's rows (`?rowsPage=`), return counts only in the default view, keep the full table for export | Software Engineering — Bhavish Nobeen | 2026-12-15 |
| F-02 | Medium | Ingestion pipeline: fixed 2 threads, unbounded in-memory queue (`IngestionJobRunner`); 10 parallel uploads with a 2 s model waited up to 21.6 s | Pool size as a setting, bounded queue with 429 + `Retry-After` when full, queue depth metric | Software Engineering — Bhavish Nobeen | 2026-12-15 |
| F-03 | Medium | After a 50,000-row job is VALIDATED, `TemplateRecordIndexer.reindex` keeps the pipeline thread and one DB connection for about 35 s (Hikari leak WARN at 30 s) | Index in batches with short transactions, on its own executor, after the job's response | Software Engineering — Bhavish Nobeen | 2026-12-15 |
| F-04 | Medium | JVM RSS reached 2,656 MiB with the production heap (1,971 MiB): 160 MiB under the 2,816 MiB container limit; an OCR or LibreOffice child process could push the container over and get it killed | Lower `-XX:MaxRAMPercentage` to 60 or raise `app_memory`; cap metaspace/direct memory; re-measure RSS on staging under S3 | IT Infrastructure — Bhavish Nobeen | 2026-11-30 |
| F-05 | Medium | Report generation is synchronous and CPU-bound (52 ms p50 for 8 fields, 514 ms for 300 fields; one CPU saturates at 15–17 generation flows/s and passes 3 s p95 at 60 users); no queue, as the SEC-002 workbook asks | Bounded generation executor; 202 + poll, or 429 + `Retry-After` when the queue is full | Software Engineering — Bhavish Nobeen | 2026-12-15 |
| F-06 | Low | Under saturation there is no early shedding: at 200 no-think users read p95 was 1,205 ms (0 errors) and requests would only fail at the 10 s pool timeout | Align Tomcat `max-threads`/`accept-count` with the pool; a concurrency limiter answering 503 + `Retry-After`; rely on autoscaling in production (SEC-003) | Software Engineering — Bhavish Nobeen | 2027-01-31 |
| F-07 | Low | Rate-limit counters are in memory per instance (known, `docs/security/owasp-api-top10.md` API4): N tasks allow N × each limit | Shared counter store before scaling past the minimum task count | Software Engineering — Bhavish Nobeen | 2027-01-31 |
| F-08 | Low | XLSX export is about 4× slower than CSV (p95 721 ms vs 64 ms for 200 rows; first calls 1.9 s) | Stream the workbook (SXSSF) and warm it at start-up | Software Engineering — Bhavish Nobeen | 2027-01-31 |
| F-09 | Info | Soak p95 rose from 44 to 73 ms in the last 4 minutes while another tenant loaded the box | Repeat the soak (≥ 1 h) on staging | IT Infrastructure — Bhavish Nobeen | before go-live |

## 8. Retest

There were no critical failures to fix and retest. The one result that mixed two causes —
S5b's slow-model phase (F-02 and F-03 together) — was rerun alone on a fresh stack
(`retest-s5b`), with no large job running:

| Phase | Main run (with the 50,000-row re-index running) | Retest (alone), 2026-10-19 17:30:47 UTC | Change |
|---|---|---|---|
| Model slow, 10 parallel uploads: completion median / max | 12.4 s / 21.6 s | **7.2 s / 11.3 s** | max halved |
| Model failing, 10 × 2: completion median / max | 3.1 s / 7.2 s | 2.1 s / 4.2 s | faster |
| Outcomes, failing phase | 20/20 VALIDATED; ERROR × 6, CIRCUIT_OPEN × 14; 12 model calls | identical: 20/20; ERROR × 6, CIRCUIT_OPEN × 14; 12 model calls | same |
| Breaker open, model healthy | 5/5 CIRCUIT_OPEN, 0 model calls | 5/5 CIRCUIT_OPEN, 0 model calls | same |
| Errors, probe | 0; probe answered | 0; probe 8/8 | same |

Reading: the circuit-breaker behaviour is reproducible exactly. With nothing else running, 10
uploads that each need about 2.2 s of model time finish in 11.3 s, which is 10 × 2.2 s shared by
2 threads: the queueing of F-02 is confirmed and quantified, and the doubling seen in the main
run was F-03 (one of the two threads held by the large re-index). Raw output:
`perf/results/stress-2026-10/retest-s5b/`.

## 9. Conclusions against the thresholds

| Criterion (MO-016 §8) | Result | Verdict |
|---|---|---|
| Capacity at 1× peak (50 users) | All classes within p95 thresholds, 0 errors, 0 5xx | **Pass** |
| 2× peak (100 users) | 0 errors, probe 100 %, no cascading failure | **Pass** |
| Spike (200) and recovery | 0 errors; recovery stage at the 1× level | **Pass** |
| Breakpoint | Capacity 300 users ≈ 715 req/s (4 CPUs, shared); latency break at 400 users; one CPU: 100 users ≈ 236 req/s within thresholds, break at 150 users | Reported |
| Graceful degradation | 429 + `Retry-After` at every limit, other users unaffected; AI outage → 20/20 jobs completed; full pool → waiting, no errors | **Pass** |
| Soak (10 min) | 0 errors, RSS flat (1.35–1.43 GiB), 0 full GCs; late p95 drift explained by a noisy neighbour | **Pass** (repeat on staging) |
| Large templates | 300-field template and 50,000-row job complete; the 50,000-row job read fails the read threshold (F-01) | **Partial** |

**Overall: Pass on the test box with findings F-01 to F-09.** The application is not the weak
point at the assumed peak; one instance handled 2× peak at about 1 core. Production capacity,
the real expected peak and the autoscaling behaviour still have to be demonstrated on staging.

## 10. Limitations and follow-up

1. **Test box ≠ production.** Shared 4 vCPU with other workloads, database on the same machine
   with `fsync=off`, no TLS, ALB, nginx, CloudFront or network latency, local disk instead of
   S3, fake LLM. Absolute numbers are optimistic for I/O and pessimistic for CPU contention.
2. **One instance only.** Horizontal scaling, load balancing and the WAF were not exercised
   (`NOT IN REPO (live cloud)`).
3. **Data volume.** Background data was 60 users with a few jobs and documents each, plus
   the large-file cases; production-scale 12-month volumes were not generated.
4. **Connection monitor.** The monitor counts the app role's open connections; the monitoring
   role cannot see other roles' session state in `pg_stat_activity`, so busy and idle
   connections are not split (`db_conn_active` stays empty).
5. **Single client IP.** All traffic came from 127.0.0.1; the harness raises the sign-in
   limits per IP for that reason.
6. **Expected peak unknown.** 50 concurrent users is a working assumption
   (`TO CONFIRM (organisational)`).
7. **Follow-up (owner IT Infrastructure — Bhavish Nobeen, before go-live, date to set):** apply
   `infra/terraform/envs/staging`, seed synthetic production-scale data, run the `loadtest/`
   k6 scenarios (`loadtest/README.md`) and this plan's S1–S6 against the staging ALB at 2× the
   confirmed peak, with a 1-hour soak, CloudWatch and RDS metrics, and an observed scale-out
   event for SEC-003; then re-issue this report.

## Appendix A — Commands

```sh
# main run (4 CPUs, limits raised within their hard maxima, production heap)
STRESS_LABEL=main MODOCUMENT_RATELIMIT_UPLOADS=200 MODOCUMENT_RATELIMIT_INGESTION=600 \
  MODOCUMENT_RATELIMIT_EXPORTS=600 MODOCUMENT_RATELIMIT_DOC_GENERATE=300 \
  E2E_JAVA_OPTS="-Xmx1971m -XX:+UseG1GC" SKIP_BUILD=1 \
  E2E_RUN_CMD='node perf/stress/run.mjs' e2e/run-local.sh

# default limits (S5a)
STRESS_LABEL=default-limits STRESS_USERS=5 STRESS_SCENARIOS=s5a \
  E2E_JAVA_OPTS="-Xmx1971m -XX:+UseG1GC" SKIP_BUILD=1 \
  E2E_RUN_CMD='node perf/stress/run.mjs' e2e/run-local.sh

# one CPU
STRESS_LABEL=one-cpu STRESS_PIN_CPUS=3 STRESS_USERS=60 STRESS_SCENARIOS=s4-lite,s2-break,s6 \
  STRESS_BREAKPOINT_STEPS=5,10,25,50,100,150,200,300 \
  MODOCUMENT_RATELIMIT_UPLOADS=200 MODOCUMENT_RATELIMIT_INGESTION=600 \
  MODOCUMENT_RATELIMIT_EXPORTS=600 MODOCUMENT_RATELIMIT_DOC_GENERATE=300 \
  E2E_JAVA_OPTS="-Xmx1971m -XX:+UseG1GC -XX:ActiveProcessorCount=1" SKIP_BUILD=1 \
  E2E_RUN_CMD='node perf/stress/run.mjs' e2e/run-local.sh

# retest of S5b alone
STRESS_LABEL=retest-s5b STRESS_USERS=20 STRESS_SCENARIOS=s5b \
  MODOCUMENT_RATELIMIT_UPLOADS=200 MODOCUMENT_RATELIMIT_INGESTION=600 \
  MODOCUMENT_RATELIMIT_EXPORTS=600 MODOCUMENT_RATELIMIT_DOC_GENERATE=300 \
  E2E_JAVA_OPTS="-Xmx1971m -XX:+UseG1GC" SKIP_BUILD=1 \
  E2E_RUN_CMD='node perf/stress/run.mjs' e2e/run-local.sh
```

The build was not redone: `target/modocument-1.2.0.jar` and frontend/dist (build output, not committed) were copied from the
main checkout's current build (`SKIP_BUILD=1`).

## Appendix B — Raw output

| Path | Content |
|---|---|
| `perf/results/stress-2026-10/<label>/run.json` | Machine, load average, JVM flags and CPU affinity, rate-limit overrides, scenario windows |
| `perf/results/stress-2026-10/<label>/run-output.txt` | Driver console output with per-stage lines |
| `perf/results/stress-2026-10/<label>/<scenario>.summary.json` | Per stage: requests, req/s, p50/p90/p95/p99/max, errors, status codes, per request name and per class with threshold verdicts, probe, resources |
| `perf/results/stress-2026-10/<label>/<scenario>.requests.csv.gz` | Every request: epoch ms, name, class, latency, status, bytes, ok, error |
| `perf/results/stress-2026-10/<label>/<scenario>.timeline.csv` | Per second: requests, errors, 429s, p50/p95/max, JVM CPU/RSS/threads, box CPU, load, DB connections |
| `perf/results/stress-2026-10/<label>/resources.csv` | 1-second resource samples for the whole run |
| `perf/results/stress-2026-10/<label>/jstat-gcutil.txt` | `jstat -gcutil` every 5 s (heap occupancy, GC counts and times) |
| `perf/results/stress-2026-10/main/seed.json` | Seed timings |
| `perf/stress/` | The driver: `run.mjs`, `scenarios.mjs`, `lib/engine.mjs`, `lib/monitor.mjs`, `lib/setup.mjs`, `lib/fixtures.mjs` |

## To confirm

- `TO CONFIRM (organisational)`: the expected production peak; approval of the thresholds and of the proposed remediation dates (Rushal), and approval of this report (Reezvi Pydiah, management approval required by the checklist).
- `NOT IN REPO (live cloud)`: staging run through CloudFront, ALB and nginx; autoscaling events; RDS metrics.
