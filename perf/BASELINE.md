# Optimization round (v1.2.0) — baseline measurements

**Commit measured:** `bb845a5` ("Optimization Wave 0"; production code
identical to v1.1.0 `57e98ba` plus the Wave-0 contracts). Measured
2026-10-11 between 03:22 and 03:50 UTC by QA, before any Wave-1 change.
The working tree only carried the new `perf/` scripts and QA's test-code
changes (the `dirtyTree: true` in the JSON files); the jar and the SPA were
built from `bb845a5`'s production sources.

> **Read these numbers as relative, not absolute.** They were taken on a
> shared 4-vCPU / 15.7 GiB sandbox while five engineers were building and
> testing on the same box. Load averages (1/5/15 min) during each step are
> given below; they ranged from **0.2 to 15**. Compare the Wave-2 run only
> against this baseline at a similar load (re-run both if the load differs
> much; `perf/README.md` → "Wave 2"). Payload sizes, request counts, jar
> sizes, chunk sizes and context counts do not depend on load; latencies,
> startup and suite times do.

Units: this page uses decimal kB/MB; the JSON files hold bytes and
`perf/compare.mjs` prints KiB/MiB.

Raw results: `perf/results/baseline/*.json` (the stack run 03:41–03:43 UTC),
`perf/results/qa-context-fold/test-suite.json` (the suite after Part B).

## How it was measured

```sh
# backend suite (H2), timed, then profiled
mvn -B verify                               # 316 s wall, log kept as results/baseline/mvn-verify.log (git-ignored)
node perf/test-suite.mjs perf/results/baseline/mvn-verify.log target/surefire-reports 316

# everything else (jar + SPA build, local stack, seed, API, browser, footprint)
perf/run.sh baseline                        # = mvn -B -DskipTests package; node perf/build-sizes.mjs;
                                            #   SKIP_BUILD=1 E2E_RUN_CMD='node perf/seed.mjs && node perf/api-bench.mjs
                                            #   && node perf/frontend-load.mjs && node perf/footprint.mjs' e2e/run-local.sh

# frontend unit suite
cd frontend && npx vitest run               # timed with date +%s
```

Stack: `e2e/run-local.sh` — PostgreSQL 16 scratch cluster (`127.0.0.1:55432`,
`fsync=off`), fresh database, backend jar with the production profile as the
RLS-subject role `modocument_app`, `-Xmx1536m`, the fake LLM, and the built
SPA behind `vite preview`.

Data set: one bank user (the e2e READ_WRITE operator, no MFA) owning
**50 ingestion jobs × 1,000 rows** (6 columns: id, name, department, email,
salary, date), each uploaded as its own CSV through `POST /api/ingestion/jobs`
and run to `VALIDATED` (`wait=true`). All 50 went through the API (no SQL
clones were needed): upload-to-VALIDATED p50 **591 ms**, seeding 32 s.

## 1. API timings and payloads

Node `http` client straight to the backend port, keep-alive, `Accept-Encoding:
gzip, deflate, br`; 3 warm-up calls then 20 sequential timed calls per case;
latency = time to the last body byte. Load average during the run: 7.7 / 6.1 / 3.8.

| Case | p50 ms | p95 ms | Response (raw) | On the wire |
|---|---:|---:|---:|---:|
| `POST /api/auth/login` | 64.5 | 156.3 | 438 B | 438 B |
| `GET /api/auth/me` | 8.2 | 16.7 | 167 B | 167 B |
| **`GET /api/ingestion/jobs`** (50 jobs) | **282.8** | **335.8** | **16.5 MB** | 16.5 MB |
| `GET /api/ingestion/jobs/{id}` (1,000 rows — the poll) | 16.4 | 28.4 | 328 kB | 328 kB |
| dashboard: `GET /Templates/GetAll` | 9.6 | 21.5 | 192 B | 192 B |
| dashboard: `GET /api/ingestion/jobs` | 420.7 | 684.8 | 16.5 MB | 16.5 MB |
| dashboard: `GET /api/extraction/jobs` | 10.0 | 20.5 | 2 B | 2 B |
| dashboard: `GET /ImportHistory/GetAll` | 11.7 | 22.7 | 13.5 kB | 13.5 kB |
| **dashboard: the 4 calls in parallel** (page load) | **388.8** | **545.7** | **16.5 MB** | 16.5 MB |
| `GET /api/ingestion/jobs?page=0&size=50&view=summary` | 311.1 | 477.7 | 16.5 MB | 16.5 MB |
| `GET /api/dashboard/summary` | — | — | 404 (not on this build) | |

Reading:
- The jobs list is **~330 kB per job** (parsed, mapped and validation JSON of
  every job), so it grows linearly with the job count and is the whole
  dashboard cost: 99.9 % of the dashboard's bytes are the one list the page
  only counts.
- The summary query parameters are **ignored** on this build (same 16.5 MB
  array) — that row is the "before" for SE4's summary endpoint.
- **No response compression** anywhere on the API path (backend `identity`
  even with `Accept-Encoding: gzip`; the vite proxy passes it through).
  nginx in production is configured without gzip too (`frontend/nginx.conf`).
- The 1,000-row single job (the poll) is fast; its cost is the 328 kB body
  every poll interval.

## 2. Frontend cold load (login → dashboard)

Fresh Chromium context (empty cache), `/login`, sign in, wait until the
dashboard's data calls are done; 3 runs (byte counts identical; timings
median). Served by `vite preview`, which gzips static assets — production
nginx does **not** yet (so "as served" below is what nginx would send *with*
gzip on; today it sends the raw column). Load: 7.3 / 6.1 / 3.8.

| | Requests | Raw | As served (vite preview, gzip) |
|---|---:|---:|---:|
| HTML | 1 | 395 B | 407 B |
| **JS** | **1** | **614.9 kB** | **182.9 kB** |
| CSS | 1 | 39.0 kB | 7.6 kB |
| API (login page: providers; after sign-in: login, 4 dashboard lists, announcements, version) | 8 | 16.5 MB | 16.5 MB |
| **Total** | **11** | | |

- Initial JS (everything the login page loads): **614.9 kB raw / 182.8 kB
  gzip / 148.4 kB brotli**, one chunk. Target for Wave 2: ≤ 300 kB raw
  (≈ 90 kB gzip).
- `index.html` and assets are all served `Cache-Control: no-cache` by vite
  preview; nginx today sends no cache headers on `/assets/`.
- Timings (median): login page ready 189 ms; after "Sign in" the dashboard
  heading shows at 293 ms and its data has arrived at **769 ms**.
- `qrcode` is inside the single chunk (no separate request), so every login
  pays for it.

`npm run build` (Vite 6.4.3): **4.4 s**; output 1 JS chunk 614.9 kB
(gzip 182.8 kB, brotli 148.4 kB), 1 CSS 39.0 kB (gzip 7.5 kB), `index.html`
0.4 kB. Vite warns the chunk is over 500 kB.

## 3. Footprint

`mvn -B -DskipTests package` (9 s — incremental on an already compiled `target/`, warm `~/.m2`; not a clean-build time):
**`target/modocument-1.1.0.jar` = 369,187,364 bytes (352.1 MiB / 369 MB)**,
177 nested jars (349.4 MiB), application classes 5 MiB.

| # | Nested jar | MiB |
|---|---|---:|
| 1 | `driver-bundle-1.49.0.jar` (Playwright browsers driver) | **189.2** |
| 2 | `hibernate-core-6.6.18.Final.jar` | 11.5 |
| 3 | `kafka-clients-3.9.1.jar` | 8.8 |
| 4 | `tess4j-5.13.0.jar` | 8.7 |
| 5 | `byte-buddy-1.17.6.jar` | 8.6 |
| 6 | `bcprov-jdk18on-1.78.1.jar` | 7.9 |
| 7 | `ojdbc11-23.7.0.25.01.jar` | 7.0 |
| 8 | `zstd-jni-1.5.6-4.jar` (Kafka compression) | 6.4 |
| 9 | `poi-ooxml-lite-5.3.0.jar` | 5.7 |
| 10 | `lept4j-1.19.1.jar` (tess4j) | 3.6 |

Playwright alone is 54 % of the jar; with Kafka (+zstd) and tess4j/lept4j the
plan's removals target ~217 MiB.

**Startup** (`java -Xmx1536m -jar`, production profile, PostgreSQL + Flyway,
time to "Started MoDocumentApplication"): **23.7 s** (process running for
25.4 s) at load 7.3 / 6.1 / 3.8. An earlier run of the same jar, at load
~4 → 8, took 43.9 s: startup is the most load-sensitive number here; re-run
it a few times in Wave 2. The container image size is not measured here (no
Docker in the sandbox): take it from CI's `docker image inspect`.

## 4. Test-suite time

| | Baseline (`bb845a5`) | After Part B (QA test-code changes) |
|---|---:|---:|
| `mvn -B verify` wall (H2, incl. compile, JaCoCo, repackage) | **316 s** (load 1.9 → 3.9) | **277 s** (load 0.2 → 4.4) |
| Tests run / failures / skipped | 1,752 / 0 / 0 | 1,752 / 0 / 0 |
| Test classes | 236 | 236 |
| **Spring test contexts started** | **27** (+1 non-test `SpringApplication` start in `SecretFileEnvironmentPostProcessorTest` = 28 "Started" lines) | **19** (20 lines) |
| Time spent starting contexts (sum of "Started … in") | 91.2 s | 73.3 s |
| Sum of class times (surefire) | 266.5 s | 253.3 s |
| `AuthUseCaseTest` | 8.4 s | 1.2 s |
| `npx vitest run` (54 files, 456 tests) | 117 s / 118 s (two runs, load 7 → 15) | unchanged (no frontend change) |

Paired back-to-back re-run at the same (heavy) load: see §6.

Vitest note: the first run reported 456/456 passed but exited 1 on one
unhandled error — `ReferenceError: window is not defined` from a
`ToastContext` dismiss timer (`src/context/ToastContext.jsx:52`) firing after
`IngestionJobPage.test.jsx`'s jsdom environment was torn down (load 14);
the second run was clean. A load-dependent flake in the frontend gate, for
SE3 (clear the toast timers on unmount / use fake timers in that test).

Slowest backend classes at baseline (surefire time, incl. their context start):
`SqlExtractionStoreTest` 26.0 s (first context of the run: JVM warm-up),
`DocReleaseSodIntegrationTest` 16.1 s, `MaskingGateC2Test` 13.6 s,
`IntegrationApiSecurityTest` 11.3 s, `IngestionFallbackIntegrationTest` 11.2 s,
`DocCreationEndToEndTest` 10.5 s, `ExtractionModeResolutionApiTest` 9.3 s,
`AuthUseCaseTest` 8.4 s, `IntegrationHubSecurityQaTest` 7.7 s,
`UserAdminActionsIntegrationTest` 7.3 s.

Context loads at baseline, in order (each a distinct configuration; the
32-entry context cache never evicted): `SqlExtractionStoreTest`
(`extraction.store=sql`), `IngestionJobOptimisticLockTest` (plain, no
MockMvc), `LogRedactionCanaryTest` (the main MockMvc context),
`UserAdminActionsIntegrationTest`, `ApiRateLimitIntegrationTest`,
`TemplateVisibilityMakerCheckerTest`, `SyntheticProbeTest`
(DetectionGateTestSupport), `RetentionPurgeGateC5Test`,
`IngestionFallbackIntegrationTest`, `MaskingGateC2Test`, `ApiDocsExposureTest`,
`BankCreateAtomicityTest`, `IntegrationHubSecurityQaTest`,
`IntegrationApiSecurityTest`, `HeaderContractTest`, `IntegrationAgentApiTest`,
`AgentProtocolSecurityChainTest`, `ExtractionModeResolutionApiTest`,
`DataSubjectRightsGateC12Test`, `DocReleaseSodIntegrationTest`,
`AuthProvidersConfiguredTest`, `LlmQuotaRetryAfterIntegrationTest`,
`UploadContentGateSec030Test`, `UploadOwnershipEnforcementTest`,
`IntegrationEngineTest` (IntegrationTestSupport), `ExtractionEndToEndTest`,
`DocCreationEndToEndTest`.

## 5. Part B — what was folded (test code only)

| Fold | Classes | Contexts |
|---|---|---|
| Plain `@SpringBootTest` → `+@AutoConfigureMockMvc` (same configuration as the API tests; MockMvc beans are inert for them) | IngestionJobOptimisticLockTest, CrossUserAccessTest, AgentOfflineSweepTest, RevocationValidatorTest, BootstrapAdminRunnerTest, BootstrapPlatformRunnerTest, ContextLoadsAndSeedTest, RoutePermissionCoverageTest, DocCreationOwnershipIntegrationTest | 2 → 1 |
| `@ProdShapedSpringBootTest` (testsupport): one production-shaped property set and its own H2 database | ApiDocsExposureTest, HeaderContractTest | 2 → 1 |
| `AiStubApiTestSupport`: one JVM-wide `StubAiServer` + storage root, dynamic properties on the base only | IngestionFallbackIntegrationTest, ExtractionModeResolutionApiTest | 2 → 1 |
| `IsolatedStorageApiTestSupport`: one JVM-wide storage root (`SharedTestStorage`), doc-creation AI off | MaskingGateC2Test, DataSubjectRightsGateC12Test, DocReleaseSodIntegrationTest, UploadContentGateSec030Test, UploadOwnershipEnforcementTest | 5 → 1 |
| `OnePermitRateLimitTestSupport`: every bucket + the LLM quota at one permit (fresh users per test) | ApiRateLimitIntegrationTest, LlmQuotaRetryAfterIntegrationTest | 2 → 1 |
| `AuthUseCaseTest`: BCrypt strength 4 for the plain encoder (no test there is about hashing cost; the SEC-018 rehash tests keep the production Argon2id/BCrypt delegating encoder) | — | 8.4 s → 1.2 s |

No test, assertion or `@Disabled` was removed or added; every assertion is
unchanged. Left alone on purpose (folding would change what they test or
needs a per-test review): `SqlExtractionStoreTest` (`extraction.store=sql`),
`ExtractionEndToEndTest` (worker on), `RetentionPurgeGateC5Test` (purge
scheduler on), `UserAdminActionsIntegrationTest` (admin-security = 5),
`AuthProvidersConfiguredTest` (OIDC providers), `IntegrationHubSecurityQaTest`
(SSRF guard on), `TemplateVisibilityMakerCheckerTest` (maker-checker on — already
shared with `MakerCheckerIntegrationTest`), `DocCreationEndToEndTest`
(asserts global emptiness of blueprint/instance lists, relies on a fresh
schema), and the four `@MockitoBean`/`@MockitoSpyBean` contexts
(`IntegrationApiSecurityTest`, `IntegrationAgentApiTest`,
`AgentProtocolSecurityChainTest`, `BankCreateAtomicityTest`).

### Production changes that would help (for the Supervisor; not made)

1. **API response compression** (`server.compression.enabled=true`,
   `mime-types` incl. `application/json`, `min-response-size` ~2 KB): the
   16.5 MB jobs list is highly repetitive JSON (not measured here; gzip
   typically cuts JSON like this 5–10×) and
   every 328 kB poll too. Cheap, independent of SE4's summary endpoint, but
   it belongs to `application.yml` (SE1) — and the nginx `gzip` for
   `application/json` (SE3) covers the production path anyway.
2. **Runtime switches instead of context properties** for the background
   jobs the tests turn on per context (`modocument.retention.enabled`,
   `extraction.worker.enabled`, `modocument.ratelimit.*` read once at
   startup): if the tests could flip them per test (they are partly ws20
   settings already), `RetentionPurgeGateC5Test`, `ExtractionEndToEndTest`,
   `UserAdminActionsIntegrationTest` and the rate-limit context could join
   the main context (another −4).
3. **Surefire fork reuse / JaCoCo off by default** (SE5's CI plan): the
   JaCoCo agent and the report run in every local `verify`; behind a
   `coverage` profile the local suite gets faster at no coverage loss in CI.

## 6. Paired re-run (same box, back to back, heavy load)

Both halves ran one after the other (03:56–04:16 UTC) at load average
**12 → 15** (the other engineers' builds), so the absolute times are about
twice §4's; the ratio is what counts. "Before" is a `git archive bb845a5`
copy: it has no `.git`, so `SystemInfoControllerTest.versionHasTheContractShape`
fails there on the missing commit id (environmental; it passes in the
worktree) and the build stops after surefire — without the jar/JaCoCo
steps the "after" run includes — and it compiled main + test from scratch
where "after" recompiled the tests only.

| | Before (`bb845a5`) | After (QA test changes) | Δ |
|---|---:|---:|---:|
| Spring contexts started ("Started" lines) | 28 | 20 | −8 (−29 %) |
| Time starting contexts | 237.1 s | 199.9 s | −16 % |
| Sum of class times | 542.9 s | 519.4 s | −4 % |
| `AuthUseCaseTest` | 9.4 s | 1.2 s | −87 % |
| `mvn -B verify` wall | 617 s (stopped after the tests) | 586 s (complete) | ≈ −5 to −10 % |

Takeaway: 8 fewer contexts (27 → 19 test contexts) and ~16–20 % less time
building them, but context starts are only about a third of the suite's
test time, so the wall-clock gain is ~10 % at best (§4: 316 s → 277 s at
low load). The rest is real test work (MockMvc round trips on a shared H2,
document generation, the AI-stub timeouts); the next lever is not more
folding but two surefire forks and JaCoCo behind a profile (SE5, `pom.xml`).
