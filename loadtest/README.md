# MoDocument load and stress scenarios (k6)

These scripts prepare gate **E7** (capacity proven before go-live) and control **SEC-008**
(capacity tested on production-sized infrastructure with synthetic data). Committing them is
workstream 17's part. **Running them is a separate, later item.** They are not run in CI or in a
development container: numbers measured on a laptop or a CI runner say nothing about production
capacity.

## Layout

| File | What it does |
|---|---|
| `lib/config.js` | Reads every setting from environment variables. Holds the shared thresholds (p95 per flow, error rate below 1%, zero rate-limited responses) and the user pool. |
| `lib/api.js` | API calls: login, refresh (cookie rotation), one cached session per virtual user with refresh before the 15-minute token expiry, CSV upload and ingestion, job polling, export, and SPA-style reads. Every request is tagged with `flow` and `name`. |
| `data/sample.csv` | 20 synthetic rows. `__RUN_ID__` is replaced on every upload so the duplicate-upload gate (C6) never refuses a repeat. |
| `scenarios/login-refresh.js` | Authentication throughput: login, then refresh, at an arrival rate. |
| `scenarios/upload-ingest.js` | Upload a CSV, run parse, map and validate, and poll until the job finishes. |
| `scenarios/job-polling.js` | The read path: many users polling their own jobs and the job list. |
| `scenarios/export.js` | Export validated rows as csv, xlsx and json. |
| `scenarios/main-mixed.js` | **Main scenario.** 60% browse, 25% ingest, 15% export at an open arrival rate. It ramps through 0.5x, 1x and **2x the expected peak**, holding at peak and at 2x peak. |

## Where to run: staging, not here

- **Target.** A staging environment built as described under "Production-sized infrastructure"
  below, reached over **HTTPS** through the same load balancer, nginx and TLS path as production.
  `BASE_URL` must be `https://...`: the refresh cookie is `Secure`, so the refresh flow fails
  over plain HTTP.
- **Never production.** The scenarios create jobs, uploads and exports.
- **Not this repository's dev container or CI.** Neither has production-like resources, and
  neither is a realistic network path.
- **Load generator.** Run it on a machine outside the cluster under test, with enough CPU and
  bandwidth that the generator is not the bottleneck: watch k6's own CPU, and check that the
  `dropped_iterations` metric stays at 0. Record its specification in the report.
- **Install k6** (v0.50 or later) from <https://grafana.com/docs/k6/latest/set-up/install-k6/>.

## Preparing staging

1. **Synthetic bank, users and template.** Users are admin-created; there is no
   self-registration. Following the [JML runbook](../docs/security/jml-runbook.md), create a
   dedicated synthetic bank and **N `READ_WRITE` users with no MFA enrolled** (ADMIN accounts are
   forced into MFA and cannot be used here). Put them in a JSON file **outside the repository**:
   `[{"email": "...", "password": "..."}, ...]`. Create one template whose field names match the
   CSV headers exactly: `RunId` (Text), `Reference` (Text), `Description` (Text), `Amount`
   (Number), `ValueDate` (Date). Exact names let the deterministic mapping tiers resolve every
   column. Pass its id as `TEMPLATE_ID`.
2. **Production-scale background data.** See SEC-008 below. Seed it before the run, so queries
   run against realistic table sizes and not an empty database.
3. **Configuration identical to production.** If you change anything for the run (only the rate
   limits below are allowed), record it in the report.

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `BASE_URL` | (required) | API origin, e.g. `https://staging.modocument.example` |
| `USERS_FILE` | — | JSON array of `{email, password}`. Use an absolute path, because k6 resolves a relative `open()` path against the directory of the calling script. |
| `USER_EMAIL`, `USER_PASSWORD` | — | A single user, used when `USERS_FILE` is not set (smoke runs only) |
| `TEMPLATE_ID` | (required for ingest and export) | Template matching the CSV headers |
| `CSV_FILE` | `../data/sample.csv` | Alternative CSV. Keep the `__RUN_ID__` placeholder in it. |
| `EXPECTED_PEAK_IPS` | `2` | Expected peak in actions per second. The main scenario ramps to twice this. |
| `RAMP`, `HOLD` | `5m`, `10m` | Main scenario stage durations |
| `PRE_VUS`, `MAX_VUS` | `50`, `500` | Virtual-user pool for the arrival-rate executor |
| `VUS`, `DURATION` | `5`, `10m` | For the constant-VU scenarios |
| `MIX_BROWSE`, `MIX_INGEST`, `MIX_EXPORT` | `60`, `25`, `15` | Action mix weights for the main scenario |
| `THINK_TIME_S` | `3` | Mean pause between one user's actions |
| `POLL_INTERVAL_S`, `POLL_TIMEOUT_S` | `2`, `120` | Job polling interval and timeout |
| `P95_READ_MS`, `P95_WRITE_MS`, `P95_AUTH_MS`, `P95_EXPORT_MS`, `P95_JOB_MS` | `500`, `2000`, `1000`, `3000`, `60000` | p95 latency thresholds |
| `MAX_ERROR_RATE` | `0.01` | Error-rate threshold (`http_req_failed` and `checks`) |

## Rate limits during a run

Production limits are per user, per hour (`MODOCUMENT_RATELIMIT_*`; table in
[owasp-api-top10.md](../docs/security/owasp-api-top10.md)). Login and refresh are additionally
limited per IP: 30 per 15 minutes. A load test therefore either **spreads the load over enough
users** or **raises the limits on staging for the run window**. Every scenario has the threshold
`rate_limited: count==0`, so a 429 fails the run loudly instead of passing as low capacity.

- **Enough users (preferred).** Users needed for a bucket = that action's rate per hour divided
  by the bucket limit. Example: `EXPECTED_PEAK_IPS=3` with a 25% ingest share at 2x peak is
  1.5 ingests/s = 5,400 per hour. That needs at least 5,400 / 60 = **90 users** with the default
  60 per hour ingestion limit. Exports at a 15% share need 3,240 / 60 = **54 users**. Give
  `MAX_VUS` at least that many entries in `USERS_FILE`: virtual users map round-robin onto the
  file.
- **Login per IP.** Every virtual user logs in once and then refreshes every 12 minutes, so a
  single generator needs `MODOCUMENT_SECURITY_RATE_LIMIT_AUTH_PER_IP_MAX` above roughly
  `MAX_VUS + MAX_VUS / 12` per 15 minutes. Raise it for the run window, or spread the load over
  several generator IPs. `login-refresh.js` measures authentication throughput itself, and it
  always needs this raised.
- **Raising limits.** Any raise (`MODOCUMENT_RATELIMIT_*`, `MODOCUMENT_SECURITY_RATE_LIMIT_*`) is
  recorded in the report and reverted afterwards. Do not set `MODOCUMENT_RATELIMIT_ENABLED=false`:
  the limiter's own cost is part of what is being measured.
- **Replicas.** Counters are in memory per instance, so N replicas allow N times the limit.
  State the replica count in the report.
- **Confirm the limiter after the run.** With the production limits restored, run
  `upload-ingest.js` as a single user and `VUS=1` until it is throttled. Check that the 429
  carries `Retry-After`. Here the `rate_limited` threshold is expected to fail.

## Running

```bash
# Smoke: one user, short, to validate the setup (expect thresholds to pass)
k6 run -e BASE_URL=https://staging.example -e USER_EMAIL=lt-001@synthetic.example \
       -e USER_PASSWORD=... -e TEMPLATE_ID=42 -e VUS=1 -e DURATION=1m \
       loadtest/scenarios/upload-ingest.js

# Individual flows
k6 run -e BASE_URL=... -e USERS_FILE=/abs/users.json loadtest/scenarios/login-refresh.js
k6 run -e BASE_URL=... -e USERS_FILE=/abs/users.json -e TEMPLATE_ID=42 loadtest/scenarios/upload-ingest.js
k6 run -e BASE_URL=... -e USERS_FILE=/abs/users.json -e TEMPLATE_ID=42 loadtest/scenarios/job-polling.js
k6 run -e BASE_URL=... -e USERS_FILE=/abs/users.json -e TEMPLATE_ID=42 loadtest/scenarios/export.js

# Main scenario to 2x peak, keeping raw results for the report
k6 run -e BASE_URL=... -e USERS_FILE=/abs/users.json -e TEMPLATE_ID=42 \
       -e EXPECTED_PEAK_IPS=3 --summary-export=results/main-summary.json \
       --out json=results/main-mixed.json loadtest/scenarios/main-mixed.js
```

**Thresholds**, which make k6 exit non-zero when breached:

| Metric | Threshold |
|---|---|
| `http_req_failed` | rate < 1% |
| `checks` | rate > 99% |
| `http_req_duration{flow:read}` | p95 < 500 ms |
| `http_req_duration{flow:write}` | p95 < 2000 ms (upload plus job create) |
| `http_req_duration{flow:auth}` | p95 < 1000 ms (Argon2id hashing is deliberately expensive) |
| `http_req_duration{flow:export}` | p95 < 3000 ms |
| `job_completion_ms` | p95 < 60 s (upload until the job reaches a terminal state) |
| `rate_limited` | count == 0 |
| `http_req_failed{flow:read}` (main scenario) | rate < 5%, **aborts** the run after 1 minute |

These targets are proposals. Agree the go-live SLOs and set them through the `P95_*` variables
rather than editing the scripts.

**Syntax check (no k6 needed).** `node --input-type=module --check < loadtest/scenarios/main-mixed.js`
(repeat for each file under `lib/` and `scenarios/`). This parses each script as an ES module.
It does not resolve the `k6/*` imports and does not run anything.

## SEC-008: "production-sized infrastructure with synthetic data"

A capacity number counts as evidence only when **the system under test matches production**
and **the data is synthetic but production-shaped**:

- **Same topology and sizing.** Same instance types and counts for the app, PostgreSQL, docling
  and the LibreOffice converter. Same JVM heap and GC settings, database class, storage IOPS,
  connection-pool sizes, replica count and autoscaling rules, and the same load balancer, nginx
  and TLS chain. Anything smaller is recorded as a deviation, and its results are not used for
  the go-live decision.
- **Same configuration.** Production profile (`permit-dev-paths` off), production logging
  levels, retention and the SoD scheduler enabled. The AI gateway is in its production mode
  (local-first model, or the provider with production quotas). If the model is replaced by a
  latency-matched stub, the report says so and gives the stub's latency.
- **Synthetic data at production volume.** Banks, users, templates, historical ingestion jobs,
  uploads, extraction records and audit rows are generated to the expected **12-month**
  production volume, with production-like distributions (rows per file, columns per template,
  file sizes). **No production data is copied**, not even masked: PRIV controls apply, and a
  load test is no exception.
- **Observability on.** CPU and memory per pod, JVM heap and GC pauses, DB CPU, connections, lock
  waits and slow queries, request p50/p95/p99 per route, job queue depth and worker
  utilisation, and 4xx/5xx rates. These are recorded for the same time window as the k6 run.

## Capacity report (template)

Copy these headings into the report for the run, and attach the k6 summary JSON and the
dashboards.

1. **Summary.** Pass or fail against the SLOs at 1x and 2x peak. The headline capacity figure
   (actions/s at which p95 or errors breach) and the recommendation.
2. **Scope and gate mapping.** E7 / SEC-008, build version (`/v3/api-docs` `info.version`), date,
   and who ran it.
3. **Environment.** Topology, instance sizes, replica counts, JVM and DB settings, AI-gateway
   mode, load-generator specification and location. Deviations from production.
4. **Data set.** Synthetic volume per table and how it was generated. Confirmation that no
   production data was used.
5. **Workload model.** Expected peak (`EXPECTED_PEAK_IPS`) and its derivation, the action mix,
   think time, user-pool size, and the stage timeline.
6. **Configuration changes for the run.** Rate-limit raises and any other change, each with the
   reason and confirmation that it was reverted.
7. **Results per scenario.** Throughput, p50/p95/p99 per `flow` and `name`, error rate,
   `job_completion_ms`, `rate_limited`, and `dropped_iterations`.
8. **Resource utilisation.** CPU, memory, GC, DB, queue depth, and the saturation point with
   the bottleneck identified.
9. **Stress behaviour above 2x.** Graceful degradation (429 or 503) or failure, and recovery
   time after the load drops.
10. **Rate-limit check.** The post-run 429 + `Retry-After` check with production limits.
11. **Findings and actions.** Tuning applied, open risks with owners, and the re-test plan.
12. **Sign-off.** Engineering, operations and security.
