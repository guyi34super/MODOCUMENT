# Optimization round (v1.2.0) — QA sign-off (Wave 2, release candidate v1.2.0-rc.1)

Owner: QA. Date: 2026-10-11. Scope: the five Wave-1 workstreams of the
optimization round — SE1 database & runtime, SE2 audit logs & API hygiene,
SE3 frontend load & audit UI, SE4 hot read paths & AI cost, SE5 footprint,
S3 file store & CI — on the integrated branch `claude/tender-noether-izbf0u`
at `8d1e272`, **plus the Wave-2 shared connector library (connector-core,
`6627af4`)**, merged into QA's tree after the measurements, plus QA's Wave-2
commits (test code, perf scripts and docs only). The gates, the E2E suite
and the connector checks below ran on the merged head. Plan and checklist: [`optimization-test-plan.md`](optimization-test-plan.md)
(every row now carries its status and evidence); contracts:
[`docs/optimization/contracts.md`](../optimization/contracts.md) incl. §10
"As built"; measurements: [`perf/WAVE2.md`](../../perf/WAVE2.md) (before:
[`perf/BASELINE.md`](../../perf/BASELINE.md)).

## 1. Recommendation

**GO for `v1.2.0-rc.1`**, as a release candidate for staging, with the
conditions below. Every speed, load and footprint target of the plan is met
with a wide margin (§2), all gates are green (§3), and there is **no open P1
or P2 bug**. The four bugs QA found are P3 — audit-trail completeness, an
orphaned-file path on a failed delete and a UX gap — each with a committed
repro test (§4); none weakens tenant isolation, authentication or the SSRF
controls.

Conditions:
1. Bump the release version before tagging: `pom.xml`, `agent/pom.xml`,
   `frontend/package.json` still say `1.1.0` (`scripts/bump-version.sh`,
   checked by `scripts/check-version-sync.sh`).
2. ~~BUG-10, BUG-11 (unaudited ingestion edits) and BUG-12 (a failed object
   delete after a purge/erase is never retried) are fixed before `v1.2.0`
   (final), or explicitly waived by the Supervisor.~~ **Met — fixed after
   rc.1** (§4a): the repros run enabled and pass.
3. The items of §5 that need Docker or AWS (image size and CDS start, MinIO
   S3 contract IT, `terraform plan` incl. CloudFront) are checked in CI /
   on staging before `v1.2.0`.
4. ~~Supervisor decision on OBS-01 (the `access-denied-burst` rule counts
   minutes, so a one-minute flood does not alert) — fix or accept.~~
   **Fixed after rc.1** (§4a): the rule counts refusals.

## 2. Results per area

| Area | Owner | Evidence (lowest level first) | Result |
|---|---|---|---|
| **Speed & DB** | SE1, SE4 | `ReadPathQueryCountTest` (flat statement counts), `RequestPrincipalStateQueryCountTest` (one AppUsers read), `PerfIndexPlanPgIT` (index use), `HousekeepingJobTest` + `HousekeepingRlsPgIT` + QA `HousekeepingRetentionSettingQaTest`, QA `IngestionSummaryPagingQaTest`; perf API bench | **Accepted.** Jobs list 16.5 MB → 20.3 kB, p50 330 → 11 ms; dashboard data 16.5 MB → 4.3 kB, p50 352 → 12 ms (same load) |
| **Frontend load** | SE3 | Vitest (`preloadRecovery`, `usePolling`, paged pages, `AuditLogPage`), perf cold load (vite + real nginx), `perf/nginx-check.sh` 38/38, E2E OPT-L1/L1b/X3 | **Accepted** with BUG-09 open. Initial JS 614.9 → 292.2 kB raw (≤ 300 kB ✓), 182.8 → 93.0 kB gzip; cold-load API bytes 16.5 MB → 5.4 kB; data loaded 504 → 149 ms after sign-in |
| **Cost & footprint** | SE4, SE5 | jar inspection, `RowBatchOutputTokensTest`, `IngestionParseStepImplTest` cache tests, QA `AiResultCacheRlsPgIT`, E2E OPT-P7, `OcrServiceTest`, `S3FileStoreTest`, `FileStoreContract` + QA `FileStoreTraversalQaTest`, QA `AfterCommitFileDeleterQaTest` | **Accepted** (BUG-12 fixed, §4a). Jar −212 MB (−57 %, Playwright/Kafka/zstd out); a re-uploaded document makes zero model calls; no cross-bank cache hit under RLS. Image/CDS: §5 |
| **Code health / API hygiene** | SE2, SE5 | `DeprecatedEndpointsTest`, QA `ApiHygieneQaTest` (20 deprecated routes × 3 headers, removed routes 404 × 4 roles × 3 methods), `RestApiConnectorRedirectTest` + QA `HttpWebFetcherRedirectQaTest` (crawler), `DatabaseExportServiceTest`; suite −10 % at the same load, 19 test contexts | **Accepted** |
| **Connector library (connector-core)** | connector engineer | `mvn -B -f connector-core/pom.xml verify` (25: two-phase SFTP read, oversized file noted and left, REST PAGE without a size reads to the first empty page, DB identifiers), agent `RestConnectorPaginationTest` / `DatabaseConnectorSqlTest`, `SftpIntegrationConnectorTest`; QA `SftpInboundPartialFailureQaTest` (2: through the real `FlowRunner`); E2E integration hub 25 live incl. the agent DB pull (quoted identifiers) | **Accepted.** A file that fails ingestion mid-run stays in the folder, the files after it are still ingested and moved, the run FAILS naming the file; an oversized file is skipped, left and noted. AC-INT-15 (SFTP E2E) needs the compose simulator and did not need a change (it asserts only the happy path, still valid) |
| **Audit** | SE2 (SE3 UI) | `AuditTrailGapsTest`, `AuditLogApiTest`, `AuditLogRlsPgIT`, `AuditThrottleTest`, `JpaAuditRecorderThrottleTest`, `AuditActionCatalogueDocTest`; QA `AccessDeniedBurstAlertTest`, `AuditViewAuditQaTest`, `IngestionEditAuditQaTest`; E2E OPT-V9 | **Accepted** (BUG-10, BUG-11, OBS-01 fixed, §4a). Viewer RLS-isolated (PG IT + E2E across two onboarded banks); viewing audited once per 15 min, no recursion |

### Checklist summary (`optimization-test-plan.md`)

86 rows carry a status: **78 PASS** (`[x]`), **4 FAIL → bug** (`[!]`: A6
BUG-10, A7 BUG-11, L1 BUG-09, F4 BUG-12 — A6, A7 and F4 are `[x]` since the
§4a fixes: **81 PASS, 1 FAIL**), **4 open** (`[ ]`: C7 CloudFront
needs AWS; F2 the store has no per-bank key prefix to test — cross-bank
access is refused above it; D2, D8 owner configuration / CI, not re-verified
by QA). The items the Supervisor asked for:

| Item | Status | Test |
|---|---|---|
| Each new audit action once, right actor/entity (§4) | PASS (BUG-10, BUG-11 fixed, §4a) | `AuditTrailGapsTest` (8 tests), `DocInstanceControllerAuditTest`, `ExtractionJobControllerAuditTest`; `IngestionEditAuditQaTest#aManualMappingChangeIsAuditedOnce`, `#aChatEditIsAuditedOnceWithoutCellValues` (enabled) |
| Audit viewer RLS-isolated | PASS | `AuditLogRlsPgIT` (3), `AuditLogApiTest#anotherBanksIdsCursorsAndFiltersFindNothing`, E2E OPT-V9 |
| Viewing the audit log audited, deduplicated | PASS | `AuditViewAuditQaTest` (3, QA), `AuditLogApiTest#viewingIsAuditedOncePerActorWithinTheWindow`, `AuditThrottleTest#dedupeWritesTheFirstReadPerWindowAndReleaseRetries` |
| SSRF redirect refusal (REST connector + crawler) | PASS | `RestApiConnectorRedirectTest` (4), `HttpWebFetcherRedirectQaTest` (4, QA: metadata, private, unlisted loopback, IPv6 loopback, `file:`, 2nd-hop refusal, loop bound) |
| `/api/aifiles/send`, `/api/transform/transform` → 404 | PASS | `ApiHygieneQaTest#theRemovedRoutesAre404ForEveryRoleAndMethod` (24 calls), absent from `/v3/api-docs` (`DeprecatedEndpointsTest`) |
| Deprecation/Sunset/Link headers | PASS | `ApiHygieneQaTest#everyDeprecatedRouteCarriesTheThreeHeaders` (all 20 rows of `versioning-policy.md` §7), `#currentRoutesCarryNoDeprecationHeaders` |
| FileStore key traversal refused | PASS | `FileStoreContract#unsafeKeysAreRejectedByEveryOperation` (local), `FileStoreKeysTest`, `S3FileStoreTest#unsafeKeysNeverReachS3`, `FileStoreTraversalQaTest` (3, QA: NUL/drive/UNC per operation; `%2e%2e` is a literal name inside the root) |
| Housekeeping deletes only past-window rows | PASS | `HousekeepingJobTest` (6), `HousekeepingRlsPgIT` (2), `HousekeepingRetentionSettingQaTest` (2, QA: per-bank `integration.historyRetention`, 7–400 d clamp) |
| Ingestion summary paging; users / changes paging | PASS | `ReadPathQueryCountTest` (summary, cap, changes), `IngestionSummaryPagingQaTest` (2, QA), `UserAdminIntegrationTest#theUserListPagesWithTheSharedEnvelopeAndStaysInTheCallersBank` |
| Lazy-route stale-chunk reload | PASS (reload once, no loop) / **BUG-09** (blank page after) | Vitest `preloadRecovery.test.js`; E2E OPT-L1, OPT-L1b; OPT-L1c `test.fixme` |
| gzip and cache headers on nginx | PASS | `perf/nginx-check.sh` → `perf/results/wave2-run2/nginx.json` (38/38, real `frontend/nginx.conf` on nginx 1.24), `scripts/check-nginx-headers.sh` |
| AI result cache: hit on re-upload, none across banks | PASS | E2E OPT-P7 (zero fake-LLM calls), `IngestionParseStepImplTest` (2), `AiResultCacheRlsPgIT` (QA, RLS) |
| `access-denied-burst` alert | PASS (OBS-01 fixed, §4a) | `AccessDeniedBurstAlertTest` (5: fires once at 3, not at 2, groups by user or IP, window bound, dedupe; a one-minute burst of 50 alerts with 50 events, 2 refusals in a minute do not, the 3rd does) |

## 3. Test inventory (this run)

| Suite | Count | Result |
|---|---|---|
| connector-core (`mvn -B -f connector-core/pom.xml verify`) | 25 | 25 pass |
| Backend H2 (`mvn -B verify`) | 1,918 tests, 270 classes | 1,915 pass, 0 fail, 3 skipped (BUG-10, BUG-11, BUG-12 `@Disabled` repros). 20 Spring "Started" lines = 19 test contexts (G9 ✓); 203 s at load 2.4 → 1.8 |
| Backend PostgreSQL (`-Ppostgres-it`, shared scratch PG 16) | 85 PgITs (+ the H2 suite) | 85 pass |
| Agent module (`mvn -B -f agent/pom.xml verify`) | 41 | 41 pass |
| Frontend Vitest | 501 tests (58 files) | 501 pass (1 load-dependent race fixed in the test, see OBS-06) |
| E2E Playwright (`e2e/run-local.sh`, RLS mode, bank simulator, agent jar; merged head) | 80 | **76 passed, 0 failed, 4 skipped**: 2 `fixme` (AC-EXT-13, OPT-L1c BUG-09), 2 environment (AC-INT-15 SFTP needs compose, AC-INT-21 needs the mTLS listener) |
| nginx contract (`perf/nginx-check.sh`) | 38 checks | 38 pass |
| `scripts/check-nginx-headers.sh`, `check-version-sync.sh` | — | OK (version still 1.1.0, condition 1) |

QA's Wave-2 additions: 11 test classes (46 test cases incl. 20 parameterised
route rows, 3 `@Disabled` repros) — `AccessDeniedBurstAlertTest`,
`ApiHygieneQaTest`, `AuditViewAuditQaTest`, `IngestionEditAuditQaTest`,
`IngestionSummaryPagingQaTest`, `HttpWebFetcherRedirectQaTest`,
`FileStoreTraversalQaTest`, `HousekeepingRetentionSettingQaTest`,
`AfterCommitFileDeleterQaTest`, `SftpInboundPartialFailureQaTest`,
`AiResultCacheRlsPgIT`; an `OK` mode in
`StubAiServer`; 6 E2E specs (`phase2/optimization.spec.js`, 1 fixme); the
`HousekeepingJobTest` context fold; `perf/nginx-check.sh` +
`perf/nginx-headers.mjs`; `perf/run.sh` installs connector-core first. E2E specs adjusted: one (AC-INT-17) — see OBS-07.
No assertion was weakened.

## 4. Open bugs and observations

| ID | Severity | Owner | Finding | Repro / evidence |
|---|---|---|---|---|
| **BUG-09** | P3 | SE3 | **No React error boundary.** When a lazy route chunk is still missing after the one guarded reload (a deploy that removed it, a CDN gap), the rejected `import()` unmounts the whole tree: the user sees a blank white page with no message. Test plan L1 expects the error to surface. | Build, sign in, block or delete `assets/AuditLogPage-*.js`, click "Audit log": one reload, then a blank page (console: failed to fetch dynamically imported module). `e2e/tests/phase2/optimization.spec.js` OPT-L1c (`test.fixme`). Fix: an error boundary around the routes' `<Suspense>` with a "Reload" action |
| **BUG-10** (fixed, §4a) | P3 | SE2 | **`PUT /api/ingestion/jobs/{id}/mapping` is not audited.** The manual mapping editor rewrites the job's mapped rows (which source column feeds which field) without any audit row; contracts §4 / plan A6 name it under `INGESTION_REMAPPED` (`IngestionJobUseCase#updateMapping` has no `audit.success`). | Create a VALIDATED job, `PUT …/mapping {"mapping":{"Name":"City","City":"Name"}}` → 200, `AuditEvent` has no row. `IngestionEditAuditQaTest#aManualMappingChangeIsAuditedOnce` (`@Disabled("BUG-10: …")`, fails on the audit assertion only when enabled) |
| **BUG-11** (fixed, §4a) | P3 | SE2 | **Chat edit is not audited.** `POST /api/ingestion/jobs/{id}/chat-edit` changes cell values (AI-applied) without an `INGESTION_ROWS_EDITED` row; plan A7 names it (`ChatEditTableUseCase#handle` saves the job, no audit). `PUT …/rows` is audited correctly. | VALIDATED job, model answers a changed table, `POST …/chat-edit {"instruction":"Set City to Paris for all rows"}` → 200 with "Paris", no audit row. `IngestionEditAuditQaTest#aChatEditIsAuditedOnceWithoutCellValues` (`@Disabled("BUG-11: …")`) |
| **BUG-12** (fixed, §4a) | P3 | SE1, SE5 | **A failed object delete after a purge / DSR erase / job deletion is lost.** `AfterCommitFileDeleter` deletes the stored files after the row deletes commit, but on an error it only logs `WARN Could not delete stored file` and drops the key; nothing points at the object any more, so it is never retried — customer data outlives its retention or erasure (plan F4: "retried, not lost"). The S3 SDK's own retries cover short blips only; a persistent failure (throttling storm, a missing `s3:DeleteObject` grant) orphans every file of the run. | A `FileStore` whose first `delete` throws `IOException("503 Slow Down")`: `deleteAfterCommit` returns, the object stays forever. `AfterCommitFileDeleterQaTest#aFailedDeleteIsRetriedNotLost` (`@Disabled("BUG-12: …")`). Fix idea: persist failed keys (an outbox row) and retry them from the housekeeping job |
| OBS-01 (fixed, §4a) | Medium (design) | SE2, Supervisor | **`access-denied-burst` counts minutes, not refusals.** ACCESS_DENIED is aggregated per actor-or-IP per entity type per minute (an opening row with `count: 1`, then one flushed row with the rest), and the windowed sweep counts rows. 50 or 5,000 refusals inside one minute = 2 rows < threshold 3 → no alert; three single probes in three minutes do alert. Documented in `application.yml`/`audit-events.md`, but the plan read A15 as "one row with count = 50" and A20 as "fires at threshold". Suggest: sum `newValues.count` in the window for this rule (or a per-minute count threshold). | `AccessDeniedBurstAlertTest` pins the as-built behaviour (fires at 3 rows, not at 2; 50 probes → 2 rows summing to 50) |
| OBS-02 | Low (spec) | SE2, Supervisor | `audit.readAuditEnabled=false` gates `SENSITIVE_DATA_VIEWED` only; `AUDIT_LOG_VIEWED` is always written (plan R4 expected both). Code, setting description and `audit-events.md` agree; arguably the safer choice. Confirm and update the plan. | `JpaAuditRecorder#recordRead` |
| OBS-03 | Low | SE3 | nginx gzips at the default `gzip_comp_level 1`: the initial JS crosses the wire as 108.1 kB vs 93.0 kB at level 6 (+16 %, ~15 kB per cold load). `gzip_comp_level 5` or `gzip_static` with build-time `.gz`/`.br` would recover it. Also: chunked API answers below `gzip_min_length` are gzipped anyway (no Content-Length), so a 2-byte JSON becomes 34 bytes — harmless. | `perf/results/wave2-run2/frontend.json`, `nginx.json` |
| OBS-04 | Info | SE1 | The backend itself never compresses (the API bench sees `identity`); compression is nginx's (and CloudFront's) job. Direct-to-app clients (agent, Integration API on 8443 through nginx — compressed there) are unaffected. | `perf/results/wave2/api.json` |
| OBS-05 | Low (fixed by QA) | SE1 | Wave 1 brought one Spring test context back (`HousekeepingJobTest`, plain `@SpringBootTest`): 20 test contexts. Folded into the shared MockMvc context (test code) → 19 (G9). | `perf/results/qa-wave2-final/test-suite.json` |
| OBS-06 | Low (fixed by QA) | SE3 | Flaky Vitest: `IntegrationRunDetailPage.test.jsx` read `run-status` after the route-derived heading, before the mocked `getRun` resolved; failed 1/501 at load ~2. Test now waits with `findByTestId`. | `perf/results/wave2/vitest.log` (first run) |
| OBS-07 | Info (spec drift) | QA | E2E AC-INT-17 still expected the cloud-metadata connection to be accepted at submit and refused at connect; since the v1.1.0 BUG-06 fix (`bdc8564`) it is refused at submit with 400 — the spec failed on every run of this round. Updated to assert the stricter 400 (not a v1.2.0 change; no assertion weakened). | `e2e/tests/phase2/integration-hub.spec.js` |
| OBS-08 | Low | SE4 | No dedicated PG RLS IT for the ingestion summary query (plan P2); the H2 tests prove owner scoping and the query shares the full list's owner predicate. | `IngestionSummaryPagingQaTest` |
| OBS-09 | Info | SE2 | A permission 403 on the audit routes is audited as `AUTHORIZATION_DENIED` (method security), not `ACCESS_DENIED` as plan V3 phrased it — consistent with the catalogue. | `AuditTrailGapsTest#aUseCaseAccessDeniedIsAuditedButAPermissionDenialStaysAuthorizationDenied` |
| OBS-10 | Info | SE5 | `java -jar` startup is only ≈ 6 % faster at equal load (17.9 → 16.9 s); the CDS gain is image-only and must be read from CI. | `perf/WAVE2.md` §4 |

Bug numbering continues the project series (BUG-01…08 were phase 1/2; this
round's first is BUG-09).

## 4a. Fixes after rc.1 (BUG-10, BUG-11, BUG-12, OBS-01)

| ID | Status | Fix | Test |
|---|---|---|---|
| BUG-10 | **Fixed** | `IngestionJobUseCase#updateMapping` writes one `INGESTION_REMAPPED` row after the save (values: `source: MANUAL`, `mappedColumns` count; no cell values), like the remap path | `IngestionEditAuditQaTest#aManualMappingChangeIsAuditedOnce` (`@Disabled` removed) |
| BUG-11 | **Fixed** | `ChatEditTableUseCase#handle` writes one `INGESTION_ROWS_EDITED` row after the save with `rows` / `changedRows` counts and `source: CHAT_EDIT`, the same shape as `PUT …/rows`; never cell values | `IngestionEditAuditQaTest#aChatEditIsAuditedOnceWithoutCellValues` (`@Disabled` removed) |
| BUG-12 | **Fixed** | `AfterCommitFileDeleter`: 3 in-call attempts per key (100/500 ms apart; after one key exhausts them, one attempt each for the rest of the batch), then the key goes to the bank-scoped, RLS-covered `PendingFileDelete` table (V290, REQUIRES_NEW from `afterCommit`). `PendingFileDeleteSweeper` (every 5 min, `modocument.retention.file-delete-retry.*`; `runAsSystem` lists bank ids only, then `runAs(bank)`) retries with backoff 5 min → 6 h and removes the row only when the store confirms the delete. Wired into the retention purge, DSR erase and job deletion | `AfterCommitFileDeleterQaTest#aFailedDeleteIsRetriedNotLost` (`@Disabled` removed), `AfterCommitFileDeleterTest` (3), `PendingFileDeleteSweeperTest` (3), `PendingFileDeleteRlsPgIT` (RLS read/WITH CHECK, sweep), `RlsPolicyCoveragePgIT` inventory |
| OBS-01 | **Fixed** | The windowed sweep weighs each `ACCESS_DENIED` row by its `newValues.count` (`AuditWindowReader#aggregatedCounts` reads only that number; `AlertSweepJob` sums it per group), so the threshold counts refusals; the alert's event count is the refusals | `AccessDeniedBurstAlertTest#aBurstOfRefusalsInsideOneMinuteAlertsOnTheRefusalsNotTheRows`, `#refusalsBelowTheThresholdInsideOneMinuteDoNotAlertAndTheThirdDoes` |

Not in scope of BUG-12: the doc-creation `StoredFileCleanup` (instance,
asset and blueprint deletes) still logs a failed delete; those are user
deletes of doc-creation content, not the retention/DSR/job paths of F4.

Gates on the fix commit: connector-core installed; backend H2
(`mvn -B verify`) **1,926 tests, 0 failures, 0 skipped** (the three
`@Disabled` repros now run; 20 Spring "Started" lines, unchanged);
PostgreSQL (`-Ppostgres-it`) **86 PgITs pass** (+ `PendingFileDeleteRlsPgIT`)
plus the same H2 suite. No route or settings-catalogue change.

## 5. Needs Docker or AWS (not provable in this sandbox)

| Item | Plan | How to close |
|---|---|---|
| Container image size, AppCDS start time | D7 | CI image job: `docker image inspect` size against the v1.1.0 image; start log "Started … in" with and without `MODOCUMENT_CDS=off` |
| Tesseract in the runtime image: an image-only PDF yields text | D6 | CI image smoke test (`OcrServiceTest#theDebianPackagesMakeOcrAvailable` runs where the packages are) |
| S3 file store against MinIO (the shared `FileStoreContract`, incl. traversal) | F1, F2, F4 | `mvn -B verify -Ps3-it` (Testcontainers) in CI |
| `terraform plan`: right-sized ECS, S3 bucket/KMS, CloudFront `/assets/*` CachingOptimized + compression | C7 | CI `terraform validate` + a staging `plan` |
| nginx in the image (vs the local nginx 1.24 used here) | C1–C6 | CI `docker-compose.e2e.yml` run; same `perf/nginx-check.sh` checks apply with `NGINX_API_UPSTREAM` |
| E2E SFTP (AC-INT-15) and mTLS listener (AC-INT-21) | X1 | CI E2E job with the compose bank simulator |

## 6. Before/after headline

From [`perf/WAVE2.md`](../../perf/WAVE2.md) §1. Latencies compare runs at the
same load (paired baseline re-run, load 1.8, vs Wave 2, load 2.1); sizes and
counts are load-independent.

| | Before (`bb845a5`) | After (`8d1e272`) | Δ |
|---|---:|---:|---:|
| Jobs list payload (full list → summary page) | 16.5 MB | 20.3 kB (1.6 kB gzip) | −99.9 % |
| Jobs list p50 / p95 | 330.5 / 396.7 ms | 10.9 / 14.4 ms | −97 % |
| Dashboard data (4 lists → summary) | 16.5 MB | 4.3 kB | −99.97 % |
| Dashboard data p50 / p95 | 352.3 / 476.4 ms | 12.0 / 14.5 ms | −97 % |
| Cold login → dashboard API bytes | 16.5 MB (8 calls) | 5.4 kB (6 calls) | −99.97 % |
| Initial JS raw / gzip | 614.9 / 182.8 kB | 292.2 / 93.0 kB (108.1 kB as nginx sends it) | −52 % / −49 % |
| Sign-in → dashboard data loaded | 504 ms | 149 ms | −70 % |
| App jar | 369.2 MB | 157.2 MB | −212 MB |
| Startup (`java -jar`) | 17.9 s | 16.9 s | ≈ −6 % (noise) |
| `mvn -B verify` | 239 s / 1,752 tests | 215 s / 1,875 tests | −10 % |
| Spring test contexts | 27 | 19 (after QA's fold) | −8 |
| Vitest | 35 s / 456 tests | 37 s / 501 tests | ≈ |
