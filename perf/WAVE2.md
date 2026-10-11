# Optimization round (v1.2.0) — Wave 2 after-measurements

**Commit measured:** `8d1e272` ("Page the admin user list; record the Wave 1
as-built contract deviations"): every Wave-1 merge (SE1–SE5 and QA's
perf toolkit / context fold), production code untouched since. Measured
2026-10-11 between 05:17 and 06:03 UTC by QA. The jar and the SPA were built
from that commit; the working tree carried only QA's Wave-2 test and perf
changes (none of them is production code).

**Connector-core merge (`6627af4`, after the measurements):** the shared
connector library landed on the branch at 06:13 and was merged into QA's
tree afterwards. It touches only the integration connectors (none of the
measured paths); the jar built from the merged head is 157,213,853 bytes
(+35 kB: `modocument-connector-core-1.1.0.jar`, 115 kB, replaces the
server's own connector classes). Every build now starts with
`mvn -B -f connector-core/pom.xml install` (`perf/run.sh` and
`e2e/run-local.sh` do it).

**Compared against:** the Wave-0 baseline `bb845a5` (`BASELINE.md`, measured
03:22–03:50 at load 7.7) **and a paired re-run of that baseline** taken
05:25–05:32, on the same box, at the same load as the Wave-2 run (`git
archive bb845a5` + the current `perf/` scripts, label `baseline-rerun`).

> **Read these numbers as relative, not absolute.** A shared 4-vCPU /
> 15.7 GiB sandbox; a connector-core engineer was building in parallel.
> Load averages (1/5/15 min) are given with every number. Payload sizes,
> request counts, jar and chunk sizes and context counts do not depend on
> load; latencies, startup and suite times do — compare those only with the
> run at the same load (the baseline re-run), as below.

Units: decimal kB/MB (the JSON holds bytes; `compare.mjs` prints KiB/MiB).

| Run | Label | When (UTC) | Load (1/5/15) | What |
|---|---|---|---|---|
| Before, original | `baseline` | 03:41 | 7.7 / 6.1 / 3.8 | `BASELINE.md` |
| Before, paired | `baseline-rerun` | 05:25–05:32 | 1.8 / 2.2 / 1.7 (verify 2.8 → 2.2) | `perf/run.sh` + timed `mvn -B verify` (`-Dmaven.test.failure.ignore`: 1 env failure, no `.git`) + `vitest run` |
| After | `wave2` | 05:17–05:24 | 2.1 / 2.2 / 1.3 (vitest 1.3 → 3.7) | `PERF_TESTS=1 perf/run.sh wave2` |
| After, 2nd pass | `wave2-run2` | 05:52–05:54 | 4.0 → 5.2 | API re-run + the cold load **through the real nginx config** + startup |

## Commands

```sh
git merge --ff-only claude/tender-noether-izbf0u            # 8d1e272
PERF_TESTS=1 perf/run.sh wave2                              # build, stack, seed 50x1000, API, browser, footprint, suites
node perf/compare.mjs baseline wave2 --md                   # vs the original baseline
node perf/compare.mjs baseline-rerun wave2 --md             # vs the paired re-run (same load)
# paired baseline: git archive bb845a5 | tar -x -C <dir>; copy perf/ and e2e/run-local.sh in; perf/run.sh baseline-rerun
# 2nd pass: nginx (perf/nginx-check.sh) + noisy cases, see perf/README.md "Wave 2" step 5
```

Same data set as the baseline: one READ_WRITE operator, **50 ingestion jobs ×
1,000 rows**, all through `POST /api/ingestion/jobs` to `VALIDATED`
(upload-to-VALIDATED p50 557 ms, was 591 ms). Same cases, 3 warm-up + 20
timed sequential calls, straight to the backend port; plus the Wave-2 routes.

## 1. Headline (before → after)

| | Before (`bb845a5`) | After (`8d1e272`) | Δ |
|---|---:|---:|---:|
| Jobs list payload: `GET /api/ingestion/jobs` → `?view=summary&page=0&size=50` | 16.5 MB | **20.3 kB** (1.6 kB gzip via nginx) | **−99.9 %** (812×) |
| Jobs list p50 / p95 (same load) | 330.5 / 396.7 ms | **10.9 / 14.4 ms** (2nd pass, load 5: 10.2 / 16.1) | **−97 %** |
| Dashboard data: 4 lists in parallel → `GET /api/dashboard/summary` | 16.5 MB | **4.3 kB** (0.6 kB gzip) | **−99.97 %** |
| Dashboard data p50 / p95 (same load) | 352.3 / 476.4 ms | **12.0 / 14.5 ms** (load 5: 13.3 / 22.2) | **−97 %** |
| Cold login → dashboard: API bytes | 16.5 MB, 8 calls | **5.4 kB, 6 calls** (1.7 kB through nginx) | −99.97 % |
| Cold login → dashboard: requests | 11 | 10 | −1 |
| Initial JS (login page), raw | 614.9 kB, 1 chunk | **292.2 kB**, 2 chunks (`index` 58.2 + `vendor` 234.0) | **−52 %** ✓ ≤ 300 kB |
| Initial JS, gzip -6 | 182.8 kB | **93.0 kB** ✓ ≈ 90 kB | −49 % |
| Initial JS **as nginx sends it** (gzip level 1, the default) | (nginx sent it raw: 614.9 kB) | **108.1 kB** | −82 % on the wire |
| Sign-in → dashboard data loaded (median of 3; same load) | 504 ms | **149 ms** (vite preview) / 229 ms (nginx, load 5) | −70 % |
| App jar | 369.2 MB (352.1 MiB) | **157.2 MB (149.9 MiB)** | **−212 MB** ✓ ≥ 150 MB |
| Startup `java -jar` "Started in" (same load) | 17.9 s | 16.9 s (load 4–5: 20.0 s) | ≈ −6 %, within noise |
| `mvn -B verify` wall (same load) | 239 s, 1,752 tests | **215 s, 1,875 tests** | −10 % with +7 % tests |
| Spring test contexts ("Started" lines) | 28 | 21 at `8d1e272` → **20** after QA's Wave-2 fold | −8 |
| `vitest run` (same load) | 35 s, 456 tests | 37 s, 501 tests | +6 % for +10 % tests |

✓ = the plan's Wave-2 target (`perf/README.md` → "Wave 2", step 3) is met.

## 2. API (p50 / p95 ms, response bytes)

Node `http`, keep-alive, `Accept-Encoding: gzip, deflate, br`, straight to
the backend (no nginx: the backend itself never compresses — see §3 for the
wire through nginx).

| Case | Baseline (load 7.7) | Baseline re-run (load 1.8) | **Wave 2 (load 2.1)** | Wave 2, 2nd pass (load 5.2) |
|---|---:|---:|---:|---:|
| `POST /api/auth/login` | 64.5 / 156.3 | 58.4 / 75.5 | 52.9 / 118.0 | 96.9 / 139.4 |
| `GET /api/auth/me` | 8.2 / 16.7 | 6.9 / 10.4 | 5.8 / 8.2 | — |
| `GET /api/ingestion/jobs` (compat, 50 jobs, 16.5 MB) | 282.8 / 335.8 | 330.5 / 441.4 | 267.6 / 358.3 | — |
| `GET /api/ingestion/jobs/{id}` (1,000 rows, 328 kB — the poll) | 16.4 / 28.4 | 16.6 / 20.8 | 15.9 / 21.8 | 19.5 / 58.6 |
| dashboard: `GET /Templates/GetAll` | 9.6 / 21.5 | 6.2 / 8.7 | 7.0 / 9.6 | 8.2 / 15.3 |
| dashboard: `GET /api/ingestion/jobs` | 420.7 / 684.8 | 323.2 / 466.9 | 240.6 / 300.3 | 246.5 / 380.4 |
| dashboard: `GET /api/extraction/jobs` | 10.0 / 20.5 | 7.3 / 9.8 | 5.3 / 6.3 | 7.3 / 18.2 |
| dashboard: `GET /ImportHistory/GetAll` (13.5 kB) | 11.7 / 22.7 | 8.9 / 13.8 | 7.0 / 8.9 | 9.2 / 11.9 |
| **dashboard: the 4 calls in parallel** (16.5 MB) | 388.8 / 545.7 | 352.3 / 476.4 | 235.2 / 293.5 | 248.4 / 311.4 |
| **`GET /api/ingestion/jobs?page=0&size=50&view=summary`** | 311.1 / 477.7 (16.5 MB: ignored) | 320.6 / 396.7 (16.5 MB) | **10.9 / 14.4 (20.3 kB)** | 10.2 / 16.1 |
| **`GET /api/dashboard/summary`** | 404 | 404 | **12.0 / 14.5 (4.3 kB)** | 13.3 / 22.2 |

Reading:
- **What replaces what.** The SPA's job list now asks for the summary page
  (20.3 kB, `{items, page, size, total}`) instead of the 16.5 MB array; the
  dashboard makes one summary call (4.3 kB) instead of the four lists
  (it still also reads `/Templates/GetAll`, 192 B, for template names).
  Both new routes stay flat with the data set (SE4's statement-count tests).
- The **compat list** (no `view`) keeps its 16.5 MB shape for 50 jobs (cap at
  200 jobs) but is ~20 % faster at the same load (330 → 268 ms p50): the
  batched reads and the single auth read per request (SE1/SE4).
- The small calls (`me`, templates, imports) are 15–25 % faster at the same
  load — consistent with one AppUsers read per request instead of several.
- `POST /api/auth/login` is dominated by the password hash (CPU) and moves
  with the load: p95 75 → 118 ms in the paired runs, 139 ms at load 5 in the
  re-run. **Noise, not a regression** (the hashing code did not change; p50
  improved 58 → 53 ms).

## 3. Frontend cold load (login → dashboard)

Fresh Chromium context, `/login`, sign in, wait until the dashboard's data
calls are done; 3 runs, byte counts identical, timings median.

| | Baseline (vite preview) | **Wave 2 (vite preview)** | **Wave 2 through nginx** (`frontend/nginx.conf`) |
|---|---:|---:|---:|
| Requests | 11 | 10 | 10 |
| HTML | 395 B | 537 B | 537 B (below `gzip_min_length`) |
| JS | 1 file, 614.9 kB raw / 182.9 kB served | 2 files, 292.2 kB raw / 93.0 kB served (gzip-6) | 2 files, 292.2 kB raw / **108.1 kB on the wire** |
| CSS | 39.0 kB / 7.6 kB | 39.5 kB / 7.6 kB | 39.5 kB / 9.0 kB |
| API (providers, login, dashboard calls, announcements, version) | 8 calls, 16.5 MB | 6 calls, 5.4 kB | 6 calls, 5.4 kB raw / 1.7 kB on the wire |
| `Cache-Control` on `/assets` | `no-cache` (vite) / none (old nginx) | `no-cache` (vite) | `public, max-age=31536000, immutable` |
| Login page ready / dashboard visible / data loaded | 189 / 293 / 769 ms (load 7) — re-run 140 / 137 / 504 ms (load 1.8) | 111 / 143 / 149 ms (load 2) | 162 / 157 / 229 ms (load 5) |

- **`qrcode` is no longer on the login path** (it was inside the single
  614.9 kB chunk): the cold load fetches `index` + `vendor` only (plan L3).
- `npm run build`: 1 chunk → 55 (an `index`, a `vendor`, one per lazy route
  page); all JS together 614.9 → 654.6 kB raw (+6 %, the price of splitting),
  but a route chunk is only fetched when its page is opened. Build 3.1 s → 3.3 s.
- **nginx compression level.** nginx gzips at its default `gzip_comp_level 1`:
  108.1 kB on the wire for the initial JS against 93.0 kB at level 6 (+16 %).
  Raising it to 5–6 (or serving pre-compressed `.gz` with `gzip_static`) would
  save ~15 kB on every cold load — see the sign-off, OBS-03.
- Header checks against the same nginx (test plan C1–C6, C8): 38/38 PASS
  (`results/wave2-run2/nginx.json`): gzip + `immutable` + `Vary` on JS/CSS, a
  real 404 for a missing chunk (not cached), `no-cache` on `/`, `/index.html`
  and deep links, API JSON gzipped with the backend's own `Cache-Control`
  and a single CSP, the security header set on every static answer, the
  favicon served. The poll (one 1,000-row job) goes 328 kB → 44 kB on the wire.

## 4. Footprint

`mvn -B -DskipTests package` (21 s, incremental on a warm `~/.m2`; not a
clean-build time): **`target/modocument-1.1.0.jar` = 157,178,551 bytes
(149.9 MiB)**, was 369,187,364 (352.1 MiB): **−212 MB (−57 %)**. 196 nested
jars (147.1 MiB; was 177 / 349.4 MiB — the AWS SDK adds many small jars),
application classes 5.4 MiB.

| # | Nested jar | MiB | Baseline rank |
|---|---|---:|---|
| 1 | `hibernate-core-6.6.18.Final.jar` | 11.5 | 2 |
| 2 | `tess4j-5.13.0.jar` | 8.7 | 4 |
| 3 | `byte-buddy-1.17.6.jar` | 8.6 | 5 |
| 4 | `bcprov-jdk18on-1.78.1.jar` | 7.9 | 6 |
| 5 | `ojdbc11-23.7.0.25.01.jar` | 7.0 | 7 |
| 6 | `poi-ooxml-lite-5.3.0.jar` | 5.7 | 9 |
| 7 | `s3-2.55.5.jar` (new: S3 file store) | 4.2 | — |
| 8 | `lept4j-1.19.1.jar` | 3.6 | 10 |
| 9 | `tomcat-embed-core-10.1.42.jar` | 3.5 | — |
| 10 | `jtokkit-1.1.0.jar` | 3.1 | — |

Gone: Playwright's `driver-bundle` (189.2 MiB), `kafka-clients` (8.8) and
`zstd-jni` (6.4) are no longer in the default jar (Kafka/Mongo come only with
the `scale` Maven profile). tess4j/lept4j stay (OCR via the image's Tesseract).

**Startup** (`java -Xmx1536m -jar`, production profile, PostgreSQL + Flyway,
"Started MoDocumentApplication in"): **16.9 s at load 2.1** and 20.0 s at
load 4–5; the paired baseline re-run 17.9 s at load 1.8 (the original
baseline 23.7 s at load 7, and 43.9 s once). Same-load difference ≈ −6 %,
inside the noise of this box. The CDS archive only exists in the container
image (`Dockerfile` training run), so the startup target of the plan (D7) has
to be read from CI's image start, not from `java -jar`. The image size is
not measurable here (no Docker): `docker image inspect` in CI.

## 5. Test suites

| | Baseline (`BASELINE.md`) | Baseline re-run | **Wave 2 (`8d1e272`)** | Final (QA Wave-2 tests added) |
|---|---:|---:|---:|---:|
| `mvn -B verify` wall (H2, incl. compile, JaCoCo, repackage) | 316 s (load 1.9 → 3.9) | 239 s (load 2.8 → 2.2) | **215 s** (load ~2) | 226 s (load 3.8 → 4.0) |
| Tests run / failures / skipped | 1,752 / 0 / 0 | 1,752 / 1 env / 0 | 1,875 / 0 / 0 | 1,915 / 0 / 3 (`@Disabled` BUG-10, -11, -12) |
| Test classes | 236 | 236 | 261 | 269 |
| Spring "Started" lines (contexts + 1 non-test start) | 28 | 28 | 21 | **20** |
| Time starting contexts | 91.2 s | 81.8 s | 60.9 s | 69.6 s (load 4) |
| Sum of class times | 266.5 s | 227 s | 195 s | — |
| `npx vitest run` | 117 / 118 s (load 7 → 15), 456 tests | **35 s** (load 1.3 → 2.9), 456 | **37 s** (load 1.3 → 3.7), 501 | — |

- The suite is **10 % faster at the same load with 7 % more tests**; the
  saving is the contexts (SE5's surefire/JaCoCo split is CI-side and does
  not show in a local `verify`).
- Wave 1 had added one context back (`HousekeepingJobTest`, a plain
  `@SpringBootTest`); QA folded it into the shared MockMvc context
  (`@AutoConfigureMockMvc`, test code only) → 19 test contexts again (G9).
- The baseline's 117 s Vitest was the load (7 → 15); at the same load the
  suite takes the same time as before (35 → 37 s for 45 more tests).
- The first scripted Vitest run failed 1/501:
  `IntegrationRunDetailPage.test.jsx` read `run-status` right after the
  route-derived heading appeared, racing the mocked `getRun` (pre-existing,
  load-dependent). Fixed in the test (`findByTestId`); 501/501 since.

## 6. Not measured here

- Container image size and CDS start (needs Docker: CI `docker image inspect`
  and the image's start log).
- MinIO S3 contract IT (`-Ps3-it`, Testcontainers), the CloudFront
  `/assets/*` behaviour and `terraform plan` (need Docker/AWS).
- The alert-sweep `EXPLAIN` is SE1's `PerfIndexPlanPgIT` (green in G2), the
  output-token saving SE4's `RowBatchOutputTokensTest` (green in G1).
