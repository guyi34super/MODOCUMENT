# A5 — scans and test runs (Security workbook 02)

Runs made for this pack on **2026-10-19** by agent A5 (automated re-performance;
human sign-off required). Repository state: commit `eb53e97` (the pack
skeleton on top of `main` at `23647fe`). Environment: a sandbox with Java 21,
Maven 3.9.11 (offline repository), Node 22 / npm 10.9.4, semgrep 1.179.0,
gitleaks 8.24.3. No Docker daemon, so no `*PgIT` (PostgreSQL) test and no
container scan could run here. Outbound access to `semgrep.dev`,
`services.nvd.nist.gov`, `api.osv.dev`, `www.cisa.gov` and the GitHub
artifact/log blob store was refused by the sandbox egress proxy.

All test data is synthetic (the tests' own seed users, banks and rows). No
secret is reproduced here: gitleaks ran with `--redact`.

Used by: PRIV-002, PRIV-011, SEC-009, SEC-015, SEC-031, SEC-032, SEC-040,
SUP-S12 (pages in `docs/compliance/controls/`).

## 1. Backend tests — detection, export caps, egress, SIEM, audit (batch 1)

Command (from the repository root), 16:23:06Z → 16:26:19Z UTC:

```
mvn -o -B test -Dtest='ExportCapGateD2Test,ExportGuardTest,AlertingGateD1Test,AlertRuleCatalogueTest,AlertServiceTest,SiemFeedE6Test,OutboundHostPolicyTest,EgressTargetsTest,BankEgressGuardTest,EgressRuleIntegrationTest,AccessDeniedBurstAlertTest,PlatformChangeAlertTest,AuditLogApiTest,AuditTrailGapsTest,SyntheticProbeTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Result: **BUILD SUCCESS — 92 tests, 0 failures, 0 errors, 0 skipped.**

| Test class | Tests | Result | What it proves for this pack |
|---|---|---|---|
| `ExportCapGateD2Test` | 5 | Pass | 10,000-row export by READ_WRITE (cap 5,000) refused 403 `EXPORT_CAP_EXCEEDED`, audited `EXPORT_BLOCKED`, alert `export-blocked` (HIGH) raised from that audit row and delivered to the webhook; with a reason it proceeds, audited `EXPORT_OVER_CAP_WITH_REASON` + `EXPORT_RUN`; database path capped the same; over-long reason → 400 |
| `ExportGuardTest` | 8 | Pass | Cap logic per role, reason normalisation |
| `AlertingGateD1Test` | 4 | Pass | 5 failed logins → windowed alerts per user and per IP reach the webhook; `ACCOUNT_LOCKED` alerts immediately; ADMIN sign-in raises `admin-login`; alerts visible to the own bank's admin only |
| `AlertRuleCatalogueTest` | 8 | Pass | The shipped rule catalogue is pinned and validated |
| `AlertServiceTest` | 6 | Pass | Rule matching, dedupe |
| `AccessDeniedBurstAlertTest` | 5 | Pass | `access-denied-burst` windowed rule |
| `PlatformChangeAlertTest` | 1 | Pass | `platform-change` rule (bank lifecycle, egress approvals) |
| `SiemFeedE6Test` | 6 | Pass | Bank A's feed holds only bank A's events and vice versa; cursor paging; `siem:read` ADMIN only; push forwarder single-bank; schema doc field list = serializer output |
| `OutboundHostPolicyTest` | 5 | Pass | User-supplied URLs resolving to internal ranges refused |
| `EgressTargetsTest` | 6 | Pass | Host/CIDR parsing; metadata addresses never allowed |
| `BankEgressGuardTest` | 14 | Pass | Private destinations only via APPROVED bank egress rules |
| `EgressRuleIntegrationTest` | 3 | Pass | Request → approval by platform admins → enforced |
| `AuditLogApiTest` | 9 | Pass | Audit viewer API (filters, paging, bank scope) |
| `AuditTrailGapsTest` | 9 | Pass | Previously unaudited paths now write audit rows |
| `SyntheticProbeTest` | 3 | Pass | Public probe through the full path |

### 1a. Triggered alerts and timing (PRIV-002, SEC-032)

The application log of this run carries one `SECURITY ALERT` line per raised
alert and one `audit action=...` line per audit row, both tagged with the
request id. A script paired each immediate alert with the audit row that
triggered it (same request id) and with the `ALERT_SENT` rows written when
each channel (log, test webhook) accepted the alert.

| Rule | Severity | Trigger audit action | Audit row → alert raised (ms) | Audit row → last channel accepted, `ALERT_SENT` (ms) |
|---|---|---|---|---|
| `sod-attempt-blocked` | MEDIUM | `SELF_APPROVAL_BLOCKED` | 108 | 226 |
| `platform-change` | HIGH | `EGRESS_RULE_APPROVED` | 7 | 112 |
| `platform-change` | HIGH | `EGRESS_RULE_APPROVED` | 76 | 112 |
| `platform-change` | HIGH | `BANK_REACTIVATED` | 45 | 346 |
| `platform-change` | HIGH | `BANK_REACTIVATED` | 74 | 346 |
| `admin-login` | LOW | `LOGIN_SUCCESS` | 12 | 324 |
| `account-locked` | HIGH | `ACCOUNT_LOCKED` | 47 | 77 |
| `export-blocked` | HIGH | `EXPORT_BLOCKED` | 13 | 43 |
| `export-over-cap` | INFO | `EXPORT_OVER_CAP_WITH_REASON` | 17 | 34 |
| `export-blocked` | HIGH | `EXPORT_BLOCKED` | 9 | 37 |
| `export-over-cap` | INFO | `EXPORT_OVER_CAP_WITH_REASON` | 4 | 23 |

Summary: 11 immediate alerts measured; all raised within 108 ms and accepted
by every channel within 346 ms of the triggering audit row. A further 12
windowed alerts (`login-failures-per-user`, `login-failures-per-ip`,
`access-denied-burst`, one `account-locked`) were raised by sweeps that the
tests call themselves, so their timing reflects the test's pacing; the
production worst case is the sweep interval (`modocument.alerts.sweep.interval-ms`,
60,000 ms) plus delivery. `AlertingGateD1Test` asserts the windowed alert is
created less than 5 minutes after the audit row.

The triggered test alert for PRIV-002 (log excerpt; synthetic user #92 of the
test bank 47; request id kept, nothing else identifying):

```
16:26:16.419Z [01a154fc-31bc-7beb-b74c-1b597f421607] audit action=EXPORT_BLOCKED outcome=DENIED entity=IngestionJob:8 actorUserId=92 bankId=47
16:26:16.432Z [01a154fc-31bc-7beb-b74c-1b597f421607] SECURITY ALERT [MoDocument HIGH] export-blocked (bank 47, alert #20): Export above the role row cap refused (no reason stated) - EXPORT_BLOCKED (DENIED) by user #92 from ip 127.0.0.1 on IngestionJob:8
16:26:16.447Z [01a154fc-31bc-7beb-b74c-1b597f421607] audit action=ALERT_SENT outcome=SUCCESS entity=AlertLog:20
16:26:16.462Z [01a154fc-31bc-7beb-b74c-1b597f421607] audit action=ALERT_SENT outcome=SUCCESS entity=AlertLog:20
```

Limits: "channel accepted" means the log appender and the loopback test
webhook (`src/test/java/com/modocument/testsupport/WebhookStub.java`, wired in `src/test/java/com/modocument/api/DetectionGateTestSupport.java`)
accepted the alert. No phone, pager or mailbox received it.

## 2. Backend tests — audit, privileged actions, SIEM re-run (batch 2)

Run after editing `docs/security/siem-event-schema.md` (its field table is
pinned by `SiemFeedE6Test`). 16:35:21Z → 16:36:45Z UTC:

```
mvn -o -B test -Dtest='SiemFeedE6Test,AgentOfflineSweepTest,AuthFlowIntegrationTest,MakerCheckerIntegrationTest,JpaAuditRecorderTest,UserAdminIntegrationTest,AuditViewAuditQaTest,AiGatewayC9Test' -Dsurefire.failIfNoSpecifiedTests=false
```

Result: **BUILD SUCCESS — 52 tests, 0 failures, 0 errors, 0 skipped.**

| Test class | Tests | Result | Relevance |
|---|---|---|---|
| `SiemFeedE6Test` | 6 | Pass | Schema doc still matches the serializer after the edit |
| `AgentOfflineSweepTest` | 3 | Pass | `agent-offline` alert recorded |
| `AuthFlowIntegrationTest` | 10 | Pass | Refresh replay refused and audited |
| `MakerCheckerIntegrationTest` | 6 | Pass | Privileged changes need a second admin; self-approval blocked and audited |
| `JpaAuditRecorderTest` | 8 | Pass | Audit rows enriched with actor, session, IP, request id |
| `UserAdminIntegrationTest` | 10 | Pass | Admin user actions audited |
| `AuditViewAuditQaTest` | 3 | Pass | Reading and exporting the audit log is itself audited |
| `AiGatewayC9Test` | 6 | Pass | AI gateway rules |

## 3. Access-control matrix pin (SEC-040)

```
mvn -o -B test -Dtest='RolePermissionMatrixTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Result (16:38Z): **BUILD SUCCESS — 2 tests, 0 failures.** `docs/security/role-permission-matrix.csv` is byte-equal to the matrix rendered from `RolePermissions`.

## 4. Secret scanning — gitleaks 8.24.3

| Command | Scope | Result |
|---|---|---|
| `gitleaks detect --source . --redact --no-banner --config .gitleaks.toml --report-format json` | git history (286 commits scanned in 19.9 s; HEAD has 271 non-merge commits, the clone is not shallow) with the repo's `.gitleaks.toml` and `.gitleaksignore` | **0 leaks** (exit 0) |
| `gitleaks dir . --redact --no-banner --config .gitleaks.toml --report-format json` | working tree, files as they are now | 21 matches, exit 1. Triage: all false positives (see below) |

Working-tree triage (the `dir` mode does not apply the commit-based
fingerprints in `.gitleaksignore`):

| Rule | Where | Count | Triage |
|---|---|---|---|
| `generic-api-key` | `src/test/java/...` (`IntegrationHubSecurityQaTest`, `IntegrationEngineTest`, `IntegrationApiTest`, `PlatformBankApiTest`, `DefaultDocRuleEvaluatorTest`, `SecretFileEnvironmentPostProcessorTest`), `agent/src/test/java/.../SecretsNeverLoggedTest.java` | 14 | Synthetic test fixtures (e.g. a fixed HMAC test value, a test file name). Not credentials. |
| `generic-api-key` | `frontend/src/pages/PlatformCommon.jsx`, `frontend/src/utils/temporaryPassword.js` | 2 | The character alphabet used to generate temporary passwords. Not a secret. |
| `private-key` | `connector-core/src/main/java/com/modocument/connector/core/rest/TlsMaterial.java`, `agent/src/main/java/com/modocument/agent/cloud/AgentTls.java`, `agent/README.md`, `.gitleaksignore` | 4 | The PEM header string in code, docs and comments. No key material. |

## 5. SAST — semgrep 1.179.0

| Command | Result |
|---|---|
| `semgrep scan --metrics=off --config .semgrep/modocument.yml --json` | **0 findings, 0 errors**, 1,033 files scanned (repo rules: weak crypto, raw `ChatModel` outside `infrastructure/ai`, `System.getenv` outside the allow-list, `request-host-used`) |
| Registry packs `p/java`, `p/owasp-top-ten` (used in `.github/workflows/security.yml`) | **Not run here**: `semgrep.dev` is unreachable from this sandbox. Last CI evidence: the Security workflow on `main` succeeded on 2026-10-12 (run 38212039156, commit `d6ea503`). |

## 6. Dependency vulnerability checks

| Command | Scope | Result |
|---|---|---|
| `npm audit --omit=dev --json` (in `frontend/`) | 37 production packages | **0 vulnerabilities** (0 critical, 0 high, 0 moderate, 0 low) |
| `npm audit --json` (in `frontend/`) | all 269 packages incl. dev | **0 vulnerabilities** |
| `npm audit --json` (in `e2e/`) | 18 packages (test tooling) | **0 vulnerabilities** |
| `mvn -B -q org.owasp:dependency-check-maven:12.1.0:check` | Maven runtime dependencies | **Could not complete**: the vulnerability data feeds (NVD, CISA KEV) are blocked from this sandbox ("UpdateException ... 403", "NoDataException: No documents exist"). |
| `trivy`, `grype`, `osv-scanner` | — | Not installed; OSV API unreachable. |
| `mvn -o -B -q dependency:list -DincludeScope=runtime` | resolved runtime versions | Used to re-check the Trivy findings below against the current code |

**Latest container/dependency scan available — GitHub Actions, Weekly image
rebuild, run 38249093919 on 2026-10-12** (`.github/workflows/image-rebuild.yml`,
Trivy, fixable HIGH/CRITICAL only): `modocument-app` image **52 fixable (11
critical)**, `modocument-frontend` image **0 fixable**. The workflow opened
GitHub issue #52 "Security: base image refresh" (open on 2026-10-19). The
dependency list above shows that the current code still resolves the
affected versions (for example `tomcat-embed-core` 10.1.42,
`spring-security-web` 6.5.1, `spring-webmvc` 6.2.8, `jackson-databind` 2.19.1,
`postgresql` 42.7.7, `spring-ai-client-chat` 1.0.1; `pom.xml` parent
`spring-boot-starter-parent` 3.5.3). Triage and SLAs:
`docs/compliance/evidence/A5-vulnerability-assessment-report-2026-10.md`.

## 7. Web scan — OWASP ZAP baseline

Not runnable here (no Docker). Latest run: GitHub Actions **ZAP baseline, run
38400815763, 2026-10-13** (`.github/workflows/zap-baseline.yml`, commit
`b4c14cc`), the only run so far: 27 URLs, **FAIL-NEW 0, WARN-NEW 1, PASS 65**.
The warning: "Cross-Origin-Embedder-Policy Header Missing or Invalid [90004]
x 4". The job ended red because the action fails on warnings
(`fail_action: true`). The header is still absent from
`frontend/security-headers.conf` and not tuned in `.zap/rules.tsv`.

## 8. CI status of the scan workflows (read through the GitHub API)

| Workflow | Last run seen | Result |
|---|---|---|
| Security (`.github/workflows/security.yml`) | 2026-10-19 (scheduled, run 39241049111); also 2026-10-17 and 2026-10-18 pushes | Every job failed within 2–3 seconds. The merge commit messages of #65 and #66 state that GitHub Actions is not starting jobs ("no runner assigned") and that the gates were run locally. Last successful run on `main`: 2026-10-12 (run 38212039156). |
| Weekly image rebuild | 2026-10-19 (run 39236177050) | Failed in 4 s (same cause). Previous run 2026-10-12: Trivy ran, issue #52 opened, red because a fixable CRITICAL remains. |
| ZAP baseline | 2026-10-13 (run 38400815763) | See section 7. |
| Dependabot | 16 open Dependabot PRs on 2026-10-19 (#41–#51, #67–#71) | No open PR moves the root `spring-boot-starter-parent` off 3.5.3. |

## 9. Other checks

| Command | Result |
|---|---|
| `bash scripts/check-nginx-headers.sh` | "OK — 7 location(s) in frontend/nginx.conf carry the required security headers" (also covers `/.well-known/security.txt`, served by the `location /` block) |
| `python3 scripts/compliance/check_pack.py` | Run before commit; see the commit message of this pack's A5 commit |

Raw outputs (logs, JSON reports) were kept in the agent's scratch area and are
not committed: they are large and contain machine paths. Every figure above
comes from them.
