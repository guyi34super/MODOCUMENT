# Phase 2 test plan

Covers the admin dashboard, editable settings, the platform owner, code
extraction fallback, software updates and the bank integration hub.

Owner: QA. Approver: the Supervisor. The plan follows the approved phase-2
plan ("Verification", "Top risks", §6) and the Wave-0 contracts in
[`contracts.md`](contracts.md). Every acceptance criterion (AC) has an ID,
a test level, a planned test (name and location) and an owner. A feature is
done when its ACs are green at their stated level.

## 1. Scope

In scope:
- the six phase-2 features;
- the regressions they can cause in existing behaviour: sign-in and MFA,
  maker/checker, RLS tenant isolation, ingestion, exports and masking;
- the release pipeline, as far as it can be checked without AWS.

Out of scope until AWS and the first bank exist (the "implement later" list):
- real staging and production deploys and the rollback rehearsal;
- ALB mTLS trust store, VPN and PrivateLink;
- agents installed on a bank's premises.

## 2. Strategy: the test pyramid

| Level | Tool and location | What it proves | Runs |
|---|---|---|---|
| **UNIT** | JUnit 5, `src/test/java/**/*Test.java` (no Spring context) | Pure logic: resolvers, clamps, classifiers, confidence scoring, SafeRegex, TOTP, HMAC, identifier validation, policy tests (catalogue safety, migration policy, ranges) | `mvn -B verify` |
| **H2-IT** | Spring Boot test + MockMvc on H2, `src/test/java/**/*Test.java` (`@SpringBootTest`) | Controllers, permissions, use cases, appliers, maker/checker, audit rows, error codes, parity (no overrides = old behaviour) | `mvn -B verify` |
| **PG-IT** | `src/test/java/com/modocument/pgit/*PgIT.java`, profile `-Ppostgres-it` (scratch PG16 locally via `MODOCUMENT_IT_*`, Testcontainers in CI) | Things H2 cannot show: RLS policies and `WITH CHECK` under `modocument_app`, triggers, partial unique indexes, Flyway end to end, cross-task propagation, run locks | `mvn -B verify -Ppostgres-it` |
| **CONN-IT** | `src/test/java/com/modocument/connectorsit/*ConnIT.java`, profile `-Pconnectors-it` (Testcontainers: Oracle XE, SQL Server, MariaDB, PostgreSQL; embedded MINA SFTP; JDK HTTP stub) | Connector contract per vendor and protocol: read, write, test, timeouts, identifier safety | CI only (no Docker in the sandbox); PG + SFTP + HTTP parts locally |
| **VITEST** | `frontend/src/**/*.test.jsx` | Components and pages: rendering per role, badges, forms, dialogs (focus trap), API module calls | `npm test` |
| **E2E** | Playwright, `e2e/tests/**/*.spec.js` ([README](../../e2e/README.md)) | User journeys through the real edge path: SPA, proxy, backend, PostgreSQL (RLS role), and the fake LLM and bank simulator | `e2e/run-local.sh` / `docker-compose.e2e.yml` |
| **SEC** | Tagged inside the levels above, plus ZAP baseline and gitleaks/semgrep | SSRF, SQL identifiers, cross-bank 404, replay and signatures, secret leakage, header contract | per level, plus `security.yml` and `zap-baseline.yml` |
| **CI/MAN** | actionlint, `terraform fmt/validate`, script checks; manual review | Release pipeline shape, IaC, rollback (later) | CI and release review |

Rules:
- **Lowest sufficient level.** A rule that can be shown in UNIT is not
  re-proven in E2E. E2E proves the wiring and the journeys, plus anything
  that only shows up end to end: RLS role, proxy, browser, LLM transport.
- **Production shape in E2E.** By default the backend runs as the RLS role
  (`modocument_app`, `E2E_DB_APP_ROLE=rls`) with the production profile,
  per-run secrets and the real auth and MFA flow. The only relaxations are
  the per-IP auth and MFA throttles, which are configuration, not code
  (README).
- **AI is never real in tests.** The fake LLM is deterministic and
  scriptable: `ok`, `error500`, `error429`, `timeout`, `badjson` and
  `badenvelope`, with call counters.
- **A bug gets a failing test** at the lowest level that reproduces it.
  E2E-only bugs go in `e2e/tests/04-known-bugs.spec.js` (`@bug`). A known-bug
  test is never marked fixme.
- **Spec titles start with the AC ID.** Phase-2 E2E specs exist from Wave 1
  as `test.fixme`, and the owner switches them on in the PR that ships the
  feature.

## 3. Environments

| Env | How | Database | AI | Notes |
|---|---|---|---|---|
| Dev sandbox | `e2e/run-local.sh` | scratch PG16 `127.0.0.1:55432`, fresh DB and owner role per run | fake LLM (Node) | `vite preview` proxy with the nginx path list; no Docker |
| CI E2E | `docker-compose.e2e.yml` (job definition in the e2e README, owned by SE5) | postgres:16 on tmpfs plus `docker/postgres-init` | fake LLM container | real frontend nginx image and real app image |
| CI unit/IT | `ci.yml` (`mvn verify`, `-Ppostgres-it`, Vitest, build) | H2 / Testcontainers PG | none | already exists |
| CI connectors | `-Pconnectors-it` (SE4 adds the profile) | Testcontainers Oracle XE, SQL Server, MariaDB | none | licence check of the drivers in the SBOM |
| Bank simulator (Wave 3) | `e2e/bank-simulator/` ([README](../../e2e/bank-simulator/README.md)): `E2E_BANK_SIM=1 e2e/run-local.sh` (bank DB + REST stub) or `docker-compose.bank-sim.yml` (adds SFTP) | bank PG: view `integration.customers`, table `integration.staging_results`, least-privilege reader/writer roles; REST stub (OAuth2 CC, paging, capture with idempotency, signed-webhook receiver); SFTP (`atmoz/sftp`) with a pinned TEST-ONLY host key | none | the "bank" side of the integration loop; security cases in [`docs/qa/integration-security-tests.md`](../qa/integration-security-tests.md) |
| Staging (later) | release.yml | RDS | configured endpoint | smoke `/api/probe/synthetic` plus the E2E `@smoke` subset |

Test data: one database per run, run-scoped names (`uniqueName()`) and
generated credentials. Fixtures live in `e2e/fixtures/`. The golden
extraction corpus lives in `src/test/resources/extraction-corpus/` (SE4
builds it, QA curates it; see AC-EXT-08).

## 4. Entry and exit criteria per wave

| Wave | Entry | Exit (gate the Supervisor checks) |
|---|---|---|
| **1 Foundations** | Wave-0 contracts merged (`contracts.md`, enums, route skeleton) | The standing gates are green: `mvn -B verify`, `-Ppostgres-it`, Vitest and build, gitleaks and semgrep. E2E `@smoke` is green with `E2E_DB_APP_ROLE=rls`; while BUG-01 is open, a Supervisor-signed waiver allows `owner` mode. UNIT ACs of the libraries that landed are green: SET-02/14/15, EXT-07/08/09/10, REL-01/04, X-01. This plan is approved. |
| **2 Integration** | Wave 1 merged; harness on the integration branch | Every SET, PLT, ADM, EXT and REL AC is green at its H2-IT and PG-IT level. The E2E specs for SET-01/03/04/05/06/07, PLT-01/03/05/06/10, ADM-01/03/04/05/07/10, EXT-02/03/04/05/11 and REL-02/03/06/07 are switched on and green. Corpus is ≥ 95 %. No open P1 or P2 bugs. RLS inventory is updated (X-02). |
| **3 Integration hub** | Wave 2 merged; bank simulator in the stack | Every INT AC is green at its level, including CONN-IT for the 4 DB vendors in CI. The E2E integration specs (§5.6) are switched on and green. The security set passes: SSRF, identifiers, idempotency, stale signature, revoke, cross-bank 404 and masking default. |
| **4 Completion** | Wave 3 merged | Full E2E regression is green twice in a row with retries off, including the pull → process → push loop and the agent. ZAP baseline (including the integration API) shows no High. No `test.fixme` is left without a signed waiver. Open bugs are at most P3, each with an owner. `v1.1.0-rc.1` is built. QA sign-off is recorded in this file (§9). |

Severity: **P1** blocks go-live or loses or leaks data. **P2** breaks a
feature with no workaround. **P3** has a workaround. **P4** is cosmetic.

## 5. Acceptance criteria

Level abbreviations are from §2. A test written as `Class#method` lives in
`src/test/java/com/modocument/...`. An E2E test is the spec title prefix
inside the named file. AC IDs are stable: never renumber them, and add new
ones at the end.

### 5.1 Settings: guardrails, maker/checker, propagation (SE1 backend, SE3 UI)

| ID | Acceptance criterion | Level | Planned test |
|---|---|---|---|
| AC-SET-01 | `GET /api/admin/settings` returns every registered setting grouped by tab with the full `SettingView` (value, source BANK/PLATFORM/DEFAULT, min/max, hardMin/hardMax, tier, scope, requiresApproval, requiresRestart, locked, clamped, pendingChangeId, version) | H2-IT, E2E | `api.settings.SettingsControllerTest#catalogueListsEveryGroup`; `e2e/tests/phase2/settings.spec.js` AC-SET-01 |
| AC-SET-02 | Resolution is bank override → platform value → yml default. The result is clamped by hard limits and by the platform `bankMin/bankMax`. An invalid stored value is skipped with a warning and never throws. | UNIT | `infrastructure.settings.SettingsResolverTest` |
| AC-SET-03 | A value outside the hard limits returns 400 `SETTING_OUT_OF_RANGE` and nothing is stored or audited as applied. Examples: password min length 11 (floor 12); retention 31 days (gate C5 ceiling 30); JWT TTL 16. | H2-IT, E2E | `SettingsControllerTest#outOfRangeRejected`; settings.spec AC-SET-03 |
| AC-SET-04 | A locked setting returns 400 `SETTING_LOCKED`; the UI shows the "Locked" badge and a disabled input. Locked settings: MFA for admins, rate limiting, retention on, PII tokenization. | H2-IT, VITEST, E2E | `SettingsControllerTest#lockedRejected`; `components/SettingField.test.jsx`; settings.spec AC-SET-04 |
| AC-SET-05 | An OPERATIONAL change applies immediately (200, `applied[]`, new version) and is audited `SETTING_UPDATED` with old and new values | H2-IT, E2E | `SettingsUseCaseTest#operationalAppliesImmediately`; settings.spec AC-SET-05 |
| AC-SET-06 | A SECURITY change returns 202 with a `SETTING_UPDATE` pending change. The effective value is unchanged until approval, and the view shows `pendingChangeId`. A second change to the same key returns 409 `SETTING_CHANGE_PENDING`. | H2-IT, E2E | `SettingsUseCaseTest#securityGoesToApproval`; settings.spec AC-SET-06 |
| AC-SET-07 | The maker approving their own change gets 409 and a `SELF_APPROVAL_BLOCKED` audit row. A different admin's approval applies it, audits `SECURITY_SETTING_UPDATED` and raises `security-setting-changed` (MEDIUM). | H2-IT, E2E | `SettingUpdateApplierTest#selfApprovalBlockedOtherAdminApplies`; settings.spec AC-SET-07 |
| AC-SET-08 | A stale `expectedVersion` returns 409 `SETTING_VERSION_CONFLICT`. At approval, the applier re-validates against the current limits and version; a conflict marks the change FAILED with 409. | H2-IT | `SettingUpdateApplierTest#revalidatesAtApply` |
| AC-SET-09 | Propagation: a write on instance A is visible on instance B within 10 s (5 s `ConfigRevision` poll); the writer's own instance sees it immediately; a lost poll heals on the next tick | PG-IT | `pgit.SettingsPropagationPgIT` (two contexts, one DB) |
| AC-SET-10 | Parity: with no overrides, every existing test passes unchanged and each consumer reads its yml default | H2-IT, CI | full `mvn verify`; `SettingsParityTest` |
| AC-SET-11 | Consumers honour the bank value at runtime. Each of these is checked with an override: password min length, lockout attempts and base/cap, JWT TTL, idle timeout, upload/ingestion/export buckets, LLM quota, export caps, retention days, alert thresholds, parsing/mapping properties. | H2-IT, E2E | `SettingsConsumersTest` (one method per consumer); settings.spec AC-SET-07 (password length end to end) |
| AC-SET-12 | Isolation: bank A cannot read or write bank B's rows or the platform rows (RLS `WITH CHECK`). A bank admin writing a PLATFORM-scope key gets 409 `USE_PLATFORM_SETTINGS`. | PG-IT, H2-IT | `pgit.SettingsRlsPgIT`; `SettingsControllerTest#platformKeyRefusedForBankAdmin` |
| AC-SET-13 | Platform settings: a PLATFORM_ADMIN changes defaults and `bankMin/bankMax` through `PLATFORM_SETTING_UPDATE` (maker/checker). A bank value outside the new range resolves clamped (`clamped=true`, "Clamped by platform"). | H2-IT, E2E | `PlatformSettingsControllerTest`; settings.spec AC-SET-13 |
| AC-SET-14 | No secret-like or URL-like property can be registered; no API ever returns one | UNIT | `SettingsCatalogueSafetyTest` |
| AC-SET-15 | With `MODOCUMENT_SETTINGS_ENFORCE_GUARDRAILS=true`, a yml default outside its limits refuses to boot | H2-IT | `SettingsStartupCheckTest` |
| AC-SET-16 | SettingsPage: one tab per group, a save button per tab, a toast "N applied, M sent for approval", the badges (Requires approval, Pending, Locked, Clamped) and a source label; the same page serves `mode="platform"` | VITEST, E2E | `pages/SettingsPage.test.jsx`; settings.spec AC-SET-16 |
| AC-SET-17 | `BankSetting` is in the RLS inventory; `ConfigRevision` is in `NonTenantTableInventoryPgIT` | PG-IT | `RlsPolicyCoveragePgIT`, `NonTenantTableInventoryPgIT` |

### 5.2 Platform owner: trigger, onboarding, suspension (SE2)

| ID | Acceptance criterion | Level | Planned test |
|---|---|---|---|
| AC-PLT-01 | Exactly one PLATFORM bank (unique partial index). `BootstrapPlatformRunner` creates it and **two** platform admins, both forced through a password change and MFA. | PG-IT, E2E | `pgit.PlatformBankPgIT`; platform.spec AC-PLT-01 |
| AC-PLT-02 | DB trigger: `PLATFORM_ADMIN` outside the platform bank, or any bank role inside it, is rejected on INSERT and UPDATE | PG-IT | `pgit.PlatformRoleTriggerPgIT` |
| AC-PLT-03 | `ADMIN = complementOf(PLATFORM_SET ∪ CLIENT_SCOPES)`. PLATFORM_ADMIN has no domain or customer-data permission. A bank admin gets 403 on `/api/platform/**`; a platform admin gets 403 on `/Templates/**` and `/api/ingestion/**`. | UNIT, H2-IT, E2E | `RolePermissionMatrixTest`; `PlatformAccessTest`; platform.spec AC-PLT-03 |
| AC-PLT-04 | `PLATFORM_*` additionally requires the caller's bank to be the platform bank, even with a forged role claim | H2-IT | `PermissionCheckerPlatformTest` |
| AC-PLT-05 | Onboarding: `POST /api/platform/banks` returns 202 `BANK_CREATE`. Fewer than 2 users, or no ADMIN, gives 400. Self-approval gives 409. A second platform admin's approval creates the bank and users, audits `BANK_ONBOARDED` in both banks, and the new admins are forced through a password change and MFA. | H2-IT, E2E | `BankCreateApplierTest`; platform.spec AC-PLT-05 |
| AC-PLT-06 | Suspension: an approved `BANK_SUSPEND` bumps every user's TokenVersion (live tokens get 401 on the next call) and refuses login and refresh. Reactivation is direct and restores login. | H2-IT, E2E | `BankSuspendApplierTest`; platform.spec AC-PLT-06 |
| AC-PLT-07 | Break-glass `BANK_ADMIN_ADD` (maker/checker) adds an admin to an existing bank | H2-IT | `BankAdminAddApplierTest` |
| AC-PLT-08 | At least two active platform admins always remain: disabling or demoting the second-last is refused | H2-IT | `PlatformAdminFloorTest` |
| AC-PLT-09 | Cross-bank reads happen only in named use cases and return **counts only**. `runAsSystem` call sites are allow-listed. | UNIT, H2-IT | `TenantContextCallSiteRegistryTest`; `PlatformOverviewTest#countsOnly` |
| AC-PLT-10 | A PLATFORM_ADMIN sees only the Platform nav, and `/` redirects to `/platform`. The console has overview, banks (onboarding modal with two admin rows), bank detail with suspend/reactivate, settings, announcements and releases. | VITEST, E2E | `components/Layout.test.jsx`, `pages/Platform*.test.jsx`; platform.spec AC-PLT-10 |
| AC-PLT-11 | `platform-change` (HIGH) alert on onboarding, suspension and platform setting changes | H2-IT | `AlertRuleCatalogueTest#platformChange` |

### 5.3 Admin dashboard, user actions, shared templates (SE3)

| ID | Acceptance criterion | Level | Planned test |
|---|---|---|---|
| AC-ADM-01 | `GET /api/admin/dashboard` (METRICS_READ) KPIs equal the seeded data exactly. The KPIs: users by status and role; MFA enrolment; locked accounts; admins without MFA; active in the last 7 days; pending approvals and releases; ingestion today and over 7 days by status; `parsedBy` AI/CODE/AI_PARTIAL; extraction over 7 days; alerts by severity; last retention run; templates private and shared; pending security settings; version; integration tiles. | H2-IT, E2E | `AdminDashboardUseCaseTest`; admin-dashboard.spec AC-ADM-01 |
| AC-ADM-02 | Only the caller's bank is counted: AppUsers and UserMfa are filtered by `BankId` explicitly. The 30 s cache is per bank and never serves another bank. | PG-IT | `pgit.DashboardIsolationPgIT` |
| AC-ADM-03 | `POST /api/admin/users/{id}/unlock` is direct, clears the lockout and audits `USER_UNLOCKED`. Targeting yourself is refused. | H2-IT, E2E | `UserAdminActionsTest#unlock`; admin-dashboard.spec AC-ADM-03 |
| AC-ADM-04 | Password reset returns 202 `USER_PASSWORD_RESET`; the payload carries the hash only, never the plaintext. On approval: TokenVersion bump, refresh revoked, MustChangePassword set. | H2-IT, E2E | `UserPasswordResetApplierTest`; admin-dashboard.spec AC-ADM-04 |
| AC-ADM-05 | MFA reset returns 202 `USER_MFA_RESET`. On approval MFA is removed, and an ADMIN is forced to re-enrol at the next sign-in. | H2-IT, E2E | `UserMfaResetApplierTest`; admin-dashboard.spec AC-ADM-05 |
| AC-ADM-06 | `PATCH /api/admin/users/{id}` (display name) is direct. Every action on yourself is refused. The `adminSecurity` bucket returns 429 over the limit. | H2-IT | `UserAdminActionsTest` |
| AC-ADM-07 | Visibility defaults to PRIVATE; `PUT /Templates/{id}/Visibility` needs TEMPLATE_PUBLISH. A BANK template is readable by every user of the bank and writable only with TEMPLATE_PUBLISH. Another bank never sees it (404). The matrix covers every `TemplateOwnership` call site. | H2-IT, PG-IT, E2E | `TemplateVisibilityMatrixTest`; `pgit.TemplateVisibilityPgIT`; admin-dashboard.spec AC-ADM-07 |
| AC-ADM-08 | Deleting a shared template that is in active use returns 409 | H2-IT, E2E | `DeleteTemplateUseCaseTest#sharedInUse409`; admin-dashboard.spec AC-ADM-08 |
| AC-ADM-09 | Modal traps focus, closes on Escape and has an accessible name. ConfirmDialog, KpiTile and SettingField render every state. | VITEST | `components/Modal.test.jsx`, `ConfirmDialog.test.jsx`, `KpiTile.test.jsx`, `SettingField.test.jsx` |
| AC-ADM-10 | AdminDashboardPage quick actions work: Add user, Invite (copyable), New shared template, Review approvals. PendingChangesPage shows a label and a diff for **every** new ChangeType (e.g. `password.min-length: 12 → 14`). | VITEST, E2E | `pages/AdminDashboardPage.test.jsx`, `PendingChangesPage.test.jsx`; admin-dashboard.spec AC-ADM-10 |
| AC-ADM-11 | `Layout` sections are filtered by role: READ_ONLY, READ_WRITE, APPROVER, ADMIN and PLATFORM_ADMIN | VITEST | `components/Layout.test.jsx` |

### 5.4 Code extraction fallback (SE4; heuristics SE2)

| ID | Acceptance criterion | Level | Planned test |
|---|---|---|---|
| AC-EXT-01 | Mode = template mode ?? bank default setting ?? `AI_WITH_CODE_FALLBACK` | UNIT | `ExtractionModeResolverTest` |
| AC-EXT-02 | `AI_WITH_CODE_FALLBACK` with the LLM failing (fake LLM `error500`): the job reaches VALIDATED with `parsedBy=CODE` and `parseFallbackReason=ERROR`, `INGESTION_PARSE_FALLBACK` is audited, and the job page shows the "Extracted by code" badge | H2-IT, E2E | `IngestionParseFallbackTest`; extraction-fallback.spec AC-EXT-02 |
| AC-EXT-03 | Each failure maps to its reason: timeout→TIMEOUT, invalid JSON→INVALID_OUTPUT, output scan→OUTPUT_REJECTED, AI off→AI_DISABLED, key unset→NOT_CONFIGURED, quota→QUOTA, breaker open→CIRCUIT_OPEN, tenancy refusal→PROMPT_REFUSED | UNIT, E2E | `AiFailureClassifierTest`; extraction-fallback.spec AC-EXT-03 (`timeout`, `badjson`) |
| AC-EXT-04 | `CODE_ONLY` never calls the LLM (fake-LLM `/__stats` count unchanged); reason `MODE_CODE_ONLY` | H2-IT, E2E | `IngestionParseFallbackTest#codeOnlyNeverCallsAi`; extraction-fallback.spec AC-EXT-04 |
| AC-EXT-05 | `AI_ONLY` fails as today (job FAILED, no fallback) | H2-IT, E2E | `IngestionParseStepImplTest.llmJsonFailureThrowsInsteadOfPoiFallback` (AI_ONLY); extraction-fallback.spec AC-EXT-05 |
| AC-EXT-06 | Still fatal in every mode: no text extracted, missing file | H2-IT | `IngestionParseFallbackTest#fatalCasesStayFatal` |
| AC-EXT-07 | Confidence = 0.4 coverage + 0.3 value shape + 0.2 structure + 0.1 row count. Accepted at or above `extraction.code.minConfidence` (0.75). In CODE_ONLY, 0.40 up to the threshold passes as LOW_CONFIDENCE, with a callout. Below 0.40 the job fails with a reason. | UNIT, E2E | `CodeConfidenceScorerTest`; extraction-fallback.spec AC-EXT-07 |
| AC-EXT-08 | **Golden corpus ≥ 95 % field-level accuracy** (cell exact match after normalisation) over the corpus, with **≥ 90 % per strategy**. Strategies: delimited (quoted newlines, BOM, cp1252, header/footer), native sheet, markdown/row-line, fixed-width PDF text, key:value blocks, per-field regex. Accuracy is reported per strategy and may not regress between waves. | UNIT | `infrastructure.transform.code.CodeExtractionCorpusTest` over `src/test/resources/extraction-corpus/` (≥ 40 documents, each with `expected.json`) |
| AC-EXT-09 | Dates normalise to ISO through the field's DateFormats; numbers are locale-aware (`1.234,56` / `1,234.56`) | UNIT | `ValueNormaliserTest` |
| AC-EXT-10 | SafeRegex enforces a pattern length cap and per-field and per-document deadlines; a catastrophic pattern ends before the deadline | UNIT | `SafeRegexTest` |
| AC-EXT-11 | The circuit breaker opens after 5 transport, 5xx or timeout failures: `AI_CIRCUIT_OPENED` audit and a HIGH alert. While open, parsing falls back with `CIRCUIT_OPEN` and makes no LLM call, and HTTP AI endpoints answer 503. It is HALF_OPEN after 120 s. 4xx and invalid output do not count. | UNIT, E2E | `AiCircuitBreakerTest`; extraction-fallback.spec AC-EXT-11 |
| AC-EXT-12 | When the in-process quota is exhausted, parsing falls back with `QUOTA` and makes no LLM call | UNIT | `LlmQuotaTest` |
| AC-EXT-13 | `AI_PARTIAL`: failed LLM batches are re-parsed by code, and the time budget sends the remaining batches to code | H2-IT, E2E | `IngestionParseFallbackTest#partial`; extraction-fallback.spec AC-EXT-13 (`error500` with `count: 1`) |
| AC-EXT-14 | Provenance (ParsedBy, ParseStrategy, ParseConfidence, ParseFallbackReason) is persisted (V230), returned in the job DTO and counted on the dashboard | H2-IT, PG-IT | `IngestionJobUseCaseTest#provenanceStored`; `FlywayBootPgIT` |
| AC-EXT-15 | `parse-fallback-surge` alert at 20 per hour | H2-IT | `AlertRuleCatalogueTest#fallbackSurge` |
| AC-EXT-16 | TemplateExtractionSection: the mode select shows the inherited default; per-field aliases, patterns and date formats round-trip. The job badge and the low-confidence callout render. | VITEST, E2E | `TemplateExtractionSection.test.jsx`, `IngestionJobPage.test.jsx`; extraction-fallback.spec AC-EXT-16 |
| AC-EXT-17 | InferSchema and Summarize return heuristic results without the LLM when AI is unavailable (SE2) | H2-IT | `InferSchemaUseCaseTest#heuristicFallback`, `SummarizeExtractionUseCaseTest#heuristicFallback` |

### 5.5 Releases, announcements, version (SE5)

| ID | Acceptance criterion | Level | Planned test |
|---|---|---|---|
| AC-REL-01 | `scripts/check-version-sync.sh` passes when pom, `package.json` and the CHANGELOG agree, and fails on a mismatch or a wrong tag | CI | script self-test in `ci.yml` (good and bad fixture) |
| AC-REL-02 | `GET /api/system/version` returns version, commit, buildTime, schemaVersion (the latest Flyway version), deployedAt and environment to every human role; anonymous gets 401 | H2-IT, E2E | `SystemControllerTest`; releases.spec AC-REL-02 |
| AC-REL-03 | `GET /api/system/release-notes` parses Keep-a-Changelog sections | UNIT, E2E | `ChangelogParserTest`; releases.spec AC-REL-03 |
| AC-REL-04 | `MigrationPolicyTest` rejects drop, rename, SET NOT NULL and VALIDATE without a `-- contract:` header; `FlywayVersionRangeTest` enforces `RANGES.md` | UNIT | `MigrationPolicyTest`, `FlywayVersionRangeTest` |
| AC-REL-05 | `DeployedVersionRecorder`: the first task wins (one row per version) and writes `VERSION_DEPLOYED` in every bank, a RELEASE announcement and an INFO alert | PG-IT | `pgit.DeployedVersionRecorderPgIT` (two concurrent recorders) |
| AC-REL-06 | Announcement CRUD requires PLATFORM_ANNOUNCE (bank admin gets 403). `/api/announcements/active` filters by audience bank codes and time window. The body is escaped text, so `<script>` renders inert. | H2-IT, E2E | `AnnouncementControllerTest`; releases.spec AC-REL-06 |
| AC-REL-07 | Banner: MAINTENANCE is visible to everyone and cannot be dismissed; INFO can be dismissed; admins see "What's new in X.Y.Z"; a reload prompt appears when the SPA version differs from the backend | VITEST, E2E | `AnnouncementBanner.test.jsx`; releases.spec AC-REL-07 and 07b |
| AC-REL-08 | `release.yml`: SHA-pinned actions, images built once, SBOM and Trivy, immutable tags, and a production environment with 2 reviewers for `v*` tags only | CI, MAN | actionlint plus release review checklist |
| AC-REL-09 | Terraform: ECS circuit breaker with rollback, `ignore_changes` on desired_count and task_definition, OIDC environment subjects; fmt and validate pass in both envs | CI | `terraform fmt -check` / `validate` |
| AC-REL-10 | A deliberately failing rc on staging rolls back automatically | MAN (later) | runbook rehearsal (no AWS yet) |
| AC-REL-11 | Image dry run: built with build args; `/api/system/version` and the release notes are correct inside the container | CI | docker build + curl step (SE5) |

### 5.6 Bank integration hub (SE1 vault/egress, SE2 clients/API v1, SE3 UI, SE4 connectors/flows, SE5 agent)

| ID | Acceptance criterion | Level | Planned test |
|---|---|---|---|
| AC-INT-01 | Connection CRUD for each type (REST_API, DATABASE, SFTP, WEBHOOK) and mode (DIRECT, AGENT). Create, or a host or credential change, returns 202 `INTEGRATION_CONNECTION_CHANGE`; disabling is immediate. | H2-IT, E2E | `IntegrationConnectionControllerTest`; integration-hub.spec AC-INT-01 |
| AC-INT-02 | Secrets are write-only: never in any response, log or audit row. They are stored with AES-256-GCM using a per-bank HKDF key and a key version; rotation re-encrypts. AGENT mode stores only a `secretRef`. | UNIT, H2-IT, PG-IT | `SecretVaultTest`; `ConnectionSecretLeakTest`; `pgit.SecretAtRestPgIT` |
| AC-INT-03 | "Test connection" for each type reports success or a safe failure message (no secret, no stack trace) | CONN-IT, E2E | `*ConnectorContractConnIT#testConnection`; integration-hub.spec AC-INT-03 |
| AC-INT-04 | REST: GET pull with pagination and a records path; POST/PUT push in batches. Outbound auth: none, basic, bearer, API-key header, OAuth2 CC (token cached until expiry) and mTLS. Redirects are not followed on authenticated calls. | CONN-IT | `RestConnectorConnIT` (JDK HTTP stub) |
| AC-INT-05 | DB (PostgreSQL, Oracle, SQL Server, MariaDB): read-only SELECT on an allow-listed table or view by identifier, with watermark and row cap. INSERT/UPSERT into a staging table runs one transaction per batch (all or nothing). Every call has a timeout. | CONN-IT | `DbConnectorConnIT` (parameterised by vendor, `-Pconnectors-it`) |
| AC-INT-06 | SQL identifier injection (quotes, `;`, comments, unicode confusables, schema hops) is refused before any SQL is sent | UNIT, CONN-IT | `JdbcIdentifiersTest`; `DbConnectorConnIT#identifierInjectionRefused` |
| AC-INT-07 | SFTP refuses an unknown or changed host key. The path is confined to the folder (`../` refused) and there is a size cap. Reads glob, then move the file to `processed/`. Writes upload to a temp name, then rename. | CONN-IT | `SftpConnectorConnIT` (embedded MINA) |
| AC-INT-08 | Outbound webhooks carry `X-MoDocument-Signature: sha256=HMAC(secret, ts + "." + body)` and `X-MoDocument-Timestamp`, and the simulator verifies them. A timestamp older than 5 minutes is rejected by the documented verifier. Retries back off exponentially via the outbox; after N attempts the delivery goes to dead-letter. | UNIT, CONN-IT, E2E | `WebhookSignerTest`; `WebhookConnectorConnIT`; integration-hub.spec AC-INT-08 / AC-INT-13 |
| AC-INT-09 | The flow builder covers direction, connection, template, trigger (MANUAL, SCHEDULE at least every 5 min, EVENT), PayloadKinds, field mapping (auto-suggest), MaskPii (default true) and RowCap. Preview returns the first 20 rows and writes nothing. | H2-IT, VITEST, E2E | `IntegrationFlowControllerTest`; `FlowBuilder.test.jsx`; integration-hub.spec AC-INT-09 |
| AC-INT-10 | Enabling or widening an OUTBOUND flow returns 202 `INTEGRATION_FLOW_ENABLE`. Self-approval gives 409. Disabling is immediate. | H2-IT, E2E | `FlowEnableApplierTest`; integration-hub.spec AC-INT-10 |
| AC-INT-11 | Scheduler: exactly one `IntegrationRun` per (flow, slot) across 2 or more instances; the minimum interval is enforced | PG-IT | `pgit.IntegrationSchedulerLockPgIT` |
| AC-INT-12 | Inbound DB pull: bank rows become a validated `IngestionJob` linked to the run | E2E | integration-hub.spec AC-INT-12 (bank simulator) |
| AC-INT-13 | The domain event writes `OutboundDelivery` in the same transaction: a rollback leaves no delivery and a crash loses no event. The dispatcher sends with the idempotency key (run + batch hash), retries, dead-letters, and supports replay from the UI. | H2-IT, PG-IT, E2E | `OutboundOutboxTest`; `pgit.OutboxAtomicityPgIT`; integration-hub.spec AC-INT-13 |
| AC-INT-14 | Outbound payloads are PII-masked by default (`maskForExport`). Turning masking off is SECURITY tier (maker/checker). ExportGuard caps apply (`EXPORT_RUN` with the flow id). | H2-IT, E2E | `PayloadBuilderMaskingTest`; integration-hub.spec AC-INT-14 |
| AC-INT-15 | An SFTP inbound file goes through the normal ingestion pipeline (AI or code), and the result file is dropped back | E2E | integration-hub.spec AC-INT-15 |
| AC-INT-16 | `INTEGRATION_RUN_*` and `INTEGRATION_DELIVERY_DEAD` are audited; alerts: integration-failures (5/h), dead-letter (HIGH), unusual volume | H2-IT | `IntegrationAuditAlertTest` |
| AC-INT-17 | SSRF: a DIRECT connection to a private IP without an approved `BankEgressRule` is refused. An approved rule (platform `EGRESS_RULE_APPROVE`) allows exactly that host or CIDR and port. DNS is re-resolved at connect (rebinding). Redirects into private ranges are refused. | UNIT, H2-IT, E2E | `OutboundHostPolicyBankAwareTest`; `EgressRuleApplierTest`; integration-hub.spec AC-INT-17 |
| AC-INT-18 | API client: created through maker/checker (`API_CLIENT_CREATE`). The secret is shown once at apply and stored as an Argon2id hash. Rotation keeps two secrets valid during the overlap; revoke is immediate. | H2-IT, E2E | `ApiClientLifecycleTest`; integration-hub.spec AC-INT-18 |
| AC-INT-19 | OAuth2 client credentials: `/api/integration/oauth/token` (client_secret_basic) issues a JWT with `typ=client`, `client_id`, `bank_id`, `scope` and `token_ver`; TTL 5–15 min from the setting. A wrong secret gives 401 and `API_CLIENT_AUTH_FAILED`. | H2-IT, E2E | `OAuthTokenEndpointTest`; integration-hub.spec AC-INT-19 |
| AC-INT-20 | API key: `Authorization: ApiKey <prefix>.<secret>`. Optional HMAC request signing over method, path, timestamp and body digest; a bad signature or a stale timestamp gives 401 | H2-IT, E2E | `ApiKeyAuthenticatorTest`; integration-hub.spec AC-INT-20 |
| AC-INT-21 | mTLS: the cert fingerprint maps to the client. Client-cert headers are trusted only from the trusted proxy and stripped otherwise. The local nginx listener uses `ssl_verify_client optional`. | H2-IT, E2E | `MtlsClientAuthenticatorTest`; `scripts/check-nginx-headers.sh`; integration-hub.spec AC-INT-21 |
| AC-INT-22 | A client token reaches only `/api/integration/v1/**` within its scopes (granted = role grant ∩ scopes). User routes give 403; agent routes need AGENT_CONNECT. | H2-IT | `ClientScopeEnforcementTest` |
| AC-INT-23 | A bank-B client gets **404** (never 403) for bank-A job or document ids | H2-IT, PG-IT, E2E | `IntegrationApiCrossBankTest`; `pgit.IntegrationApiRlsPgIT`; integration-hub.spec AC-INT-23 |
| AC-INT-24 | The same `Idempotency-Key` with the same body returns the same `jobId` and creates no second job. The same key with a different body gives 409 or 422. | H2-IT, E2E | `IdempotencyTest`; integration-hub.spec AC-INT-24 |
| AC-INT-25 | A revoked or expired client, or a bumped `token_ver`, gets 401 on the next call with no TTL wait | H2-IT, E2E | `ClientRevocationTest`; integration-hub.spec AC-INT-25 |
| AC-INT-26 | `AllowedCidrs` is enforced; per-client rate limit gives 429; size caps give 413; every call is audited with the client as actor | H2-IT | `IntegrationApiGuardsTest` |
| AC-INT-27 | v1 endpoints: `/templates` (shared only), `/records` 202, `/files` 202, `/jobs/{id}`, `/results?format=json|csv` (masked, capped), `/errors`, `/documents/{id}` (released only), `/events?after=` (paging) | H2-IT, E2E | `IntegrationApiV1Test`; integration-hub.spec AC-INT-19/20/21 |
| AC-INT-28 | Agent registration (`AGENT_REGISTER`) yields a one-time 15-minute token and a `docker run` command. Enrolment works once; a reused or expired token is refused. | H2-IT, E2E | `AgentEnrolmentTest`; integration-hub.spec AC-INT-28 |
| AC-INT-29 | Agent protocol: long-poll (wait ≤ 30 s), record stream, payload fetch, result report, heartbeat. The dashboard shows status, last heartbeat and version; the agent goes offline after the heartbeat timeout. | H2-IT, E2E | `AgentProtocolTest`; integration-hub.spec AC-INT-29 |
| AC-INT-30 | The cloud never receives the bank DB password: none in any request, row or log. The agent's local allow-list refuses other tables, hosts and folders even when the cloud asks. | CONN-IT, E2E | `agent/` module `AllowListTest`; integration-hub.spec AC-INT-30 |
| AC-INT-31 | Integrations UI: overview, connection wizard, flow builder, run detail (log, retry, replay), API clients (secret shown once), agents; dashboard tiles | VITEST, E2E | `pages/Integrations*.test.jsx`; integration-hub.spec (all) |
| AC-INT-32 | Integration settings tab: rows/run, run timeout, minimum interval, webhook retries, masking default (SECURITY), client token TTL 5–15 (SECURITY), allowed auth methods (PLATFORM), heartbeat timeout | H2-IT, E2E | `SettingsConsumersTest#integration`; settings.spec (extend) |

### 5.7 Cross-cutting

| ID | Acceptance criterion | Level | Planned test |
|---|---|---|---|
| AC-X-01 | Every new route carries a permission (`RoutePermissionCoverageTest`); `RESERVED` is empty by Wave 4; `openapi-routes.txt` is regenerated at each merge | UNIT | `RoutePermissionCoverageTest`, `OpenApiContractTest` |
| AC-X-02 | Every new tenant table is RLS-covered and every non-tenant table is listed | PG-IT | `RlsPolicyCoveragePgIT`, `NonTenantTableInventoryPgIT` |
| AC-X-03 | E2E runs production-shaped (backend as `modocument_app`, production profile); sign-in with MFA works there | E2E | `e2e/tests/01-auth.spec.js`, `04-known-bugs.spec.js` BUG-01 |
| AC-X-04 | gitleaks and semgrep are clean; `check-nginx-headers.sh` passes; ZAP baseline (including the integration API) shows no High | CI | `security.yml`, `zap-baseline.yml` |
| AC-X-05 | New pages are keyboard-operable with labelled dialogs and controls (axe scan on the new pages) | E2E | Wave 4 `e2e/tests/phase2/a11y.spec.js` |

## 6. Smoke suite: existing features (live since Wave 1)

| ID | Journey | Location |
|---|---|---|
| HARNESS-01…05 | TOTP RFC vectors; TOTP step logic; fake-LLM answers and modes; SPA and API through one origin | `e2e/tests/00-harness.spec.js` |
| SMK-AUTH-01 | Bootstrap admin signs in, is forced through TOTP enrolment (secret read from the UI; QR, URI, 10 recovery codes) and then through the bootstrap password change (OBS-02 fix): other routes 403 `PASSWORD_CHANGE_REQUIRED`, the env password is dead afterwards | `e2e/tests/01-auth.spec.js` |
| SMK-AUTH-02 | A wrong TOTP code is refused; no session is stored | ″ |
| SMK-AUTH-03 | USER_CREATE needs a second admin: self-approval gives 409, the checker applies, and no credential appears in the queue | ″ |
| SMK-AUTH-04 | First sign-in with the temporary password forces a change (other routes give 403 `PASSWORD_CHANGE_REQUIRED`); the old password is dead and the new one works | ″ |
| SMK-TPL-01 | An operator designs a 3-field template in the editor | `e2e/tests/02-templates-ingestion.spec.js` |
| SMK-ING-01 | A CSV is uploaded, parsed deterministically (no LLM table call), mapped and validated: the job reaches VALIDATED with 0 errors | ″ |
| SMK-ING-02 | A free-text PDF goes through the AI parse path with the fake LLM in `ok` mode: 2 rows extracted | ″ |
| SMK-SES-01 | "Log out" revokes the access token server-side; protected routes bounce to login | `e2e/tests/03-session.spec.js` |

## 7. Traceability: criterion → owner → test → status

Status: **Live** = automated and running; **Fixme** = E2E placeholder exists
(`test.fixme`); **Planned** = the owner writes it in the stated wave;
**Bug** = failing regression test for an open bug.

| Criteria | Owner | Tests (lowest level first) | Wave | Status |
|---|---|---|---|---|
| AC-SET-01, 03, 04, 05, 06, 07, 08, 13, 16 | SE1 (API) + SE3 (UI); QA E2E | H2-IT `api/settings/*`; VITEST `SettingField`, `SettingsPage`; E2E `phase2/settings.spec.js` | 1–2 | **Live** (Wave 2) |
| AC-SET-02, 08, 10, 11, 14, 15 | SE1 | UNIT/H2-IT `infrastructure/settings/*`, `SettingsConsumersTest`, `SettingsParityTest` | 1–2 | Planned |
| AC-SET-09, 12, 17 | SE1 + QA (PG-IT review) | `pgit.SettingsPropagationPgIT`, `SettingsRlsPgIT`, inventories | 2 | Planned |
| AC-PLT-01, 03, 05, 06, 09, 10 | SE2; QA E2E | H2-IT appliers, PG-IT bank/trigger; QA H2-IT `PlatformBoundaryQaTest`; E2E `phase2/platform.spec.js` | 1–2 | **Live** (Wave 2) |
| AC-PLT-02, 04, 07, 08, 09, 11 | SE2 (+ Supervisor for the registry) | `PlatformRoleTriggerPgIT`, `PermissionCheckerPlatformTest`, `TenantContextCallSiteRegistryTest` | 1–2 | Planned |
| AC-ADM-01, 03, 04, 05, 07, 08, 10 | SE3; QA E2E | H2-IT dashboard/actions/visibility; QA PG-IT `UserAdminActionsRlsPgIT`; E2E `phase2/admin-dashboard.spec.js` | 1–2 | **Live** (Wave 2) |
| AC-ADM-02, 06, 09, 11 | SE3 + QA (PG-IT) | `pgit.DashboardIsolationPgIT`, `UserAdminActionsTest`, VITEST components | 1–2 | Planned |
| AC-EXT-02, 03, 04, 05, 11 | SE4; QA E2E | UNIT classifier/breaker; H2-IT parse fallback; QA H2-IT `ExtractionModeResolutionApiTest`; E2E `phase2/extraction-fallback.spec.js` with fake-LLM modes (API level) | 2 | **Live** (Wave 2); **Bug** BUG-03 (UNIT, disabled) |
| AC-EXT-02b (badge), 07, 16 | SE4 (+ SE3 UI) | VITEST `IngestionJobPage.test.jsx`, `TemplateExtractionSection.test.jsx`; E2E `phase2/extraction-fallback.spec.js` | 4 | **Live** (Wave 4, on SE4's Wave-4 branch) |
| AC-EXT-13 (AI_PARTIAL) | SE4 | H2-IT parse step; E2E needs a multi-batch document and a one-shot fake-LLM failure | 4 | **Fixme** (E2E) |
| AC-EXT-01, 06, 09, 10, 12, 14, 15 | SE4 | UNIT/H2-IT `infrastructure/transform/code/*` | 1–2 | Planned |
| AC-EXT-08 (corpus ≥ 95 %) | SE4 builds the engine; **QA curates the corpus and expected outputs** | `ExtractionCorpusTest` (31 engineer documents, SE4) + `QaExtractionCorpusTest` (9 QA documents incl. 2 negative, `src/test/resources/extraction-corpus/qa/`); a test enforces ≥ 40 documents, ≥ 95 % overall and ≥ 90 % per strategy | 1 (engine), 2–4 (corpus) | **Live** (40 documents, Wave 4) |
| AC-EXT-17 | SE2 | `InferSchemaUseCaseTest`, `SummarizeExtractionUseCaseTest` | 4 | Planned |
| AC-REL-02, 03, 06, 07, 07a, 07b | SE5; QA E2E | H2-IT system/announcements; VITEST banner; E2E `phase2/releases.spec.js` | 1–2 | **Live** (Wave 2) |
| AC-REL-01, 04, 05, 08, 09, 11 | SE5 | scripts, `MigrationPolicyTest`, `FlywayVersionRangeTest`, `DeployedVersionRecorderPgIT`, actionlint, terraform | 1–2 | Planned |
| AC-REL-10 | SE5 | runbook rehearsal | later | Planned (needs AWS) |
| AC-INT-01, 02, 32 | SE1 (vault, settings) + SE4 (connections API) + SE3 (UI) | `ConnectorSecretVaultTest`, `IntegrationApiTest`, settings tests; E2E AC-INT-01/02 | 3–4 | **Live** (Wave 4) |
| AC-INT-17 | SE1 | `BankEgressGuardTest`, `EgressRuleIntegrationTest`, `EgressRulePgIT`; QA `IntegrationHubSecurityQaTest`; E2E AC-INT-17/17b | 3–4 | **Live**; BUG-06 open (P4) |
| AC-INT-03…16 | SE4 (+ QA bank simulator) | connector unit tests (JDK HTTP stub, embedded MINA, H2), `DatabaseConnectorPgIT`, `IntegrationHubPgIT`, QA `ConnectorSecurityQaTest`, `IntegrationReadOnlyQaPgIT`; E2E `phase2/integration-hub.spec.js` | 3–4 | **Live** (AC-INT-15 SFTP runs against the compose simulator or a local MINA stand-in); BUG-08 open (P3) |
| AC-INT-18…27 | SE2 | `IntegrationApiSecurityTest`, `ApiClientAuthPgIT`, QA `IntegrationHubSecurityQaTest`; E2E | 3–4 | **Live** (AC-INT-21 E2E needs the nginx mTLS listener) |
| AC-INT-28…30 | SE5 (agent) | `agent/` module tests; `IntegrationAgentApiTest`, `AgentProtocol*Test`; E2E with the agent jar as a local process | 3–4 | **Live** |
| AC-INT-31 | SE3 | VITEST Integrations pages; E2E (connections, flow builder, API clients) | 3–4 | **Live** |
| AC-X-01, 02 | Supervisor (shared files) + each owner | `RoutePermissionCoverageTest`, RLS inventories | every wave | Live (existing tests) |
| AC-X-03 | QA (+ auth owner for BUG-01) | `01-auth.spec.js`, `04-known-bugs.spec.js`; PG-IT `MfaLoginPgIT` | 1 | **Live** (BUG-01 fixed) |
| AC-X-04 | SE5 + QA | `security.yml`, `zap-baseline.yml` (+ `.zap/integration-seed.py` for the integration API, Wave 4) | 4 | Live; ZAP not run in the sandbox (workflow validated with actionlint) |
| AC-X-05 | SE3 + QA | `phase2/a11y.spec.js` | 4 | Planned |
| HARNESS-*, SMK-* | QA | `e2e/tests/00…03` | 1 | **Live** |

## 8. Defects and observations from Wave 1

| ID | Sev | Finding | Evidence | Suggested owner |
|---|---|---|---|---|
| **BUG-01** (fixed, Wave 2) | **P1** | Under the production two-role model (backend as `modocument_app`), `POST /api/auth/mfa/verify` answers **502 "The database request failed."** — "new row violates row-level security policy for table auditevent". `MfaUseCase.verify` is `@Transactional` and starts with no tenant bound. `JpaAuditRecorder` binds the event's bank only around `writer.append()`, but the `LOGIN_SUCCESS` INSERT is flushed at COMMIT, after the binding is gone. **No MFA user, so no ADMIN, can complete sign-in in production.** Password-only logins work because `AuthUseCase` is not transactional. H2 tests have no RLS, and no PgIT signs in with MFA. | `e2e/tests/04-known-bugs.spec.js` BUG-01 (fails with `E2E_DB_APP_ROLE=rls`) | Supervisor to assign (audit and auth path). Add a PgIT next to `TenantIsolationGatePgIT`. |
| BUG-02 (fixed, Wave 2) | P4 | After a successful forced password change, the login page shows "Your session expired. Please sign in again." (`/login?expired=1`). `ForceChangePasswordPage` calls `logout()` with the token the change just revoked, and the 401 triggers `client.js`'s expired-session redirect. | `04-known-bugs.spec.js` BUG-02 | SE3 |
| OBS-01 | P2 (product gap) | A fresh install has exactly one bank admin, and every user-security change needs a different approver, so the product can never add a second user without database access. The E2E suite seeds the checker by SQL. Bank onboarding (SE2, ≥ 2 admins) fixes this for new banks but not for the DEFAULT bank of a fresh install. | `e2e/lib/db.js` | SE2 (e.g. a second bootstrap approver) |
| OBS-02 (fixed, Wave 2) | P3 | The bootstrap admin is not forced to change the env-provided password (`MustChangePassword=false`), unlike admin-created users and the planned platform admins | `BootstrapAdminRunner` | SE2 |
| OBS-03 | P2 for EXT | The AI client has no explicit retry or timeout configuration, so the Spring AI 1.0.1 defaults apply: 10 attempts with 2 s ×5 backoff up to 3 min on 5xx, and no read timeout. A 500 reaches the fallback only after about 19 min, and a hung model never times out. Set `spring.ai.retry.*` and a read timeout in the circuit-breaker work. | `application.yml` (no `spring.ai.retry`) | SE4 |
| OBS-04 | P3 | The MFA verify throttle is 5 per minute per client IP. Behind a bank's NAT, a handful of simultaneous sign-ins lock each other out. It should become a setting (AC-SET-11) with a realistic default. | `MfaRateLimiter` | SE1 |

## 8b. Defects and observations from Wave 2 (QA round 2)

| ID | Sev | Finding | Evidence | Suggested owner |
|---|---|---|---|---|
| **BUG-03** | P3 | A model read timeout sometimes surfaces as a bare `java.util.concurrent.CancellationException` (Spring's JDK request factory cancels the response future) instead of `ResourceAccessException(HttpTimeoutException)`. `AiFailureClassifier` does not know it: `isReplyProblem` is **true** (the parse stage sends a corrective re-prompt to the hung endpoint, doubling the wait before the code fallback: 2 × 120 s in production), `countsForCircuit` is **false** (a hung model never opens the breaker) and the reason is ERROR, not TIMEOUT. It also makes `IngestionFallbackIntegrationTest.aModelThatNeverAnswersTimesOutAndFallsBackToCode` flaky ("expected: 1 but was: 2" calls; seen in a full `mvn -B verify`, passes alone). | UNIT `AiTimeoutCancellationBug03Test` (`@Disabled("BUG-03…")`; fails when enabled); log excerpt in the test's Javadoc | SE4 (classifier: treat `CancellationException` in the chain as a timeout) |
| BUG-04 | P4 | After a forced password change the login page never shows the inline "Password changed — sign in again with your new password." notice (the toast does show). `ForceChangePasswordPage` awaits `logout()`: `setUser(null)` re-renders the route's `ProtectedRoute`, which redirects to `/login` without state before the page's own `navigate('/login', {state:{notice}})` runs. | E2E `04-known-bugs.spec.js` BUG-04 (`test.fixme`) | SE3 |
| OBS-05 | P3 | The Wave-0 route skeleton for the integration hub (`/api/admin/integrations/**`, `/api/integration/v1/**`, `/api/agent/v1/**`, 501 + `@RequiresPermission`) is not on the merged branch: those routes answer 404. Contracts §3 say the skeleton fixes route coverage and `openapi-routes.txt` up front. `PlatformBoundaryQaTest#aPlatformAdminGetsNoIntegrationAdminRouteOnceTheyExist` accepts 403 or 404 until they land. | `docs/api/openapi-routes.txt` has no integration routes | Supervisor |
| OBS-06 | P3 | The E2E stack had no platform admins (no `MODOCUMENT_PLATFORM_ADMIN_*`), so no platform spec, maintenance banner or RELEASE announcement could run. QA added them to `run-local.sh` and `docker-compose.e2e.yml` (optional there). The CI env-file generator in `e2e/README.md` now lists them; SE5 should add them to the job. | `e2e/run-local.sh` | SE5 (CI job) |

## 8c. Defects and observations from Wave 4 (QA round 4)

Full list with repro steps and the go/no-go: `docs/qa/phase2-signoff.md`.

| ID | Sev | Finding | Evidence | Suggested owner |
|---|---|---|---|---|
| BUG-06 | P4 | A REST_API/WEBHOOK connection whose base URL is the cloud metadata endpoint (`169.254.169.254`, `fd00:ec2::254`) is accepted at submit (202) and can be approved; only the connect-time egress guard refuses it. | `IntegrationHubSecurityQaTest#bug06MetadataDestinationRefusedAtSubmit` (`@Disabled`) | SE4 (ConnectionValidator) / SE1 |
| BUG-07 | P4 | Renaming a connection answers 200 with the version from before the rename (JPA bumps `@Version` at commit); sending it back as `expectedVersion` gives 409. | `IntegrationHubSecurityQaTest#bug07RenameAnswersTheCommittedVersion` (`@Disabled`); E2E AC-INT-01 re-reads | SE4 |
| BUG-08 | P3 | Raising or clearing the row cap of an ENABLED outbound flow is applied directly (200); the security plan (S37) treats it as a widening that needs a second admin. | `IntegrationHubSecurityQaTest#bug08RaisingTheRowCapIsAWidening` (`@Disabled`) | SE4 |
| OBS-07..15 | P3–P4 | See `docs/qa/phase2-signoff.md` §4 (DNS re-resolution window, no FILE_TOO_LARGE event, no webhook secret overlap, S28 signing not built, agent h2c on plain http, code-extraction gaps found by SE4). | | |

## 9. Sign-off record

| Wave | Date | Result | Waivers | QA | Supervisor |
|---|---|---|---|---|---|
| 1 | | | BUG-01 (owner mode for the smoke gate) pending decision | | |
| 4 | 2026-10-10 | QA: **GO** for v1.1.0-rc.1 (no open P1/P2; see `docs/qa/phase2-signoff.md`) | BUG-03, BUG-04, BUG-06, BUG-07, BUG-08 (P3/P4); AWS-only checks listed in the sign-off | QA | |
