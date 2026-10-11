# Optimization round (v1.2.0) — QA test plan

Scope: the five workstreams of the optimization round (plan "Optimization
round (v1.2.0)", contracts in [`docs/optimization/contracts.md`](../optimization/contracts.md)).
QA writes and runs this checklist in Wave 2, after the Wave-1 merges. Each
item names the **owner** (the engineer whose change it verifies and who
fixes a failure) and **where the proof lives**. QA adds a test where the
owner's own tests don't cover the item, and records the result here.

Owners: **SE1** database & runtime (ws27) · **SE2** audit logs & API hygiene
(ws28) · **SE3** frontend load & audit UI · **SE4** hot read paths & AI cost ·
**SE5** footprint, infra, storage, CI (ws29) · **QA**.

Status legend: `[ ]` open · `[x]` passed (with the evidence: test class or
command) · `[!]` failed (bug ID).

## 0. Gates (every merge)

| # | Check | Command | Owner |
|---|---|---|---|
| G1 | H2 suite | `mvn -B verify` | all |
| G2 | PostgreSQL ITs (new: index use, retention, audit RLS, S3 prefix) | `MODOCUMENT_IT_JDBC_URL=jdbc:postgresql://127.0.0.1:55432/postgres MODOCUMENT_IT_SUPERUSER=postgres MODOCUMENT_IT_SUPERUSER_PASSWORD= mvn -B verify -Ppostgres-it` | all |
| G3 | Vitest + build | `cd frontend && npx vitest run && npm run build` | SE3 |
| G4 | E2E, full suite | `e2e/run-local.sh` | QA |
| G5 | agent + `connector-core` builds | `mvn -B -f agent/pom.xml verify` (+ connector-core) | SE4, SE5 |
| G6 | nginx headers, version sync | `scripts/check-nginx-headers.sh`, `scripts/check-version-sync.sh` | SE3, Supervisor |
| G7 | gitleaks, semgrep, actionlint, `terraform validate` | CI | SE5 |
| G8 | Route permission coverage: `RoutePermissionCoverageTest.RESERVED` no longer lists `AUDIT_READ` once SE2's first audit route lands | `mvn -B -Dtest=RoutePermissionCoverageTest test` | SE2 |
| G9 | Spring test contexts do not grow back (baseline 27 → 19 after QA's fold; see `perf/BASELINE.md`) | `node perf/test-suite.mjs <verify.log>` | QA |

## 1. Audit trail (contracts §3, §4)

### 1.1 Each new action fires once, with the correct actor and entity

For every action: drive the feature once through the API (MockMvc, H2),
then read `AuditEvent` and assert **exactly one** new row with the expected
`Action`, `Outcome`, `ActorUserId` (the caller, never the system user unless
noted), `BankId` (the caller's bank), `EntityType` + `EntityId` (the object
acted on) and no raw PII in `OldValues`/`NewValues`. A second identical call
adds exactly one more row (no double emission from controller + use case).

| # | Action | Trigger | Entity | Owner | Status |
|---|---|---|---|---|---|
| A1 | `TEMPLATE_CREATED` | `POST /Templates/CreateWithFields` | Template id | SE2 | [x] `AuditTrailGapsTest#templateFieldAndRuleChangesAreAudited` |
| A2 | `TEMPLATE_FIELD_CREATED` / `_UPDATED` / `_DELETED` | template field CRUD | TemplateField id | SE2 | [x] `AuditTrailGapsTest#templateFieldAndRuleChangesAreAudited` |
| A3 | `VALIDATION_RULE_CREATED` / `_UPDATED` / `_DELETED` | validation-rule CRUD | ValidationRule id | SE2 | [x] `AuditTrailGapsTest#templateFieldAndRuleChangesAreAudited` |
| A4 | `FILE_UPLOADED` | multipart upload (ingestion + `/api/fileupload/upload`) | UploadedFile id / stored name | SE2 | [x] `AuditTrailGapsTest#uploadsAndIngestionJobCreationAreAudited` |
| A5 | `INGESTION_JOB_CREATED` | `POST /api/ingestion/jobs` (multipart and JSON) | IngestionJob id | SE2 | [x] `AuditTrailGapsTest#uploadsAndIngestionJobCreationAreAudited` |
| A6 | `INGESTION_REMAPPED` | `POST /api/ingestion/jobs/{id}/remap`, `PUT …/mapping` | IngestionJob id | SE2 | [!] BUG-10: remap PASS (`AuditTrailGapsTest#jobReadsEditsExportsAndRemapsAreAudited`); `PUT …/mapping` unaudited: `IngestionEditAuditQaTest#aManualMappingChangeIsAuditedOnce` (@Disabled) |
| A7 | `INGESTION_ROWS_EDITED` | `PUT /api/ingestion/jobs/{id}/rows`, chat-edit | IngestionJob id; values masked | SE2 | [!] BUG-11: `PUT …/rows` PASS (`AuditTrailGapsTest#jobReadsEditsExportsAndRemapsAreAudited`); chat-edit unaudited: `IngestionEditAuditQaTest#aChatEditIsAuditedOnceWithoutCellValues` (@Disabled) |
| A8 | `DOC_GENERATED` | `POST /api/doccreation/instances/{id}/generate` | DocInstance id | SE2 | [x] `DocInstanceControllerAuditTest#generateIsAuditedOnce` (controller level) |
| A9 | `DOC_DOWNLOADED` | doc instance download (docx/pdf) | DocInstance id | SE2 | [x] `DocInstanceControllerAuditTest#downloadIsAuditedOnceWithItsFormat` |
| A10 | `EXPORT_DOWNLOADED` | `GET /api/ingestion/jobs/{id}/export/file` | IngestionJob id | SE2 | [x] `AuditTrailGapsTest#jobReadsEditsExportsAndRemapsAreAudited` |
| A11 | `TOKEN_REFRESHED` | `POST /api/auth/refresh` | AppUser id (actor = the user) | SE2 | [x] `AuditTrailGapsTest#aSuccessfulRefreshIsAudited` |
| A12 | `ACCESS_DENIED` (use case) | a use-case `AccessDeniedException` (e.g. READ_ONLY edits) | the refused entity | SE2 | [x] `AuditTrailGapsTest#aUseCaseAccessDeniedIsAuditedButAPermissionDenialStaysAuthorizationDenied` |
| A13 | `ACCESS_DENIED` (owner-scoped 404 probe) | another user's job id → 404 | IngestionJob id; outcome DENIED | SE2 | [x] `AuditTrailGapsTest#ownerScopedNotFoundProbesAreAggregatedAccessDenials` |
| A14 | `ACCESS_DENIED` (bearer) | invalid signature, expired, revoked token | actor = IP when no user | SE2 | [x] invalid signature: `AuditTrailGapsTest#anInvalidBearerTokenIsAuditedPerAddressAndAMissingOneIsNot` (expired/revoked share the INVALID_TOKEN path, not separately driven) |
| A15 | `ACCESS_DENIED` aggregation | 50 denied calls in one minute from one actor/IP → **one** row with `count = 50`; a new minute → a new row | — | SE2 | [x] as built: one opening row (`count 1`) + one flushed row with the rest (sum = 50): `AccessDeniedBurstAlertTest#fiftyProbesInOneMinuteAreAggregatedIntoAtMostTwoRowsWhoseCountsAddUp`, `AuditThrottleTest` (OBS-01) |
| A16 | `SENSITIVE_DATA_VIEWED` | ingestion job data, extraction records, user list, DSR search: twice within the hour → one row; another entity → another row; after the hour → a new row (clock-controlled test) | the viewed entity | SE2 | [x] `AuditTrailGapsTest#jobReadsEditsExportsAndRemapsAreAudited`, `#theUserListAndDsrSearchAreReadAuditedOncePerHour`, `ExtractionJobControllerAuditTest#readingRecordsIsAuditedForTheJob`, clock: `JpaAuditRecorderThrottleTest#sensitiveReadsAreDedupedPerActorAndEntityPerHour`, `AuditThrottleTest#dedupeWritesTheFirstReadPerWindowAndReleaseRetries` |
| A17 | `HOUSEKEEPING_COMPLETED` | a housekeeping run (see §4) | counts per table in `NewValues` | SE1 | [x] `HousekeepingJobTest#theJobSweepsEveryBankAndAuditsEach`, `HousekeepingRlsPgIT#underRlsEachBanksOldRowsGoAndTheOtherBanksStay` |
| A18 | `AI_RESULT_CACHE_HIT` is **not** an audit action | re-upload identical content → no audit row for the hit; the metric increments | — | SE4 | [x] `IngestionParseStepImplTest#theSameDocumentParsedAgainIsServedFromTheResultCacheWithoutAnAiCall` (metric), `AiResultCacheRlsPgIT` (no audit row) |
| A19 | `docs/security/audit-events.md` lists every action above, and `GET /api/admin/audit/actions` returns the same catalogue | doc ↔ endpoint parity test | SE2 | [x] `AuditActionCatalogueDocTest#theDocListsExactlyTheCatalogue`, `AuditLogApiTest#theActionCatalogueGroupsActionsForTheFilterPicker` |
| A20 | Alert rule `access-denied-burst` (WINDOWED, MEDIUM, group by ACTOR/IP, `alerts.accessDeniedBurst.threshold|window`) fires once at threshold and not below | alert sweep `runOnce()` | SE2 | [x] `AccessDeniedBurstAlertTest#firesOnceAtTheThresholdForOneUserAndNotBelow`, `#anonymousRefusalsGroupByAddressAndOldRowsAreOutsideTheWindow`, `AuditTrailGapsTest#theAccessDeniedBurstRuleIsInTheCatalogue` (OBS-01: the threshold counts minutes) |

### 1.2 Audit viewer — RLS-isolated per bank

| # | Check | Owner | Status |
|---|---|---|---|
| V1 | `GET /api/admin/audit` as bank A's ADMIN returns only bank A rows; bank B's rows are invisible even with `entityId`/`actor` filters naming them (PG IT under the `modocument_app` role, RLS on) | SE2 | [x] `AuditLogRlsPgIT#eachBankAdminSeesOnlyItsOwnBanksEvents`, `#underABanksTenantRlsHidesAnotherBanksRowsEvenWhenTheQueryNamesThatBank`, `AuditLogApiTest#anotherBanksIdsCursorsAndFiltersFindNothing` |
| V2 | `GET /api/admin/audit/export` same isolation; CSV ≤ 50 000 rows (ExportGuard); audited `AUDIT_LOG_EXPORTED` once per export | SE2 | [x] `AuditLogApiTest#valuesAreMaskedForReadOnlyReadersAndInEveryExport` (as built §10: above 50 000 the newest 50 000 + `X-Export-Truncated`) |
| V3 | Permission: ADMIN, APPROVER, PLATFORM_ADMIN → 200; READ_WRITE, READ_ONLY → 403 (and the 403 is itself an `ACCESS_DENIED`) | SE2 | [x] `AuditLogApiTest#auditReadIsForAdminsApproversAndPlatformAdminsOnly`; the 403 is `AUTHORIZATION_DENIED` (method security), not `ACCESS_DENIED` (`AuditTrailGapsTest#aUseCaseAccessDenied…`) |
| V4 | `GET /api/platform/audit` returns the platform bank's own events only; `/api/platform/audit/counts` returns counts per bank and **no** row content of other banks | SE2 | [x] `AuditLogApiTest#thePlatformSeesItsOwnEventsAndOtherBanksAsCountsOnly`, `AuditLogRlsPgIT#thePlatformReadsCountsAcrossBanksButACustomerAdminCannot` |
| V5 | Keyset paging: `(OccurredAt, AuditEventId)` newest first; `nextCursor` walks the full set with no duplicates or gaps when rows share a timestamp; `limit` bounds 1–200 (0 and 201 → 400) | SE2 | [x] `AuditLogApiTest#keysetPagingWalksEveryRowOnceNewestFirst`, `#filtersNarrowByOutcomeEntityActorAndTime` (limit 0/201 → 400) |
| V6 | Filters: actor (email and id), repeated `action`, `entityType`/`entityId`, `outcome`, `from`/`to` (ISO-8601; `from > to` → 400) | SE2 | [x] `AuditLogApiTest#filtersNarrowByOutcomeEntityActorAndTime` |
| V7 | Values pass through the PII masking for the caller's role (NIC/account masked for APPROVER) | SE2 | [x] `AuditLogApiTest#valuesAreMaskedForReadOnlyReadersAndInEveryExport` |
| V8 | UI `/admin/audit`: filters, "Load more", CSV export, entity link-out, old/new diff; keyboard and screen-reader checks as in the Wave-4 a11y pass; platform variant | SE3 | [x] Vitest `AuditLogPage.test.jsx` (SE3); E2E OPT-V9 drives filters on the page. Screen-reader pass not repeated by QA |
| V9 | E2E: an admin of bank A opens the audit page and cannot see bank B's events (two banks seeded via the platform console) | QA | [x] E2E `optimization.spec.js` OPT-V9 |

### 1.3 Viewing the audit log is itself audited, deduplicated

| # | Check | Owner | Status |
|---|---|---|---|
| R1 | First page view → one `AUDIT_LOG_VIEWED` row (actor, bank, filters in `NewValues`) | SE2 | [x] `AuditViewAuditQaTest#theFirstViewIsOneRowWithTheFiltersAndRepeatsByTheSameActorAreNot`, `AuditLogApiTest#viewingIsAuditedOncePerActorWithinTheWindow` |
| R2 | More page views / "Load more" by the same actor within 15 min → no new row; another actor → its own row; after 15 min → a new row | SE2 | [x] `AuditViewAuditQaTest#theFirstViewIsOneRowWithTheFiltersAndRepeatsByTheSameActorAreNot`; 15-min expiry: `AuditThrottleTest#dedupeWritesTheFirstReadPerWindowAndReleaseRetries` |
| R3 | No recursion: reading audit rows never emits `SENSITIVE_DATA_VIEWED` for the viewed rows | SE2 | [x] `AuditViewAuditQaTest#readingAuditRowsNeverAuditsReadsOfTheViewedRows` |
| R4 | `audit.readAuditEnabled=false` (platform-controlled) → no `AUDIT_LOG_VIEWED` rows; the bank admin cannot change the setting | SE1, SE2 | [x] as built the setting gates `SENSITIVE_DATA_VIEWED` only; `AUDIT_LOG_VIEWED` is always written (audit-events.md, OBS-02). Bank cannot switch it: `AuditViewAuditQaTest#theBankAdminCannotSwitchTheReadAuditSetting`; gate: `JpaAuditRecorderThrottleTest#theReadAuditFollowsTheBankSettingAndAnAbsentKeyMeansOn` |

## 2. API hygiene and security (SE2)

| # | Check | How | Owner | Status |
|---|---|---|---|---|
| S1 | SSRF: the old `RestApiConnector` refuses a redirect to a loopback / private / metadata address (`169.254.169.254`), and a redirect chain whose 2nd hop is disallowed; an allowed redirect is followed only with an egress re-check per hop (`EgressGuard`, `OutboundHostPolicy`) | loopback stub answering 302 → `Location: http://127.0.0.1:…` / `http://169.254.169.254/` with `modocument.security.ssrf.enabled=true` | SE2 | [x] `RestApiConnectorRedirectTest` (4 tests); crawler: `HttpWebFetcherRedirectQaTest` (4, QA) |
| S2 | `/api/aifiles/send` is gone: 404 for every role (not 401/403/405), absent from `/v3/api-docs` and `openapi-routes.txt`, removed from `LlmQuotaInterceptor` and the `WebConfig` legacy-path note | MockMvc + `OpenApiContractTest` | SE2 | [x] `ApiHygieneQaTest#theRemovedRoutesAre404ForEveryRoleAndMethod`, `DeprecatedEndpointsTest#theSpecMarksExactlyTheDocumentedRoutesDeprecated` |
| S3 | `/api/transform/transform` removed only if no caller remains (frontend grep, integration docs); otherwise deprecated like S4 | grep + MockMvc | SE2 | [x] removed: `ApiHygieneQaTest#theRemovedRoutesAre404ForEveryRoleAndMethod`, absent from the spec (`DeprecatedEndpointsTest`) |
| S4 | Deprecated routes (`/ImportDetails/*`, `/ProcessStep/*`, TemplateDataTypes CRUD, ImportHistory CRUD, `/api/automapper/generate`, `/api/fileupload/upload`, `/api/extraction/metrics`) answer as before **plus** `Deprecation: true`, `Sunset: Thu, 01 Apr 2027 00:00:00 GMT`, `Link: </docs/api/versioning-policy.md>; rel="deprecation"`; `deprecated: true` in OpenAPI; listed in `docs/api/versioning-policy.md`. Non-deprecated routes carry none of the three headers | parameterised MockMvc over the route list | SE2 | [x] `ApiHygieneQaTest#everyDeprecatedRouteCarriesTheThreeHeaders` (all 20), `#currentRoutesCarryNoDeprecationHeaders`, `DeprecatedEndpointsTest` |
| S5 | `DatabaseExportService` refuses a raw JDBC URL (vendor + host form only), applies connect/socket timeouts; migration note present | unit + MockMvc | SE2 | [x] `DatabaseExportServiceTest#aRawJdbcUrlIsRefusedWithTheMigrationHint`, `#buildsTheUrlFromStructuredFieldsWithTimeoutsAndExports` |

## 3. File store (SE5, contracts §7)

| # | Check | Owner | Status |
|---|---|---|---|
| F1 | Key traversal refused by every `FileStore` implementation: `../x`, `a/../../x`, absolute `/etc/passwd`, `C:\x`, URL-encoded `%2e%2e/`, a NUL byte, an empty key → `IllegalArgumentException`, nothing read or written outside the root/prefix (`LocalFileStore` unit test + the same contract test against `S3FileStore`/MinIO) | SE5 | [x] local: `LocalFileStoreTest` (`FileStoreContract#unsafeKeysAreRejectedByEveryOperation`), `FileStoreKeysTest#rejectsAbsoluteTraversalAndBackslashKeys`, `FileStoreTraversalQaTest` (NUL, drive, `%2e%2e`); `S3FileStoreTest#unsafeKeysNeverReachS3`. MinIO (`S3FileStoreS3IT`) needs Docker |
| F2 | The S3 key prefix can't cross banks: bank A's caller can't `get`/`delete`/`list` a `bank/{B}/…` key, through any API (upload id, export download, doc asset) | SE5 | [ ] keys carry no bank prefix (layout unchanged, contracts §7); cross-bank access is refused above the store (`UploadOwnershipEnforcementTest`, `CrossUserAccessTest`). Not re-tested on S3 (Docker) |
| F3 | Every former `Path` caller (UploadStorage, export copies, doc assets, RetentionPurger, IngestionJobDeletionUseCase, DataSubjectUseCase, IntegrationApiUseCase, PayloadBuilder, IngestionExportController, DocInstanceUseCase) works on `local` with the same layout (existing H2 suites green, unchanged) | SE5 | [x] H2 suite green, unchanged caller tests |
| F4 | Retention and DSR erase delete the stored object (local and S3), and a failed delete is retried, not lost (`AfterCommitFileDeleter`) | SE1, SE5 | [!] BUG-12: the local purge deletes the files (`RetentionPurgeGateC5Test#thirtyOneDayOldDataIsPurgedAndTwentyNineDayOldSurvives`), but a failed delete is logged and dropped, never retried: `AfterCommitFileDeleterQaTest#aFailedDeleteIsRetriedNotLost` (@Disabled) |

## 4. Housekeeping (SE1)

Seed rows on both sides of each window (clock-controlled), run the job once,
assert what is left. PG IT under RLS, plus H2.

| # | Table | Deleted | Kept | Owner | Status |
|---|---|---|---|---|---|
| H1 | RefreshToken | expired or revoked > 30 days ago | expired/revoked ≤ 30 days ago; active | SE1 | [x] `HousekeepingJobTest#refreshTokensExpiredOrRevokedMoreThanThirtyDaysAgoGo` |
| H2 | RevokedToken | past its token expiry | not yet expired | SE1 | [x] `HousekeepingJobTest#revokedTokensGoOncePastTheirTokensExpiry`, `HousekeepingRlsPgIT#theRevokedTokenSweepRunsAsSystem` |
| H3 | IntegrationRun + IntegrationRunEvent | older than `integration.historyRetention` (default 90 d; per-bank setting honoured, range 7–400 d) | younger; any **active** run of any age | SE1 | [x] `HousekeepingJobTest#finishedRunsTerminalDeliveriesAndEventsOlderThanTheWindowGo`; per-bank value + 7–400 d clamp: `HousekeepingRetentionSettingQaTest` (QA) |
| H4 | OutboundDelivery | terminal and older than the window | PENDING / in-flight of any age | SE1 | [x] `HousekeepingJobTest#finishedRunsTerminalDeliveriesAndEventsOlderThanTheWindowGo` |
| H5 | IntegrationEvent | older than the window | anything an undelivered delivery references | SE1 | [x] `HousekeepingJobTest#finishedRunsTerminalDeliveriesAndEventsOlderThanTheWindowGo` |
| H6 | IdempotencyKey | one batched sweep of expired keys; no per-request delete left | unexpired | SE1 | [x] `HousekeepingJobTest#expiredIdempotencyKeysGoInOneSweep`, `#eachRunIsBoundedAndABacklogDrainsOverSeveralRuns` |
| H7 | Every new job is registered in the TenantContext registry and runs per bank; one bank's failure does not stop the others; `HOUSEKEEPING_COMPLETED` once per run | SE1 | [x] `HousekeepingJobTest#theJobSweepsEveryBankAndAuditsEach`, `HousekeepingRlsPgIT` (one bank failing while others run: not driven) |

## 5. Hot read paths (SE4, contracts §5)

| # | Check | Owner | Status |
|---|---|---|---|
| P1 | `GET /api/ingestion/jobs?page=0&size=50&view=summary` → `{items, page, size, total}`; items carry exactly the `IngestionJobSummary` fields and **no** JSON blobs; newest first; `total` = all of the caller's jobs; page 1 continues page 0 with no overlap; `size` > 200 → 400 (or capped, per contract); an empty page past the end | SE4 | [x] `ReadPathQueryCountTest#theJobSummaryCostsTheSameStatementsForTwoJobsAsForTwelve`, `IngestionSummaryPagingQaTest` (walk, no overlap, clamp 200, past end) |
| P2 | Summary is owner/bank scoped exactly like the full list (another user's jobs never appear; PG RLS IT) | SE4 | [x] H2: `IngestionSummaryPagingQaTest#pagesWalkTheCallersJobsNewestFirstWithNoOverlapOrGap`; no dedicated PG RLS IT for the summary query (same owner predicate as the full list) |
| P3 | Without `view=summary` the old array shape is unchanged but capped at the 200 newest jobs | SE4 | [x] `ReadPathQueryCountTest#theLegacyUnpagedListIsCappedAtTheTwoHundredNewestJobs` |
| P4 | `validationErrorCount` matches the validation JSON for VALIDATED jobs, and is null/absent before validation | SE4 | [x] `ReadPathQueryCountTest#theJobSummaryCostsTheSameStatementsForTwoJobsAsForTwelve` |
| P5 | `GET /api/dashboard/summary` → `templates`, `ingestion.{total, byStatus, recent ×8}`, `extraction.{total, running, recent ×5}`, `imports.{total, recent ×5}`; counts equal the full lists'; dashboard permission only | SE4 | [x] `ReadPathQueryCountTest#theDashboardSummaryCostsTheSameStatementsWhateverTheHistory`; E2E OPT-X3 |
| P6 | Paged `/api/admin/users`, `/api/admin/changes`, `/api/admin/integrations/runs` (`size` ≤ 200, same envelope) | SE3, SE4 | [x] users: `UserAdminIntegrationTest#theUserListPagesWithTheSharedEnvelopeAndStaysInTheCallersBank`; changes: `ReadPathQueryCountTest#thePendingChangeQueuePagesInTheDatabaseAndLooksUsersUpOnce`; runs: Vitest `IntegrationRunsPage.test.jsx` only |
| P7 | AI result cache: identical text + template version + mode + model + prompt version → second run makes **zero** model calls (fake-LLM `/__stats`), same output; any key part changed → a call; rows bank-scoped (RLS) and purged with retention | SE4 | [x] `IngestionParseStepImplTest#theSameDocumentParsedAgain…`, `#theResultCacheIsPerBankPerTemplateVersionAndCanBeSwitchedOff`, `AiResultCacheRlsPgIT` (QA), E2E OPT-P7 |
| P8 | Row-batch columnar output `{"columns","rows"}` parsed, and the old per-row shape still parsed (golden corpus) | SE4 | [x] `RowBatchOutputTokensTest#theCompactRowShapeSavesOutputTokensOnTheGoldenCorpus`, `LlmStructuredJsonParserTest` (SE4) |
| P9 | Alert-sweep query uses the `(OccurredAt)` / `(BankId, Action, OccurredAt)` index (`EXPLAIN` in a PG IT) | SE1 | [x] `PerfIndexPlanPgIT#theAlertSweepWindowReadUsesTheOccurredAtIndex` |

## 6. Frontend load (SE3)

| # | Check | Owner | Status |
|---|---|---|---|
| L1 | Lazy-route stale-chunk reload: after a redeploy the old `index.html` asks for a chunk that 404s → `vite:preloadError` reloads the page **once** (no reload loop: a second failure within the guard window shows the error boundary). Vitest for the handler; E2E by serving a build, deleting one lazy chunk, and navigating to its route | SE3 | [!] BUG-09: reload once PASS (Vitest `preloadRecovery.test.js`; E2E OPT-L1, OPT-L1b); no error boundary, a still-missing chunk leaves a blank page (E2E OPT-L1c fixme) |
| L2 | Login and Dashboard stay eager; every other route is a lazy chunk with a `<Suspense>` fallback inside the Layout | SE3 | [x] `App.test.jsx` (SE3); build: 55 chunks, login + dashboard in `index` |
| L3 | `qrcode` is not loaded on a plain login (only on MFA enrolment / profile) — `perf/frontend-load.mjs` request list | SE3 | [x] `perf/results/wave2/frontend.json`: cold login loads `index` + `vendor` only |
| L4 | `usePolling`: no overlapping calls, paused while `document.hidden`, resumes on visibility; same for ExtractionJobDetailPage and AnnouncementBanner | SE3 | [x] `usePolling.test.jsx` (SE3) |
| L5 | Initial JS ≤ 300 kB raw (≈ 90 kB gzip) on cold login (`perf/compare.mjs`) | SE3 | [x] 285.3 KiB (292 kB) raw / 90.8 KiB gzip (`perf/WAVE2.md`) |

## 7. gzip and cache headers (SE3; SE5 reviews; CloudFront SE5)

Against the nginx image (`docker-compose.e2e.yml`) or `nginx -c frontend/nginx.conf` locally:

| # | Request | Expected | Owner | Status |
|---|---|---|---|---|
| C1 | `GET /assets/index-*.js` with `Accept-Encoding: gzip` | `Content-Encoding: gzip`, `Cache-Control: public, max-age=31536000, immutable` | SE3 | [x] `perf/nginx-check.sh` (`results/wave2-run2/nginx.json`) |
| C2 | `GET /assets/*.css` | gzip + immutable as C1 | SE3 | [x] `perf/nginx-check.sh` |
| C3 | `GET /assets/does-not-exist.js` | **404** (`try_files $uri =404`), never `index.html` with 200 (that is what breaks L1) | SE3 | [x] `perf/nginx-check.sh` |
| C4 | `GET /`, `GET /index.html`, a deep link `/admin/audit` | `Cache-Control: no-cache`; SPA fallback still works for deep links | SE3 | [x] `perf/nginx-check.sh` |
| C5 | API JSON through nginx (`/api/…` with `Accept-Encoding: gzip`) | gzip for `application/json` above the min length; no caching headers added to API responses | SE3 | [x] `perf/nginx-check.sh` (live backend behind nginx) |
| C6 | Security headers unchanged on every location (`scripts/check-nginx-headers.sh` green) — `add_header` in the new `location` blocks must not drop the inherited ones | SE3 | [x] `perf/nginx-check.sh`, `scripts/check-nginx-headers.sh` |
| C7 | CloudFront `/assets/*` behaviour: CachingOptimized + compression (terraform plan) | SE5 | [ ] needs AWS (terraform plan) |
| C8 | favicon served (no 404 in the cold-load request list) | SE3 | [x] `perf/nginx-check.sh`; no 404 in the cold-load request list |

## 8. Runtime, footprint, CI (SE1, SE5)

| # | Check | Owner | Status |
|---|---|---|---|
| D1 | Scheduler pool 4 + dedicated integration-dispatch executor: a hanging bank endpoint does not delay the settings poll or the alert sweep (test with a stub that sleeps) | SE1 | [x] `IntegrationSchedulerExecutorTest#theScheduleHandsTheTickOffAndNeverOverlapsIt` (SE1) |
| D2 | Hikari max 20 (configurable), leak-detection threshold set; Hibernate batch size 50 with ordered inserts/updates | SE1 | [ ] owner (SE1): configuration, not re-verified by QA |
| D3 | One AppUsers read per authenticated request (SQL statement count in a MockMvc test) | SE1 | [x] `RequestPrincipalStateQueryCountTest#anAuthorizedRequestReadsAppUsersOnce` (SE1) |
| D4 | Mongo/Kafka only in the `scale` profile; scale mode still boots | SE5 | [x] `ScaleModeEnvironmentGuardTest` (SE5); Kafka/Mongo jars gone from the top 10 |
| D5 | Playwright removed from the jar; `PlaywrightWebFetcher` gone; JS rendering documented as unavailable | SE5 | [x] jar: no `driver-bundle` (`perf/results/wave2/footprint.json`) |
| D6 | Tesseract in the runtime image: `OcrService` reports enabled; an image-only PDF yields text | SE5 | [x] `OcrServiceTest` (SE5); image-only PDF in the image needs Docker |
| D7 | Jar/image ≥ 150 MB smaller; startup faster with CDS (`perf/compare.mjs`, image measured with `docker image inspect` in CI) | SE5 | [x] jar 352.1 → 149.9 MiB (−202 MiB); image + CDS startup need CI/Docker |
| D8 | CI `backend-postgres` runs failsafe only; JaCoCo behind the `coverage` profile and still reported in CI | SE5 | [ ] owner (SE5): CI configuration, not runnable here |

## 9. Regression

| # | Check | Owner | Status |
|---|---|---|---|
| X1 | Full E2E suite on `run-local.sh` (all phase-2 specs live) | QA | [x] 80 tests: see `optimization-signoff.md` §3 |
| X2 | `connector-core`: the existing connector tests on server and agent pass unchanged (pagination, SFTP move timing, size caps) | SE4, SE5 | [x] agent module 41/41 (`mvn -B -f agent/pom.xml verify`), server connector tests in G1 |
| X3 | Dashboard renders the same numbers from `/api/dashboard/summary` as from the four lists on the baseline data set (`perf/seed.mjs` data) | SE3, QA | [x] E2E OPT-X3 |
| X4 | Wave-2 perf run and before/after table (`perf/README.md` → "Wave 2") attached to the sign-off | QA | [x] `perf/WAVE2.md` |
| X5 | Sign-off document `docs/qa/optimization-signoff.md` | QA | [x] `docs/qa/optimization-signoff.md` |
