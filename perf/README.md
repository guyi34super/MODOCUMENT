# perf/ — optimization-round measurements

Scripts that measure the app the same way before (baseline, `bb845a5`) and
after the v1.2.0 optimization waves. No new dependencies: Node 20+, the
`e2e/` package's Playwright + `pg`, and the Docker-free stack of
`e2e/run-local.sh` (scratch PostgreSQL on `127.0.0.1:55432`).
Results land in `perf/results/<label>/*.json` (committed; raw logs are
git-ignored). The baseline numbers and their reading are in
[`BASELINE.md`](BASELINE.md).

| Script | Measures | Output |
|---|---|---|
| `run.sh <label>` | everything below, in order, against a fresh stack | `results/<label>/` |
| `seed.mjs` | seeds one bank user (the e2e READ_WRITE operator) with `PERF_JOBS`×`PERF_ROWS` (50×1,000) ingestion jobs run to VALIDATED through the API; falls back to SQL clones of API-made jobs past `PERF_SEED_API_BUDGET_S` | `seed.json` |
| `api-bench.mjs` | p50/p95/min/max latency and raw + on-the-wire bytes (`Accept-Encoding: gzip, deflate, br`) for login, me, the jobs list, one 1,000-row job (the poll), the dashboard's 4 calls (each, and in parallel), and the Wave-2 summary endpoints when they answer 200; `PERF_WARMUP` 3 + `PERF_ITERATIONS` 20, sequential, straight to the backend port | `api.json` |
| `frontend-load.mjs` | cold login → dashboard in a fresh Chromium context: requests, JS/CSS/API bytes raw, as served, and a gzip-6 estimate; median timings of `PERF_RUNS` (3) | `frontend.json` |
| `build-sizes.mjs` | `npm run build` time and every emitted asset (raw / gzip / brotli) | `build.json` |
| `footprint.mjs [backend.log]` | app jar size, its 10 largest nested jars, startup "Started … in" | `footprint.json` |
| `test-suite.mjs <verify.log> [reports] [wall-s]` | backend tests, Spring contexts started, per-class times | `test-suite.json` |
| `compare.mjs <before> <after> [--md]` | before/after table with deltas | stdout |

## Running

```sh
# Full set (builds the jar + SPA, starts the stack, seeds, measures, tears down; ~10 min)
perf/run.sh baseline
# Plus the backend `mvn -B verify` and `vitest run` timings (~8 more minutes)
PERF_TESTS=1 perf/run.sh baseline
# Reuse target/*.jar and frontend/dist
PERF_SKIP_BUILD=1 perf/run.sh adhoc
```

`run.sh` uses `e2e/run-local.sh` with `E2E_RUN_CMD` (run a command instead of
Playwright, same environment), so the stack, secrets and teardown are
exactly the E2E ones. Never run `playwright install`: Chromium is
preinstalled at `/opt/pw-browsers`.

## Wave 2: the after-measurement

1. On the merged Wave-2 head, with nothing else of yours running:
   `PERF_TESTS=1 perf/run.sh wave2`
2. `node perf/compare.mjs baseline wave2 --md` → paste the table into the
   sign-off (`docs/qa/optimization-signoff.md`) with the load averages it
   prints. The box is shared: if the load average differs a lot from the
   baseline's (see `BASELINE.md`), re-run both at a quiet moment
   (`git worktree add /tmp/perf-base bb845a5` and `perf/run.sh baseline-rerun`
   there — the perf scripts must be copied in, they postdate `bb845a5`).
3. Check the plan's targets against the table:
   - jobs-list payload and latency smaller (`GET /api/ingestion/jobs?…view=summary`
     against the baseline `GET /api/ingestion/jobs`);
   - dashboard: `GET /api/dashboard/summary` against "dashboard: 4 calls in parallel";
   - initial JS ≤ 300 kB raw (≈ 90 kB gzip): "initial JS (login page)";
   - app jar ≥ 150 MB smaller; startup faster (CDS is measured on the image,
     not on `java -jar`, so add the image numbers from CI);
   - fewer Spring contexts / faster `mvn -B verify`.
4. The alert-sweep `EXPLAIN` and the AI-token count on the golden corpus are
   not in these scripts: they come from SE1's and SE4's PG IT / corpus run.
