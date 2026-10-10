# Phase 2 — QA sign-off (Wave 4, release candidate v1.1.0-rc.1)

Owner: QA. Date: 2026-10-10. Scope: everything phase 2 ships — settings,
platform owner, admin dashboard, code-extraction fallback, releases and
updates, the Bank Integration Hub and the on-prem Connector Agent — on the
integrated branch `claude/tender-noether-izbf0u` at `2e2f533` (all five
engineers' Wave-4 work) plus QA's Wave-4 commits. Plans and criteria:
[`docs/phase2/test-plan.md`](../phase2/test-plan.md); integration security
cases and their test map:
[`integration-security-tests.md`](integration-security-tests.md) §12.

## 1. Recommendation

**GO for `v1.1.0-rc.1`**, as a release candidate for staging, with the
conditions below. There is no open P1 or P2 bug. The open bugs (BUG-03,
BUG-06, BUG-07, BUG-08) are P3/P4, have regression tests committed as
`@Disabled`, and none weakens a security property in a way that is not
caught by a second control (see §4). What only AWS can prove is listed in §5
and must be checked on staging before `v1.1.0` (final).

Conditions:
1. Bump the release version before tagging (`pom.xml`, `agent/pom.xml`,
   `frontend/package.json` still say `1.0.0`; `scripts/bump-version.sh`,
   checked by `scripts/check-version-sync.sh`).
2. The CI E2E job (SE5) runs green on `main` with the compose bank simulator
   (incl. SFTP) at least once before the tag.
3. BUG-08 is fixed, or explicitly waived by the Supervisor, before `v1.1.0`.

## 2. Feature acceptance

| Feature | Criteria | Evidence (lowest level first) | Status |
|---|---|---|---|
| Settings (guardrails, maker/checker, propagation) | AC-SET-* | H2-IT `api/settings/*`, settings resolver/catalogue units, `SettingsPropagationPgIT`; E2E `settings.spec.js` (live); Wave 4: platform-controlled bank settings `GET/PUT /api/platform/banks/{id}/settings`, `integration.auth.allow*` locked for banks | **Accepted** |
| Platform owner (onboarding, suspension, platform admins) | AC-PLT-* | appliers H2-IT, `PlatformBoundaryQaTest`, platform PgITs; E2E `platform.spec.js`; Wave 4: pending changes decided only by the right audience (403 outside it, 409 self) | **Accepted** |
| Admin dashboard, user actions, shared templates | AC-ADM-* | `AdminDashboardIsolationTest`/`PgIT`, user-action tests; E2E `admin-dashboard.spec.js`; Wave 4: real integration counts and "How uploads were parsed (7 days)" | **Accepted** |
| Code extraction fallback | AC-EXT-* | classifier/breaker units, `IngestionFallbackIntegrationTest`, `ExtractionModeResolutionApiTest`, golden corpus (40 documents, ≥ 95 % overall, ≥ 90 % per strategy); E2E `extraction-fallback.spec.js` incl. Wave-4 UI (AC-EXT-02b badge, AC-EXT-07 low-confidence callout, AC-EXT-16 extraction section) | **Accepted**; AC-EXT-13 (AI_PARTIAL) E2E still fixme (covered at H2 level); BUG-03 open (P3); OBS-13–16 |
| Releases, announcements, version | AC-REL-* | system/announcement H2-IT, banner Vitest; E2E `releases.spec.js` | **Accepted** (AC-REL-10 runbook rehearsal needs AWS) |
| Bank Integration Hub (connections, flows, runs, API clients, Integration API v1, egress) | AC-INT-01…27, 31, 32 | connector units (JDK HTTP stub, embedded MINA, H2), engine/outbox H2-IT, `IntegrationApiSecurityTest`, `IntegrationHubPgIT`, `ApiClientAuthPgIT`, `EgressRulePgIT`, QA `IntegrationHubSecurityQaTest` / `ConnectorSecurityQaTest` / `IntegrationReadOnlyQaPgIT`; E2E `integration-hub.spec.js` 18 live (DB, REST, webhook, SFTP, API clients, idempotency, revocation, cross-bank 404, egress) | **Accepted**; BUG-06, BUG-07 (P4), BUG-08 (P3); AC-INT-21 (mTLS) E2E needs the ALB/nginx listener |
| On-prem Connector Agent | AC-INT-28…30 | `agent/` module (allow-list, secrets never logged, SFTP/DB connectors, mTLS), `IntegrationAgentApiTest`, `AgentProtocol*Test`, `IntegrationAgentRlsPgIT`, `AgentRunServiceTest` (agent deliveries audited `EXPORT_RUN` on `IntegrationFlow:<id>`); E2E: the agent jar as a local process enrols once, long-polls, pulls 25 bank rows, never sends the DB password, and refuses a table outside its allow-list | **Accepted** |
| Cross-cutting | AC-X-* | RLS inventories and route coverage, gitleaks, nginx header check, ZAP baseline (now seeding the integration API) | **Accepted**; ZAP not run in the sandbox (no ZAP), workflow validated with actionlint |

## 3. Test inventory (integrated state, this run)

| Suite | Count | Result |
|---|---|---|
| Backend H2 (`mvn -B verify`) | 1752 tests | 1748 pass, 0 fail, 4 skipped (BUG-03, BUG-06, BUG-07, BUG-08 `@Disabled` repros) |
| Backend PostgreSQL (`-Ppostgres-it`, scratch PG16) | 75 PgITs | 75 pass |
| Agent module (`mvn -B -f agent/pom.xml verify`) | 41 tests | 41 pass |
| Frontend Vitest | 456 tests (54 files) | 456 pass |
| Bank simulator node:test | 20 | 20 pass |
| E2E Playwright (`run-local.sh`, RLS mode, bank simulator, agent jar, SFTP stand-in) | 74 | **72 passed, 0 failed, 2 not run** (AC-EXT-13 fixme; AC-INT-21 skipped: no mTLS listener) |

QA's own additions in Wave 4: `IntegrationHubSecurityQaTest` (24 tests, 3
`@Disabled` bug repros), `ConnectorSecurityQaTest` (3),
`IntegrationReadOnlyQaPgIT` (1), 21 live integration E2E specs (were fixme)
and 3 live Wave-4 extraction specs. Security cases S1–S49: 48 PASS, 1 GAP
(S28, feature not built) — map in `integration-security-tests.md` §12.

E2E environment notes: the run used `e2e/run-local.sh` (Docker-free,
production profile, backend as `modocument_app` under RLS,
`E2E_BANK_SIM=1`). SFTP (AC-INT-15) ran against a local Apache MINA SFTP
server holding the simulator's committed TEST-ONLY host key (the sandbox has
no Docker for `atmoz/sftp`); in CI it runs against the compose simulator.
The scratch PostgreSQL trusts loopback logins, so the "wrong credential"
case of AC-INT-03 uses an unknown role.

## 4. Open bugs and observations

### Bugs (Wave 4; numbering continues from BUG-05)

| ID | Sev | What | Repro | Test | Owner |
|---|---|---|---|---|---|
| BUG-06 | P4 | A REST_API/WEBHOOK connection to the cloud metadata endpoint is accepted at submit (202) and can be approved; only the connect-time egress guard refuses it. The guard always refuses, so nothing is ever contacted; the plan (S2) wants 400 at submit like an egress rule for the same address. | `POST /api/admin/integrations/connections` `{"name":"x","type":"REST_API","mode":"DIRECT","authType":"NONE","config":{"baseUrl":"http://169.254.169.254/latest/meta-data"}}` → 202 | `IntegrationHubSecurityQaTest#bug06MetadataDestinationRefusedAtSubmit` (`@Disabled`) | SE4 (`ConnectionValidator`) / SE1 |
| BUG-07 | P4 | Renaming a connection answers 200 with the version from BEFORE the rename (`@Version` is bumped at commit, after the view is built). A client that sends it back as `expectedVersion` gets 409 "changed by someone else". | create a connection; `PUT …/connections/{id}` with a new name only → body `version` = v; `GET …/connections/{id}` → v+1; next PUT with v → 409 | `IntegrationHubSecurityQaTest#bug07RenameAnswersTheCommittedVersion` (`@Disabled`); E2E AC-INT-01 re-reads the connection | SE4 (`IntegrationConnectionUseCase.update`: `saveAndFlush`) |
| BUG-08 | P3 | Raising (or clearing) the row cap of an ENABLED outbound flow applies directly (200) and the flow keeps sending; S37 treats it as a widening that needs a second admin. `Snapshot#widenedBy` ignores the row cap. The bank's `integration.maxRowsPerRun` and the export caps still bound each run. | enable an outbound flow with `rowCap` 10 through maker/checker; `PUT …/flows/{id}` with `rowCap` 10000 → 200, still enabled | `IntegrationHubSecurityQaTest#bug08RaisingTheRowCapIsAWidening` (`@Disabled`) | SE4 (`IntegrationFlowUseCase`) |
| BUG-03 (Wave 2) | P3 | A model read timeout surfacing as `CancellationException` is classified ERROR, re-prompted and never opens the breaker. Still open. | see test plan §8b | `AiTimeoutCancellationBug03Test` (`@Disabled`) | SE4 |
| BUG-04 (Wave 2) | P4 | **Fixed in Wave 4 (SE3)**: the "Password changed" notice now shows; the E2E is a live regression test and passed in this run. | — | `04-known-bugs.spec.js` BUG-04 | — |

### Observations

| ID | Sev | What | Suggested owner |
|---|---|---|---|
| OBS-07 | P4 | The agent's JDK `HttpClient` offers an h2c upgrade on plain `http://`; `vite preview` never answers it, so a local agent must call the backend directly (`run-local.sh` does). Production is HTTPS to the ALB, where this does not arise. | SE5 (optionally pin HTTP/1.1 when `allow-insecure-http`) |
| OBS-08 | P4 | `ConnectionView.secretSet` is `true` for an AGENT connection that stores only a `secretRef` (no ciphertext). Contract §7.3 reads as "a secret is stored"; the UI shows it correctly. Document the meaning. | SE4 / contracts |
| OBS-09 | P4 | DNS: `BankEgressGuard` resolves the host right before connecting, then the HTTP/JDBC/SFTP client resolves it again. The JVM's DNS cache narrows the rebinding window to near zero; pinning the vetted address would close it. | SE1 / SE4 |
| OBS-10 | P4 | An SFTP file over the size cap is skipped and left in place (correct), but only a log line records it — no `FILE_TOO_LARGE` run event for the admin. | SE4 |
| OBS-11 | P4 | Webhook secret rotation has no overlap window: the new secret applies the moment the change is approved, so the bank must switch its verifier at the same time (or accept both). Document it in the developer guide. | SE4 / docs |
| OBS-12 | P3 | S28 / AC-INT-20 optional API-key request signing (HMAC over method, path, timestamp, body digest) was not built; it is not in the developer guide either. OAuth2 and mTLS are the recommended methods. Deferred. | SE2 (later) |
| OBS-13 | P3 | Code extraction: HDR/TRL records that use the file's delimiter are read as data rows (found by SE4). | SE4 |
| OBS-14 | P4 | Code extraction: a non-English totals label (e.g. "Summe") is kept as a row (SE4). | SE4 |
| OBS-15 | P4 | Code extraction: a summary line containing a pipe is read as a row (SE4). | SE4 |
| OBS-16 | P3 | Code extraction: two multi-space preamble lines over a fixed-width gap merge the Date and Particulars columns (SE4). | SE4 |
| OBS-17 | P3 | Accessibility: SE3's Wave-4 a11y pass (accessible names, captions, skip link) did not cover the phase-1 pages (ingestion, validation rules, template editor, doc builder). | SE3 (later wave) |
| OBS-01 (Wave 1) | P2 product gap | A fresh install still has one bank admin for the DEFAULT bank; the E2E suite seeds the checker by SQL. Onboarded banks are fine (≥ 2 admins). Unchanged. | SE2 |

## 5. Must wait for AWS (or the CI compose stack)

- **Live deploys**: staging/production ECS deploys, Flyway on RDS, the
  runbook rehearsal (AC-REL-10), rollback.
- **ALB mutual TLS**: the mTLS listener and trust store, the forwarded
  `X-Amzn-Mtls-Clientcert-*` path end to end (E2E AC-INT-21) and
  certificate-bound tokens over a real handshake. Locally only the header
  path (trusted-proxy check) is proven.
- **Real bank connectivity**: per-bank site-to-site VPN / PrivateLink for
  DIRECT mode; egress rules against real private ranges; the agent behind a
  bank proxy and its image on a read-only root file system.
- **ZAP baseline** on the deployed stack (the workflow now seeds the
  integration API; not run in the sandbox).
- **Connector contract tests on real vendors** (`-Pconnectors-it`:
  Testcontainers Oracle, SQL Server, MariaDB) and the CI E2E job with the
  compose SFTP simulator — need Docker.
- CloudFront/ALB timeouts with the agent's 25 s long-poll at production
  scale; the `agent-offline` alert on real heartbeats.

## 6. What QA changed in Wave 4

- `e2e/tests/phase2/integration-hub.spec.js`: aligned with the merged API and
  live by default (no `E2E_WAVE3`); the SFTP bank side is read over SFTP
  (no `docker exec`); agent run as a local jar.
- `e2e/tests/phase2/extraction-fallback.spec.js`: AC-EXT-02b/07/16 live.
- `e2e/lib/integration.js`, `e2e/lib/phase2.js`, `e2e/run-local.sh`
  (connector key, plain http for the simulator only, agent jar, agent URL).
- Backend tests (test code only): `IntegrationHubSecurityQaTest`,
  `ConnectorSecurityQaTest`, `IntegrationReadOnlyQaPgIT`.
- ZAP: `.github/workflows/zap-baseline.yml` gains one `cmd_options` line
  (SE5's file; minimal change) and `.zap/integration-seed.py`; the
  `.zap/rules.tsv` IGNOREs were re-reviewed and stay justified.
- Docs: this file, the S1–S49 map, the test plan (traceability, §8c, sign-off
  row), the e2e and bank-simulator READMEs.
