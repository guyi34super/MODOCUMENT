# MO-034 — Internal Control Audit Report

**Task MO-034: Internal control audit document.** Deliverable: Internal Control Audit Report + corrective action log.
**Linked DPO task:** MO-059 (the DPO takes part in testing the data-protection controls).

This report tests every control in the internal control matrix (`docs/compliance/mo-033-internal-control-matrix.md`), rates each one Effective, Partially effective, Not effective or Not tested, and records the findings, root causes, corrective actions and owners. Commands, times and raw counts of the re-performance are in `docs/compliance/evidence/A9-retest-runs.md`.

## 1. Document control and independence

| Field | Value |
|---|---|
| Document | MO-034 Internal Control Audit Report |
| Version | 1.0 draft |
| Fieldwork | 2026-10-19 (re-performance 17:00–17:15 UTC) |
| Status | Draft. Not yet reviewed or approved. |
| Tester | Agent A9 (automated re-performance). A9 owns no control and wrote no control page, so it is independent of the control owner, Bhavish Nobeen. |
| Human independent tester | Required by the checklist for Bhavish-owned controls (RBAC, stress testing): Rushal (MO-001 RACI row 22, proposed). Data-protection controls: the DPO (MO-059). Both sign-offs: `TO CONFIRM (organisational)`. |
| Review | Taj Aundoo (risk and control review; RACI row 22: C) |
| Approval | Board of Directors (RACI row 22: A; checklist "Management Approval: Yes") |

| Version | Date | Change | By |
|---|---|---|---|
| 1.0 draft | 2026-10-19 | First audit of 75 controls; 39 corrective actions | Agent A9 for Bhavish Nobeen |

## 2. Summary dashboard

| Overall rating | Controls | Share |
|---|---|---|
| Effective | 9 | 12 % |
| Partially effective | 33 | 44 % |
| Not effective | 11 | 15 % |
| Not tested | 19 | 25 % |
| Pending (A3) | 3 | 4 % |
| **Total** | **75** | |

| Group | Effective | Partially effective | Not effective | Not tested | Pending |
|---|---|---|---|---|---|
| 02 Security | 0 | 8 | 3 | 0 | 0 |
| 03 Software Engineering | 8 | 18 | 2 | 1 | 1 |
| 04 IT Infrastructure & Operations | 0 | 2 | 4 | 8 | 2 |
| ICM (governance, organisational, data protection) | 1 | 5 | 2 | 10 | 0 |

Re-performance: **39 controls** had their cited tests re-run by A9 (465 test executions in 59 classes, 0 failures); **16 more** were re-checked by static inspection or a re-run script (Terraform validation, Semgrep, reference checks, code reads). Every control was rated. Corrective actions: **38** open (CA-01 … CA-39, CA-33 pending A3).

**Key messages for the Board.**

1. **The product's access controls work.** RBAC, maker/checker, segregation of duties, tenant isolation, log redaction and password policy are Effective: the cited tests pass again, and the 352-call RBAC probe matches the matrix call for call.
2. **The engineering process is not operating.** CI has started no job since 2026-10-16, merges went in with red checks and no review, and 11 critical library CVEs are past their 72-hour limit (SEC-009, SEC-010, SEC-025, SEC-026: Not effective).
3. **Nobody is paged.** Alerts and monitoring are built but reach no person (SEC-032, SUP-S11, SEC-005: Not effective).
4. **Infrastructure controls cannot be tested yet.** They exist as validated Terraform only, with known defects (DEF-1 to DEF-13); nothing is deployed.
5. **Governance controls are designed but not yet operated:** no document approval, no named independent reviewer, DPO not confirmed.

## 3. Scope, method and sampling

**Population.** All 75 controls in MO-033: 57 workbook controls and ICM-01 to ICM-18. All 75 were rated; none was sampled out.

**Procedures per control.**

1. **Inspect** the control page, its YAML and the cited evidence files; confirm that every cited path exists (`python3 scripts/compliance/check_pack.py`).
2. **Re-perform** the cited tests where they run in the repository: four Maven batches on H2 and a scratch PostgreSQL 16 (B1–B4).
3. **Re-check** static facts the pages rely on (Terraform defaults, defect lines, counts, versions, commits): checks X1–X16.
4. **Re-compute** archived raw outputs against the report figures, for the Bhavish-owned RBAC report (MO-019) and the stress-testing report (MO-017).
5. **Rate** design and operation, then the overall rating.

**Sampling method.** Judgemental, risk-based. Every control with an executable test in the repository had at least one cited test class re-run; where a page cites many classes, the classes that prove the control objective were chosen (for example the gate tests `ExportCapGateD2Test`, `DataSubjectRightsGateC12Test`, `AlertingGateD1Test`). Population and selection: 59 classes covering 39 controls across all three workbooks and the ICM group (this audit set a minimum of 25). For records (merges, roadmap items, probe calls) the whole population was examined, not a sample: all 352 probe calls and 39 scenarios; all merges since 2026-07-01 as reported by A7.

**Rating criteria.**

| Rating | Design | Operation |
|---|---|---|
| Effective | The control, as built, meets its objective | Re-performed in the repository with the expected result; no gap affecting the objective |
| Partially effective | Meets part of the objective; a Build or Config gap remains | Works for part of the population or scenarios; evidence incomplete |
| Not effective | Missing, or cannot meet the objective as designed | Re-performance or records show it is not operating |
| Not tested | — | Operation cannot be tested from the repository (nothing deployed, or the record is held outside the repository); design reviewed |

The overall rating is the operating rating, except that a Not effective design makes the control Not effective.

**Limitations.**

- No live cloud: nothing in `infra/terraform/` is applied. Backups, failover, edge, GuardDuty, CloudTrail, alarms and log archives are rated on design only (`NOT IN REPO (live cloud)`).
- GitHub settings (branch ruleset, environment reviewers, billing), DNS, MDM, HR and contract records are outside the repository (`TO CONFIRM (organisational)`).
- Tests ran on H2 and a scratch PostgreSQL 16.14; production is designed for PostgreSQL 17.
- Re-performance by an agent is not a substitute for the human independent tester the checklist requires (§9).
- Not re-run by A9: `scripts/check-nginx-headers.sh` (sandbox restriction), gitleaks, Trivy, ZAP, the live RBAC probe (raw output re-checked instead). See `docs/compliance/evidence/A9-retest-runs.md` §3.

## 4. Re-check of the Bhavish-owned reports

### 4.1 RBAC (MO-019)

| Report figure (`docs/compliance/mo-019-role-based-access-system-report.md`) | Re-computed by A9 from `docs/compliance/evidence/A4-rbac-probe.json` | Agrees? |
|---|---|---|
| 352/352 probes as the matrix says | 352 probes; 0 mismatches when the expected value is re-derived from `docs/security/role-permission-matrix.csv` and the actual value from the HTTP status | Yes |
| 126 allowed / 182 denied / 44 anonymous | 126 / 182 / 44 | Yes |
| All 41 permissions probed | 41 of 41 | Yes |
| 38/39 scenarios as expected; deviation X-01 | 38 PASS, 1 FAIL: READ_ONLY malformed body → 400 (expected 403) | Yes |
| 155 H2 tests, 28 PostgreSQL tests pass (A4) | A9 re-ran 13 of the 22 H2 classes and 3 of the 8 PostgreSQL classes: all pass | Yes |

Conclusion: the MO-019 figures are supported by the archived raw output. The live probe itself was not re-run; Rushal should re-run it (command in MO-019 §9) as the human independent tester.

### 4.2 Stress testing (MO-017)

A3 input pending: the stress testing report (MO-017) and its raw outputs are not yet in the pack. When they are merged, A9 re-computes the reported p95, error rate and throughput from the archived raw results.

## 5. Results per control

Owner T01 = the control owner's own test result on the control page. A9 = what this audit re-performed (batch B1–B4 and check X1–X16 in `docs/compliance/evidence/A9-retest-runs.md`).

### 5.1 Workbook controls

| ID | Owner T01 | Design | Operation | **Overall** | A9 re-performance | Finding | Root cause | CA |
|---|---|---|---|---|---|---|---|---|
| PRIV-002 | Pass | Partially effective | Partially effective | **Partially effective** | `ExportCapGateD2Test` 5/5 (B3); egress defaults re-read (X7) | Caps block and audit as designed. Network egress is open to 0.0.0.0/0 and alerts reach no person. | Egress allow-list not signed off (gate D3); alert receiver not set up. | CA-04, CA-11 |
| PRIV-011 | Pass | Effective | Partially effective | **Partially effective** | Reference check of `docs/security/cia-controls-table.md`: 31 paths, 49 test classes, 0 missing (X3) | Table accurate. Not approved; availability column has no operating evidence. | Approval step not run; restore drill pending. | CA-19, CA-09 |
| SEC-009 | Fail | Partially effective | Not effective | **Not effective** | Semgrep repo rules 0 findings (X2); Spring Boot 3.5.3 still in `pom.xml` (X5) | 11 Critical (past the 72-hour SLA) and 41 High open; CI scans not running; no penetration test. | Patch not prioritised; CI billing failure; pen-test budget not set. | CA-01, CA-02, CA-08 |
| SEC-013 | Partial | Partially effective | Partially effective | **Partially effective** | `UserAdminActionsIntegrationTest` 9/9 (B1); `RlsPolicyCoveragePgIT` 2/2, `TenantIsolationGatePgIT` 5/5 (B4) | No time-bound elevation; no recertification held; no IAM policy lint. | Pre-production; elevation not built. | CA-12, CA-13 |
| SEC-015 | Partial | Partially effective | Partially effective | **Partially effective** | `AuditLogApiTest` 9/9, `AuditTrailGapsTest` 9/9, `AuditViewAuditQaTest` 3/3, `JpaAuditRecorderTest` 8/8 (B1); `AuditAppendOnlyPgIT` 5/5 (B4) | In-app logging effective. No pgAudit; CloudTrail and session recording not live. Page says the PgIT was not re-run; it passes (O-1). | Pre-production; pgAudit not configured. | CA-16, CA-23 |
| SEC-017 | Partial | Partially effective | Partially effective | **Partially effective** | `RoleAssignmentPolicyTest`, `PermissionCheckerPlatformGuardTest` 7/7, `PlatformChangeAlertTest` 1/1 (B1); X4, X9 | Everyday accounts double as admin; no grant record; no monthly review; break-glass password chosen by the platform maker. | Organisational steps not done; design choice for break-glass. | CA-12 |
| SEC-031 | Not run | Partially effective | Partially effective | **Partially effective** | `SiemFeedE6Test` 6/6 (B3) | No reference-SIEM ingestion test; structured logs and push not enabled in ECS. | Pre-production. | CA-24 |
| SEC-032 | Partial | Partially effective | Not effective | **Not effective** | `AlertingGateD1Test` 4/4, `AlertRuleCatalogueTest` 8/8 (B1, B3); 26 rules (X13); DEF-2 present (X8) | No receiver configured; ECS task passes no alert variables; infra alarms blocked by DEF-2; 16 of 26 rules untested. | On-call relay not chosen; Terraform defect. | CA-04, CA-29 |
| SEC-040 | Partial | Partially effective | Partially effective | **Partially effective** | `RolePermissionMatrixTest`, `AlertingGateD1Test`, `AccessDeniedBurstAlertTest` 5/5, `PlatformChangeAlertTest` (B1), `ExportCapGateD2Test` (B3); X3 | 8 of 12 threats lack a tested detective alert; matrices not approved. | Detective controls not yet built. | CA-29, CA-19 |
| SEC-041 | Partial | Effective | Partially effective | **Partially effective** | Roadmap read against the workbooks (`docs/security/security-roadmap.md`) | No budget amounts; proposed dates unconfirmed; no minutes of the monthly review. | Organisational steps outstanding. | CA-37 |
| SUP-S12 | Not run | Partially effective | Not effective | **Not effective** | `frontend/public/.well-known/security.txt` re-read (X10) | Contact is a placeholder on `.example`; no mailbox; policy URL private; CERT-MU not subscribed. | Owner conflict (MO-001 §3.4); mailbox not created. | CA-06 |
| PRIV-004 | Partial | Partially effective | Partially effective | **Partially effective** | `MaskingGateC2Test` 4/4, `MaskingMatrixDocTest` 2/2, `PiiTokenizerTest` 15/15 (B1, B3); commit 1035294 exists (X15) | Masking and tokenisation work. DPIA not signed by the DPO; no API-wide untagged-PII scan; data inventory out of date. | DPO not yet confirmed (MO-038). | CA-18 |
| PRIV-013 | Partial | Partially effective | Partially effective | **Partially effective** | `DataSubjectRightsGateC12Test` 3/3, `RetentionPurgeGateC5Test` 2/2 (B3) | Erase misses document titles, PDF-template photos, the scale-build Mongo store and agent staging; statutory period unconfirmed. | Stores added after the DSR feature were not all wired in. | CA-17 |
| PRIV-017 | Pass | Effective | Partially effective | **Partially effective** | `UserAdminIntegrationTest` 10/10 (B1); probe re-check (X4) | Mechanism works. No quarterly review held; shared pre-production account not yet deleted; no ticket trail. | Pre-production; organisational steps outstanding. | CA-13 |
| SEC-006 | Partial | Effective | Partially effective | **Partially effective** | `OpenApiContractTest` 7/7, `ApiDocsExposureTest` 3/3 (B3); route count 245 (X13) | Contract test does not gate merges while CI is down; sandbox not deployed. | CI outage (third-party billing). | CA-02, CA-36 |
| SEC-007 | Partial | Partially effective | Partially effective | **Partially effective** | `CrossUserAccessTest` 5/5, `ApiRateLimitIntegrationTest` 16/16, `FixedWindowRateLimiterTest` 5/5, `IntegrationApiSecurityTest` 15/15 (B2, B3) | No ZAP or API scan on file; rate-limit counters per instance; legacy endpoints bind entities. | Scans depend on CI; design choice for counters. | CA-02, CA-34 |
| SEC-008 | — | Pending (A3) | Pending (A3) | **Pending (A3)** | A3 input pending (MO-017) | A3 input pending (MO-017) | A3 input pending (MO-017) | CA-33 |
| SEC-010 | Fail | Partially effective | Not effective | **Not effective** | `.github/CODEOWNERS` has one account (X6); change trail (`docs/compliance/evidence/A7-github-change-trail.md`) | 32 merges by one account; 6 direct pushes; 0 reviews on sampled merges; four merges with red checks. | Ruleset not applied; one code owner; one-person engineering capacity. | CA-02, CA-03 |
| SEC-011 | Pass | Effective | Effective | **Effective** | `RoutePermissionCoverageTest` 2/2, `RolePermissionMatrixTest` 2/2 (B1); `TenantIsolationGatePgIT` 5/5 (B4); probe 352/352 re-checked (X4) | None in the product. Quarterly matrix review not yet held. | — | CA-13 |
| SEC-012 | Pass | Effective | Effective | **Effective** | `SodPolicyTest` 6/6, `RoleAssignmentPolicyTest` 6/6 (B1) | None. Annual catalogue review not yet held. | — | — |
| SEC-014 | Partial | Partially effective | Effective | **Partially effective** | `MfaFlowIntegrationTest` 6/6, `MfaUseCaseTest` 10/10 (B2); compose default re-read (X11) | No step-up; OIDC does not check amr/acr; APPROVER MFA optional (MO-019 X-02); compose defaults admin MFA off. | Design gaps. | CA-15 |
| SEC-016 | Pass | Effective | Effective | **Effective** | `AdminDashboardIsolationTest` 3/3, `MaskingGateC2Test` 4/4 (B1); probe re-check (X4) | None for the objective. Privileged-activity view and PLATFORM_ADMIN login alert open (X9). | — | CA-12 |
| SEC-018 | Partial | Partially effective | Partially effective | **Partially effective** | `SecretFileEnvironmentPostProcessorTest` 13/13, `ConnectorSecretVaultTest` 13/13 (B2); X14 | Argon2id below baseline; manual rotation; secret scanning not running in CI. | Default parameters kept; CI outage. | CA-26, CA-02 |
| SEC-019 | Pass | Effective | Effective | **Effective** | `LogRedactionCanaryTest` 1/1, `RedactingConverterTest` 15/15 (B2) | None in the repository; 30-day live search after go-live. | — | — |
| SEC-020 | Partial | Partially effective | Effective | **Partially effective** | `UserAdminUseCaseTest` 22/22 (B2), `UserAdminIntegrationTest` 10/10 (B1) | Temporary password known to the requesting admin and never expires. | Design gap. | CA-14 |
| SEC-021 | Pass | Effective | Effective | **Effective** | `PasswordPolicyTest` 8/8, `AccountLockoutIntegrationTest` 2/2 (B2); list size 11,384 (X13) | None for the objective; the breached list is local and small (enhancement). | — | — |
| SEC-022 | Partial | Partially effective | Partially effective | **Partially effective** | `TotpTest` 6/6, `AuthFlowIntegrationTest` 10/10, `MfaUseCaseTest` 10/10 (B2) | Admin-issued temporary passwords never expire. | Design gap. | CA-14 |
| SEC-023 | Pass | Effective | Effective | **Effective** | `MakerCheckerIntegrationTest` 6/6, `PendingChangeAudienceTest` 40/40 (B1); probe re-check (X4) | None in the product; `docs/security/maker-checker.md` is stale (O-5). | — | CA-23 |
| SEC-024 | Pass | Effective | Effective | **Effective** | `DocReleaseSodIntegrationTest` 6/6, `SodPolicyTest` 6/6 (B1) | None in the repository; nightly scan record after go-live. | — | — |
| SEC-025 | Partial | Partially effective | Not effective | **Not effective** | `MigrationPolicyTest` 58/58, `SettingsCatalogueDocTest` 1/1 (B3); X6 | Merges with red checks and no review; release pipeline never run; no tag or release. | CI outage; ruleset not applied; one-person team. | CA-02, CA-03 |
| SEC-027 | Pass | Effective | Effective | **Effective** | Reference check: 106 paths, 100 test classes, 0 missing (X3) | Accurate; not yet approved by a person (ICM-02). | — | CA-19 |
| SEC-028 | Partial | Partially effective | Partially effective | **Partially effective** | `DatabaseTlsConfigTest` 3/3, `HeaderContractTest` 5/5, `ConnectorSecretVaultTest`, `MfaSecretCipherTest` 7/7 (B2) | ALB to task is plain HTTP; bank DB targets may disable TLS; backups and logs use AWS-managed keys; nothing deployed. | Design choices not yet decided. | CA-27 |
| SEC-029 | Partial | Partially effective | Partially effective | **Partially effective** | Semgrep 0 findings (X2); `TotpTest`, `JwtServiceTest`, `MfaSecretCipherTest` (B2) | No explicit KMS key policies; Semgrep not enforced while CI is down; standard not approved. | CI outage; approval pending. | CA-02, CA-27, CA-19 |
| SEC-034 | Partial | Partially effective | Partially effective | **Partially effective** | `RequestTraceabilityE9Test` 3/3 (B3), `JpaAuditRecorderTest` 8/8 (B1) | Edge, ALB, nginx and agent do not carry the id; structured logs off in ECS. | Design gap. | CA-24 |
| SEC-035 | Partial | Partially effective | Effective | **Partially effective** | `AuditAppendOnlyPgIT` 5/5 (B4); `AuditActionCatalogueDocTest` 4/4, `AuditTrailGapsTest` 9/9 (B1); no hash chain (X12) | No hash chain or verification: a superuser edit goes undetected (A7 drill); `ExtractionAuditEvent` mutable. | Design gap. | CA-16 |
| SEC-042 | Partial | Partially effective | Partially effective | **Partially effective** | SBOMs on file (`docs/compliance/evidence/A7-sbom-summary.md`); h2 runtime scope (X14) | CI down so no SBOM for current main; frontend SBOM incomplete; no licence gate; h2 in the production jar. | CI outage; configuration gaps. | CA-02, CA-28 |
| SUP-S01 | Partial | Partially effective | Partially effective | **Partially effective** | `HeaderContractTest` 5/5 (B2), `UploadContentGateSec030Test` 7/7 (B3); header script not re-run (sandbox) | Access token in localStorage; no ZAP report or external header grade on file. | Design choice; CI outage. | CA-25, CA-34 |
| SUP-S02 | Pass | Partially effective | Effective | **Partially effective** | `RevocationValidatorTest` 7/7, `JwtServiceTest` 4/4, `AuthFlowIntegrationTest` 10/10 (B2) | No iss/aud; one HS256 key without kid. | Design gap. | CA-25 |
| SUP-S05 | Partial | Partially effective | Partially effective | **Partially effective** | 58 of 58 actions SHA-pinned (X13) | Staging role trusts every ref; production trusts a transitional `main` subject while `main` is unprotected; no provenance. | Ruleset not applied; design gap. | CA-03, CA-28 |
| SUP-S07 | Partial | Partially effective | Not tested | **Not tested** | `terraform validate` (X1) | No account exists; no `allowed_account_ids`; staging role trusts every ref; replay tests overdue. | Pre-production. | CA-30, CA-28 |
| SUP-S13 | Pass | Effective | Partially effective | **Partially effective** | `AiGatewayC9Test` 6/6, `PiiTokenizerTest` 15/15 (B3); `AiResultCacheRlsPgIT` 1/1 (B4) | Technical controls work. C11 acceptance not signed; production model location undecided; HTTPS not enforced for a non-local endpoint. | Decision pending. | CA-38, CA-11 |
| PRIV-003 | Partial | Partially effective | Not tested | **Not tested** | `terraform validate` (X1); lock default re-read (X7) | Locks off until the restore drill; GuardDuty runtime and malware features missing (DEF-3); file store not backed up (DEF-4). Nothing deployed. | Pre-production; drill (gate D6) not yet run. | CA-09, CA-10 |
| PRIV-006 | Partial | Partially effective | Not tested | **Not tested** | `terraform validate` (X1) | No backup exists yet; restore never timed; file store has no backup copy (DEF-4); RTO 2 h vs 4 h unresolved. | Pre-production; drill not scheduled. | CA-09, CA-39 |
| SEC-001 | Partial | Partially effective | Not tested | **Not tested** | `terraform fmt`/`validate` (X1); DEF-1 still present (X8) | DNS, ACM and GPU model servers outside Terraform; DEF-1 would stop the first production start; no external review. | Pre-production; IaC not planned against an account. | CA-10 |
| SEC-002 | — | Pending (A3) | Pending (A3) | **Pending (A3)** | A3 input pending (MO-017) | A3 input pending (MO-017) | A3 input pending (MO-017) | CA-33 |
| SEC-003 | — | Pending (A3) | Pending (A3) | **Pending (A3)** | A3 input pending (MO-017) | A3 input pending (MO-017) | A3 input pending (MO-017) | CA-33 |
| SEC-004 | Partial | Partially effective | Not tested | **Not tested** | `terraform validate` (X1) | Never failed over; region-loss RTO not documented; file store not replicated. | Pre-production. | CA-09 |
| SEC-005 | Partial | Not effective | Not tested | **Not effective** | `SyntheticProbeTest` 3/3 (B3) | No external probe, status page, SLA clause or measurement history exists. | Monitoring service not chosen. | CA-05, CA-39 |
| SEC-026 | Fail | Partially effective | Not effective | **Not effective** | Spring Boot 3.5.3 re-read in `pom.xml` (X5) | Issue #52: 11 Critical open since 2026-10-12, past the 72-hour limit; scheduled scans stopped; policy not approved. | Patch not prioritised; GitHub billing failure. | CA-01, CA-02, CA-19 |
| SEC-033 | Partial | Partially effective | Not tested | **Not tested** | `terraform validate` (X1); lock default (X7) | Only CloudTrail reaches the archive; AuditEvent and app logs not archived (DEF-5); locks off. | Pre-production; DEF-5. | CA-10, CA-16 |
| SUP-S03 | Partial | Partially effective | Not tested | **Not tested** | `ApiRateLimitIntegrationTest` 16/16 (B3); `terraform validate` (X1) | Edge not deployed; staging has no WAF; mTLS listener bypasses the WAF; no edge logs. | Pre-production. | CA-10 |
| SUP-S04 | Partial | Partially effective | Not tested | **Not tested** | `terraform validate` (X1) | No image signing or verification; writable root filesystems; base images by tag. | Design gap; pre-production. | CA-35 |
| SUP-S06 | Partial | Partially effective | Partially effective | **Partially effective** | `AuditAppendOnlyPgIT` 5/5, `RlsPolicyCoveragePgIT` 2/2 (B4); `terraform validate` (X1) | Database role layer works on PostgreSQL. App task holds the master credentials (DEF-11); no defined human access path; network tests not run. | Design gap; pre-production. | CA-10 |
| SUP-S08 | Partial | Partially effective | Partially effective | **Partially effective** | `ConnectorSecretVaultTest` 13/13, `IntegrationApiSecurityTest` 15/15 (B2) | No expiry alerts; rotation variables not injected (DEF-12); no key escrow. | Not built yet. | CA-31, CA-10 |
| SUP-S09 | Not run | Not effective | Not tested | **Not effective** | Policy read (`docs/compliance/policies/endpoint-device-policy.md`) | No MDM, encryption enforcement or EDR exists. | Not yet procured. | CA-07 |
| SUP-S10 | Not run | Not tested | Not tested | **Not tested** | Not testable: domain not in the repository | State unknown; workbook date 2026-10-09 passed. | DNS outside the repository. | CA-32 |
| SUP-S11 | Partial | Not effective | Not tested | **Not effective** | `SyntheticProbeTest` 3/3 (B3); DEF-2 present (X8) | No external journey probe, dashboard, paging or status page; alarm delivery blocked by DEF-2. | Monitoring not chosen; Terraform defect. | CA-04, CA-05 |

### 5.2 Governance, organisational and data-protection controls

| ID | Design | Operation | **Overall** | A9 procedure | Finding | Root cause | CA |
|---|---|---|---|---|---|---|---|
| ICM-01 | Partially effective | Not tested | **Not tested** | Version tables read; no approval recorded yet (proposed date 2026-11-02) | First approval cycle not yet run. | Pack in draft. | CA-39 |
| ICM-02 | Effective | Not effective | **Not effective** | Version tables inspected: roadmap v1.0 (2026-10-09) has no approval; v1.1 drafts pending | Security documents are in use with no recorded approval. | Approval step not scheduled. | CA-19 |
| ICM-03 | Effective | Partially effective | **Partially effective** | Register produced by A9; acceptances checked against the workbooks; C11 unsigned | 27 risks need an acceptance; only R-SHARED-01 (interim, to 21 Oct) and R-PEOPLE-02 (management decision) have one, recorded in the workbooks, not signed in the repository. | First register. | CA-38, CA-39 |
| ICM-04 | Effective | Not tested | **Not tested** | Gate items cross-checked against control pages | No go-live yet. | Pre-production. | — |
| ICM-05 | Partially effective | Not tested | **Not tested** | Stub read against the alert catalogue and recovery procedures | Plan is a stub: no severity matrix, on-call schedule, notification clocks or tabletop. | Pack-09 pending. | CA-22 |
| ICM-06 | Partially effective | Not tested | **Not tested** | Step 5 read: names no DPO step (MO-001 F1) | No DPO step in the notification path. | DPO not confirmed (MO-038). | CA-18, CA-22 |
| ICM-07 | Not tested | Not tested | **Not tested** | Not testable from the repository | No evidence in the repository. | Held outside the repository. | CA-21 |
| ICM-08 | Not tested | Not tested | **Not tested** | Not testable from the repository | No evidence in the repository. | Held outside the repository. | CA-21 |
| ICM-09 | Effective | Partially effective | **Partially effective** | `check_pack.py` run (X16); stale statements found (O-1 to O-5) | Completeness check passes for A9's paths; some pages carry stale or inconsistent statements; approvals missing (ICM-02). | Many authors in one day. | CA-23, CA-19 |
| ICM-10 | Partially effective | Not effective | **Not effective** | Re-performance by an agent only | Pack 07 reviewer not named; no human re-performance yet. | Roles not assigned. | CA-20 |
| ICM-11 | Partially effective | Not tested | **Not tested** | No minutes in the repository | Review stated in the workbook; no minutes in the repository. | Held outside the repository or not minuted. | CA-37 |
| ICM-12 | Not tested | Not tested | **Not tested** | No register in the repository; GitHub billing failure observed (`docs/compliance/evidence/A8-patch-report-2026-10.md`) | No vendor register; a provider billing failure stopped CI unnoticed for days. | Not set up. | CA-21, CA-02 |
| ICM-13 | Effective | Partially effective | **Partially effective** | `RetentionPurgeGateC5Test` 2/2 (B3) | Purge works; schedule sign-off and statutory periods not recorded. | DPO and sign-off pending. | CA-18 |
| ICM-14 | Partially effective | Effective | **Partially effective** | `MaskingGateC2Test` 4/4, `MaskingMatrixDocTest` 2/2 (B1, B3) | Works as designed; colleagues see raw values on bank-shared records (MO-019 X-03), DPO decision pending. | Feature added after the matrix. | CA-18 |
| ICM-15 | Partially effective | Partially effective | **Partially effective** | DPIA read; template has no DPO step (MO-001 F2) | DPIA drafted, not signed; template lacks a DPO step. | DPO not confirmed. | CA-18 |
| ICM-16 | Not tested | Not tested | **Not tested** | Not testable from the repository | DPO identity, appointment and line not confirmed. | MO-038 pending. | CA-21 |
| ICM-17 | Effective | Effective | **Effective** | `TenantIsolationGatePgIT` 5/5, `RlsPolicyCoveragePgIT` 2/2 (B4); probe: bank B got 404 on every bank A object (X4) | None. | — | — |
| ICM-18 | Partially effective | Not tested | **Not tested** | Roadmap budget lines read: no amounts | No thresholds or amounts recorded. | Not set. | CA-37 |

The DPO takes part in testing ICM-06 and ICM-13 to ICM-17 (MO-059): `TO CONFIRM (organisational)`.

### 5.3 Where the audit differs from the owner's own result

| ID | Owner T01 | Audit overall | Why |
|---|---|---|---|
| PRIV-002 | Pass | Partially effective | Owner's test proves the cap; the objective also needs a person alerted and egress control. |
| PRIV-011 | Pass | Partially effective | Table accurate, but not approved and the availability cells have no operating evidence. |
| SEC-031 | Not run | Partially effective | Owner left T01 Not run (reference SIEM); A9 re-ran the tenant-scoping test, which passes. |
| SEC-032 | Partial | Not effective | Alerts are raised but reach no person, so the objective (24/7 response) is not met. |
| SUP-S12 | Not run | Not effective | No mailbox exists, so no report can be received. |
| PRIV-017 | Pass | Partially effective | Mechanism passes; no review or deletion of the shared account evidenced. |
| SEC-016 | Pass | Effective | Agrees (Pass); the open items are enhancements. |
| SEC-019 | Pass | Effective | Agrees (Pass); the 30-day live search follows go-live. |
| SEC-021 | Pass | Effective | Agrees (Pass). |
| SEC-025 | Partial | Not effective | Design sound; the records show it did not operate (red checks, no review, no release run). |
| SUP-S02 | Pass | Partially effective | Revocation works; audience separation and key rotation without session loss are part of the objective. |
| SUP-S13 | Pass | Partially effective | Technical controls pass; the C11 acceptance is a required part of the control. |
| SEC-005 | Partial | Not effective | No external measurement exists at all. |
| SUP-S11 | Partial | Not effective | No external probe or paging exists. |

## 6. Observations on the pack itself

These are errors or stale statements in other pack pages, found while testing. They are not rewritten here (CA-23).

| # | Where | Observation | Effect |
|---|---|---|---|
| O-1 | `docs/compliance/controls/SEC-015.yaml` (test), `docs/compliance/controls/PRIV-011.yaml` and `docs/compliance/controls/PRIV-011.md` §Test, `docs/compliance/controls/SUP-S06.md` §Evidence | Say `AuditAppendOnlyPgIT` / the PgIT tests were not run because there is no Docker. A scratch PostgreSQL was available: A4 and A7 ran PgIT suites on 2026-10-19 (`docs/compliance/evidence/A4-test-runs.md`, `docs/compliance/evidence/A7-runs.md`), and A9 re-ran `AuditAppendOnlyPgIT` (5/5) and `RlsPolicyCoveragePgIT` (2/2). | Evidence understated; the append-only and RLS claims are proven. |
| O-2 | `docs/compliance/controls/SEC-018.yaml` vs `docs/compliance/evidence/A5-vulnerability-assessment-report-2026-10.md` | gitleaks history scan: 270 commits (gitleaks 8.21.2 over `origin/main`) vs 286 commits (8.24.3 over the worktree). Both clean; the scope differs and is not stated side by side. | Minor; state the ref scanned. |
| O-3 | `docs/security/threat-control-matrix.md` | Cites `infra/terraform/modules/{backups,security-monitoring}`, a brace pattern, not a path; both folders exist. | Reference checkers flag it. |
| O-4 | `docs/security/incident-response-stub.md` | Gives Rushal a surname, against the pack naming rule (MO-001 name note), and names no DPO step (MO-001 F1). | Naming consistency; ICM-06 finding. |
| O-5 | `docs/security/maker-checker.md` | Still says no bank-creation endpoint exists; `BANK_CREATE` exists (MO-001 F5, MO-019 X-07). | Stale documentation. |

A9 checked these numeric claims and found them correct: 26 alert rules, 11,384 breached-password entries, 58/58 SHA-pinned actions, 131 audit actions, 245 routes, four cited commits (X13, X15).

## 7. Corrective action log

All actions are Open on 2026-10-19. Owners follow the RACI; a date marked proposed needs the owner's confirmation. Progress is reported quarterly to the Board with the risk report (MO-032 §12).

| CA | Corrective action | Controls | Owner | Target date | Status |
|---|---|---|---|---|---|
| CA-01 | Upgrade Spring Boot and the listed libraries; rebuild, re-scan and ship a patch release closing issue #52; written acceptance for any CVE with no 6.2.x fix. | SEC-026, SEC-009 | Bhavish Nobeen; Reezvi Pydiah (acceptance) | 2026-10-22 (proposed; SLA passed 2026-10-15) | Open |
| CA-02 | Restore GitHub Actions (billing, runners); re-run CI, security, image-rebuild and ZAP on `main`, including the four merges made during the outage; add billing alerts. | SEC-010, SEC-025, SEC-009, SEC-006, SEC-018, SEC-029, SEC-042, SEC-007, ICM-12 | Bhavish Nobeen | 2026-10-22 (proposed) | Open |
| CA-03 | Apply the documented branch ruleset with no bypass; add a second code owner; export PR approvals each quarter. | SEC-010, SEC-025, SUP-S05 | Bhavish Nobeen; Rushal (second code owner, proposed) | 2026-10-30 (proposed) | Open |
| CA-04 | Connect the on-call relay; pass `MODOCUMENT_ALERTS_*` in the ECS task; give the SNS topic a customer-managed key (DEF-2); trip a HIGH rule out of hours with a written record. | SEC-032, SUP-S11, PRIV-002 | Bhavish Nobeen | 2026-10-30 (proposed) | Open |
| CA-05 | External probe on `/api/probe/synthetic` with an alarm to on call; status page; agreed SLA clause; monthly uptime report. | SEC-005, SUP-S11 | Bhavish Nobeen; Rushal | Before go-live (`TO CONFIRM (organisational)`) | Open |
| CA-06 | Create the security mailbox to the directors; real address in security.txt and SECURITY.md; public policy URL; CERT-MU subscription; test report acknowledged within 48 h. | SUP-S12 | Taj Aundoo (mailbox) / Bhavish Nobeen (policy) — `TO CONFIRM (organisational)` | 2026-10-30 (proposed) | Open |
| CA-07 | Choose and deploy MDM and EDR on every in-scope device; approve the device/BYOD policy. | SUP-S09 | Taj Aundoo (repo) / Bhavish Nobeen (workbook) — `TO CONFIRM (organisational)` | Before go-live | Open |
| CA-08 | Budget decision for the penetration test; vendor, scope and retest. | SEC-009 | Reezvi Pydiah | 2026-10-31 | Open |
| CA-09 | Deploy backups; timed restore drill (gate D6) in staging then production; then enable the locks; file-store backup copy (DEF-4); zone-failure test; agree one RTO. | PRIV-006, PRIV-003, SEC-004, PRIV-011 | Rushal (drill); Bhavish Nobeen (DR lead) | Before go-live | Open |
| CA-10 | Fix DEF-1 to DEF-13 (DEF-1 and DEF-2 first); add an IaC scanner and a `terraform plan` job to CI; bring DNS, ACM and model servers into IaC or scope them out. | SEC-001, SEC-033, SUP-S03, SUP-S06, SUP-S08, PRIV-003 | Bhavish Nobeen | Before the first `terraform apply` | Open |
| CA-11 | Sign off the egress allow-list (gate D3); narrow `app_egress_https_cidrs`; fix DEF-8 and enable the NACL; alert on a new destination; refuse non-HTTPS non-local AI endpoints. | PRIV-002, SUP-S13 | Bhavish Nobeen; Reezvi Pydiah (sign-off) | 2026-11-13 | Open |
| CA-12 | Grant record for the two administrators approved by Reezvi Pydiah; separate admin identities with elevation; first monthly privileged-activity review; PLATFORM_ADMIN in the admin-login alert; deliver break-glass passwords to the bank. | SEC-017, SEC-013, SEC-016 | Bhavish Nobeen; Reezvi Pydiah | 2026-10-30 | Open |
| CA-13 | Delete the shared pre-production account and record it; ticket per joiner; first quarterly recertification and matrix review at go-live. | PRIV-017, SEC-013, SEC-011 | Bhavish Nobeen | 2026-10-21 (account); go-live (review) | Open |
| CA-14 | Expire unused temporary passwords (for example 72 h); dumped-table sign-in test. | SEC-020, SEC-022 | Bhavish Nobeen | 2026-11-06 | Open |
| CA-15 | Step-up TOTP on approve, role change and export; require amr/acr for ADMIN OIDC sign-in; MFA for APPROVER; compose default admin MFA on. | SEC-014 | Bhavish Nobeen | 2026-11-20 | Open |
| CA-16 | Per-bank hash chain with daily verification and anchoring; `ExtractionAuditEvent` append-only; pgAudit; ship AuditEvent and log groups to the archive (DEF-5). | SEC-035, SEC-015, SEC-033 | Bhavish Nobeen | 2026-12-18 | Open |
| CA-17 | Extend DSR search/export/erase to document titles and photos; cover or forbid the Mongo store; confirm the statutory period. | PRIV-013 | Bhavish Nobeen; the DPO | 2026-11-20 | Open |
| CA-18 | DPO signs the DPIA; DPO step in the DPIA template and in breach notification; DPO decisions on X-03 and X-06; retention schedule sign-off. | PRIV-004, ICM-06, ICM-13, ICM-14, ICM-15 | The DPO; Bhavish Nobeen | 2026-11-14 (proposed) | Open |
| CA-19 | Approve the security documents: CIA table, matrices, roadmap, crypto standard, feature catalogue, change procedure, patch policy, escalation policy. | ICM-02, PRIV-011, SEC-040, SEC-029, SEC-027, SEC-026 | Reezvi Pydiah | 2026-11-02 (proposed) | Open |
| CA-20 | Name the Pack 07 reviewer; Rushal re-performs the Bhavish-owned tests and the DPO the data-protection tests; sign §9 of MO-034. | ICM-10 | Taj Aundoo; Rushal; the DPO | 2026-11-02 (proposed) | Open |
| CA-21 | Record the DPO appointment (MO-038), NDAs and undertakings, training records and a vendor register with DPAs. | ICM-07, ICM-08, ICM-12, ICM-16 | Board of Directors; Reezvi Pydiah; the DPO | 2026-11-30 (proposed) | Open |
| CA-22 | Write the incident pack-09 (severity, on-call, notification clocks, templates) and run a tabletop. | ICM-05, ICM-06 | Rushal | 2026-11-30 (proposed) | Open |
| CA-23 | Correct stale or inconsistent statements found by this audit (O-1 to O-5). | SEC-015, PRIV-011, SUP-S06, SEC-023, SEC-040, ICM-09 | Bhavish Nobeen | 2026-11-02 (proposed) | Open |
| CA-24 | Reference-SIEM test on staging; structured logs in ECS; request id at nginx, ALB, edge and agent. | SEC-031, SEC-034 | Rushal; Bhavish Nobeen | 2026-11-30 | Open |
| CA-25 | Add iss/aud; asymmetric keys with kid; keep the access token in memory. | SUP-S02, SUP-S01 | Bhavish Nobeen | 2026-11-20 | Open |
| CA-26 | Raise Argon2id parameters after a latency check; secret rotation or IAM DB auth. | SEC-018 | Bhavish Nobeen | 2026-11-06 | Open |
| CA-27 | TLS from ALB to containers or written acceptance; refuse non-TLS bank DB targets, webhooks and SMTP; own CMKs with key policies. | SEC-028, SEC-029 | Bhavish Nobeen | 2026-11-30 | Open |
| CA-28 | Restrict staging and production OIDC trust; build provenance; fix the frontend SBOM; licence gate; h2 to test scope. | SUP-S05, SEC-042, SUP-S07 | Bhavish Nobeen | 2026-11-13 | Open |
| CA-29 | Trip tests for the 16 untested alert rules; detective control or written acceptance for the 8 threats without one. | SEC-040, SEC-032 | Bhavish Nobeen; Reezvi Pydiah (acceptance) | 2026-11-30 (proposed) | Open |
| CA-30 | Create separate AWS accounts; `allowed_account_ids` in every provider; replay and reachability tests. | SUP-S07 | Bhavish Nobeen | Before the first `terraform apply` | Open |
| CA-31 | Expiry alerts at 30/14/7 days; key escrow for MFA and connector keys; staging rotation drill. | SUP-S08 | Bhavish Nobeen | Before go-live | Open |
| CA-32 | Confirm or publish SPF, DKIM and DMARC; MTA-STS; registrar MFA and lock. | SUP-S10 | Bhavish Nobeen (workbook) / Taj Aundoo (repo) — `TO CONFIRM (organisational)` | 2026-10-30 (proposed) | Open |
| CA-33 | A3 input pending (MO-017) | SEC-002, SEC-003, SEC-008 | Bhavish Nobeen; Rushal (run) | A3 input pending (MO-017) | Pending A3 |
| CA-34 | Authenticated ZAP API and active scan in staging; archive the reports; shared rate-limit store or WAF decision. | SEC-007, SUP-S01 | Bhavish Nobeen | 2026-11-06 | Open |
| CA-35 | Sign release images and verify before deploy; read-only root filesystems; base images by digest. | SUP-S04 | Bhavish Nobeen | Before go-live | Open |
| CA-36 | Deploy the API sandbox with synthetic data; publish a versioned spec with each release. | SEC-006 | Bhavish Nobeen | 2026-11-20 | Open |
| CA-37 | Set budget lines and Board thresholds; minute the monthly director review; set up the oversight forum. | SEC-041, ICM-11, ICM-18 | Reezvi Pydiah; Board of Directors | 2026-10-31 | Open |
| CA-38 | Reezvi Pydiah signs C11 and the other acceptances in MO-031 §9; decide the production model location. | SUP-S13, ICM-03 | Reezvi Pydiah; Bhavish Nobeen | 2026-11-02 | Open |
| CA-39 | Board approves MO-001, MO-032 and MO-034 and fixes one RTO and one critical-patch limit. | ICM-01, ICM-03, PRIV-006, SEC-005 | Board of Directors | 2026-11-02 (proposed) | Open |

## 8. Conclusion

Of 75 controls, 9 are Effective, 33 Partially effective, 11 Not effective and 19 Not tested (3 pending A3). The application-level controls are in good shape. The control environment around them (change, patching, alerting, approvals and independent review) is not yet operating, and the infrastructure controls cannot be tested until AWS is deployed. The four Not effective engineering controls (SEC-009, SEC-010, SEC-025, SEC-026) are addressed by CA-01 to CA-03 and CA-08, all due within two weeks. This audit should be repeated after go-live, with operating evidence from the deployed environment.

## 9. Review, independent testing and approval

| Role | Name | Decision | Date |
|---|---|---|---|
| Independent tester, Bhavish-owned controls (re-performs §4 and a sample of §5) | Rushal (proposed) | `TO CONFIRM (organisational)` | |
| Data-protection controls testing (MO-059) | The DPO | `TO CONFIRM (organisational)` | |
| Risk and control review | Taj Aundoo | `TO CONFIRM (organisational)` | |
| Approval | Board of Directors | `TO CONFIRM (organisational)` | |

## 10. To confirm

- `TO CONFIRM (organisational)`: Rushal's and the DPO's human re-performance and sign-off.
- `TO CONFIRM (organisational)`: the Pack 07 reviewer.
- `TO CONFIRM (organisational)`: records held outside the repository (NDAs, training, contracts, minutes, DNS, MDM, GitHub settings).
- `NOT IN REPO (live cloud)`: operating evidence of every Terraform-only control.
- A3 input pending: SEC-002, SEC-003, SEC-008 and the stress re-check (§4.2).
