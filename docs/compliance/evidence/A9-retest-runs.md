# A9 — Independent re-performance runs for MO-034 (2026-10-19)

Agent A9 re-performed these checks for the internal control audit
(`docs/compliance/mo-034-internal-control-audit-report.md`). A9 owns no control,
so it acts as the independent tester for the controls owned by Bhavish Nobeen
(RBAC, stress testing and every workbook control). A human independent tester
must still sign off: Rushal for the Bhavish-owned controls and the DPO for the
data-protection controls (MO-001 RACI row 22). Synthetic data only; no secret,
token or real host name is reproduced here.

| Item | Value |
|---|---|
| Code under test | Worktree at `d079ff5`; `src/` and `pom.xml` are identical to `origin/main` at `23647fe` (PR #66), checked with a `git diff` of those paths |
| Environment | OpenJDK 21.0.11, Apache Maven 3.9.11 (offline, local repository), Spring `test` profile on H2; scratch PostgreSQL 16.14 on 127.0.0.1:55432 for the `-Ppostgres-it` batch; semgrep 1.179.0; Terraform 1.9.8 with A8's checksum-verified provider mirror |
| Who | Agent A9 (automated re-performance); human sign-off required |
| Raw outputs | Surefire/Failsafe XML reports and Maven logs kept in the agent scratch area; per-class counts copied below |

## 1. Test batches (Maven)

| Batch | Started (UTC) | Ended (UTC) | Exit | Classes | Tests | Failures + errors | Skipped |
|---|---|---|---|---|---|---|---|
| B1 — access control, SoD, maker/checker, audit, alerting | 2026-10-19 17:02:17 | 17:03:49 | 0 | 20 | 144 | 0 | 0 |
| B2 — authentication, MFA, passwords, secrets, crypto, logging | 17:03:49 | 17:05:36 | 0 | 17 | 147 | 0 | 0 |
| B3 — API, web, privacy, DSR, AI gateway, SIEM, alerts, change | 17:05:36 | 17:06:32 | 0 | 18 | 155 | 0 | 0 |
| B4 — PostgreSQL integration (RLS, append-only audit, AI cache) | 17:06:32 | 17:07:09 | 0 | 5 | 19 | 0 | 0 |
| **All** | | | | **60** | **465** | **0** | **0** |

`SodPolicyTest` runs in B1 and again as the unit stage of B4, so 59 distinct
classes ran. Counts are read from `target/surefire-reports/TEST-*.xml` and
`target/failsafe-reports/TEST-*.xml` of each batch.

Commands (repository root):

```bash
# B1
mvn -o -q -B -Djacoco.skip=true -Dsurefire.failIfNoSpecifiedTests=false test -Dtest='RoutePermissionCoverageTest,RolePermissionMatrixTest,SodPolicyTest,RoleAssignmentPolicyTest,MakerCheckerIntegrationTest,PendingChangeAudienceTest,DocReleaseSodIntegrationTest,UserAdminIntegrationTest,UserAdminActionsIntegrationTest,AdminDashboardIsolationTest,MaskingGateC2Test,PermissionCheckerPlatformGuardTest,AlertingGateD1Test,AccessDeniedBurstAlertTest,PlatformChangeAlertTest,AuditLogApiTest,JpaAuditRecorderTest,AuditTrailGapsTest,AuditViewAuditQaTest,AuditActionCatalogueDocTest'

# B2
mvn -o -q -B -Djacoco.skip=true -Dsurefire.failIfNoSpecifiedTests=false test -Dtest='MfaFlowIntegrationTest,MfaUseCaseTest,SecretFileEnvironmentPostProcessorTest,ConnectorSecretVaultTest,LogRedactionCanaryTest,RedactingConverterTest,UserAdminUseCaseTest,PasswordPolicyTest,AccountLockoutIntegrationTest,TotpTest,AuthFlowIntegrationTest,RevocationValidatorTest,JwtServiceTest,DatabaseTlsConfigTest,HeaderContractTest,MfaSecretCipherTest,IntegrationApiSecurityTest'

# B3
mvn -o -q -B -Djacoco.skip=true -Dsurefire.failIfNoSpecifiedTests=false test -Dtest='OpenApiContractTest,ApiDocsExposureTest,CrossUserAccessTest,ApiRateLimitIntegrationTest,FixedWindowRateLimiterTest,UploadContentGateSec030Test,MaskingMatrixDocTest,PiiTokenizerTest,DataSubjectRightsGateC12Test,RetentionPurgeGateC5Test,AiGatewayC9Test,RequestTraceabilityE9Test,SiemFeedE6Test,AlertRuleCatalogueTest,ExportCapGateD2Test,SyntheticProbeTest,MigrationPolicyTest,SettingsCatalogueDocTest'

# B4 (scratch PostgreSQL; PgItSupport creates a fresh database, migrates as owner, tests as modocument_app)
MODOCUMENT_IT_JDBC_URL=jdbc:postgresql://127.0.0.1:55432/postgres MODOCUMENT_IT_SUPERUSER=postgres \
  mvn -o -q -B -Djacoco.skip=true -Ppostgres-it -Dtest=SodPolicyTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test='AuditAppendOnlyPgIT,TenantIsolationGatePgIT,RlsPolicyCoveragePgIT,AiResultCacheRlsPgIT' verify
```

### B1

| Test class | Tests | Failures | Errors | Skipped | Controls |
|---|---|---|---|---|---|
| `src/test/java/com/modocument/api/AccessDeniedBurstAlertTest.java` | 5 | 0 | 0 | 0 | SEC-040 |
| `src/test/java/com/modocument/api/AdminDashboardIsolationTest.java` | 3 | 0 | 0 | 0 | SEC-016 |
| `src/test/java/com/modocument/api/AlertingGateD1Test.java` | 4 | 0 | 0 | 0 | SEC-032, SEC-040 |
| `src/test/java/com/modocument/application/audit/AuditActionCatalogueDocTest.java` | 4 | 0 | 0 | 0 | SEC-035 |
| `src/test/java/com/modocument/api/AuditLogApiTest.java` | 9 | 0 | 0 | 0 | SEC-015, SEC-035 |
| `src/test/java/com/modocument/api/AuditTrailGapsTest.java` | 9 | 0 | 0 | 0 | SEC-015, SEC-035 |
| `src/test/java/com/modocument/api/AuditViewAuditQaTest.java` | 3 | 0 | 0 | 0 | SEC-015 |
| `src/test/java/com/modocument/api/DocReleaseSodIntegrationTest.java` | 6 | 0 | 0 | 0 | SEC-024 |
| `src/test/java/com/modocument/infrastructure/audit/JpaAuditRecorderTest.java` | 8 | 0 | 0 | 0 | SEC-015, SEC-034 |
| `src/test/java/com/modocument/api/MakerCheckerIntegrationTest.java` | 6 | 0 | 0 | 0 | SEC-023, SEC-025 |
| `src/test/java/com/modocument/api/MaskingGateC2Test.java` | 4 | 0 | 0 | 0 | SEC-016, PRIV-004 |
| `src/test/java/com/modocument/application/usecase/PendingChangeAudienceTest.java` | 40 | 0 | 0 | 0 | SEC-023 |
| `src/test/java/com/modocument/api/security/PermissionCheckerPlatformGuardTest.java` | 7 | 0 | 0 | 0 | SEC-017 |
| `src/test/java/com/modocument/api/PlatformChangeAlertTest.java` | 1 | 0 | 0 | 0 | SEC-040, SEC-017 |
| `src/test/java/com/modocument/application/security/RoleAssignmentPolicyTest.java` | 6 | 0 | 0 | 0 | SEC-012, SEC-017 |
| `src/test/java/com/modocument/application/security/RolePermissionMatrixTest.java` | 2 | 0 | 0 | 0 | SEC-011, SEC-040 |
| `src/test/java/com/modocument/api/security/RoutePermissionCoverageTest.java` | 2 | 0 | 0 | 0 | SEC-011, SEC-006 |
| `src/test/java/com/modocument/application/security/SodPolicyTest.java` | 6 | 0 | 0 | 0 | SEC-012, SEC-024 |
| `src/test/java/com/modocument/api/UserAdminActionsIntegrationTest.java` | 9 | 0 | 0 | 0 | SEC-013, SEC-017 |
| `src/test/java/com/modocument/api/UserAdminIntegrationTest.java` | 10 | 0 | 0 | 0 | PRIV-017, SEC-013, SEC-020 |
| **Total (20 classes)** | **144** | **0** | **0** | **0** | |

### B2

| Test class | Tests | Failures | Errors | Skipped | Controls |
|---|---|---|---|---|---|
| `src/test/java/com/modocument/api/AccountLockoutIntegrationTest.java` | 2 | 0 | 0 | 0 | SEC-021 |
| `src/test/java/com/modocument/api/AuthFlowIntegrationTest.java` | 10 | 0 | 0 | 0 | SEC-022, SUP-S02 |
| `src/test/java/com/modocument/infrastructure/integration/secrets/ConnectorSecretVaultTest.java` | 13 | 0 | 0 | 0 | SEC-018, SUP-S08, SEC-028 |
| `src/test/java/com/modocument/infrastructure/persistence/DatabaseTlsConfigTest.java` | 3 | 0 | 0 | 0 | SEC-028 |
| `src/test/java/com/modocument/api/HeaderContractTest.java` | 5 | 0 | 0 | 0 | SEC-028, SUP-S01 |
| `src/test/java/com/modocument/api/integration/IntegrationApiSecurityTest.java` | 15 | 0 | 0 | 0 | SUP-S08, SEC-007 |
| `src/test/java/com/modocument/infrastructure/security/JwtServiceTest.java` | 4 | 0 | 0 | 0 | SUP-S02, SEC-029 |
| `src/test/java/com/modocument/infrastructure/logging/LogRedactionCanaryTest.java` | 1 | 0 | 0 | 0 | SEC-019 |
| `src/test/java/com/modocument/api/MfaFlowIntegrationTest.java` | 6 | 0 | 0 | 0 | SEC-014, SEC-022 |
| `src/test/java/com/modocument/infrastructure/security/MfaSecretCipherTest.java` | 7 | 0 | 0 | 0 | SEC-029, SEC-028 |
| `src/test/java/com/modocument/application/usecase/MfaUseCaseTest.java` | 10 | 0 | 0 | 0 | SEC-014, SEC-022 |
| `src/test/java/com/modocument/application/security/PasswordPolicyTest.java` | 8 | 0 | 0 | 0 | SEC-021 |
| `src/test/java/com/modocument/infrastructure/logging/RedactingConverterTest.java` | 15 | 0 | 0 | 0 | SEC-019 |
| `src/test/java/com/modocument/infrastructure/security/RevocationValidatorTest.java` | 7 | 0 | 0 | 0 | SUP-S02 |
| `src/test/java/com/modocument/infrastructure/security/SecretFileEnvironmentPostProcessorTest.java` | 13 | 0 | 0 | 0 | SEC-018 |
| `src/test/java/com/modocument/infrastructure/security/totp/TotpTest.java` | 6 | 0 | 0 | 0 | SEC-022, SEC-029 |
| `src/test/java/com/modocument/application/usecase/UserAdminUseCaseTest.java` | 22 | 0 | 0 | 0 | SEC-020 |
| **Total (17 classes)** | **147** | **0** | **0** | **0** | |

### B3

| Test class | Tests | Failures | Errors | Skipped | Controls |
|---|---|---|---|---|---|
| `src/test/java/com/modocument/infrastructure/ai/AiGatewayC9Test.java` | 6 | 0 | 0 | 0 | SUP-S13 |
| `src/test/java/com/modocument/infrastructure/alerting/AlertRuleCatalogueTest.java` | 8 | 0 | 0 | 0 | SEC-032 |
| `src/test/java/com/modocument/api/contract/ApiDocsExposureTest.java` | 3 | 0 | 0 | 0 | SEC-006 |
| `src/test/java/com/modocument/api/ApiRateLimitIntegrationTest.java` | 16 | 0 | 0 | 0 | SEC-007, SUP-S03 |
| `src/test/java/com/modocument/infrastructure/persistence/CrossUserAccessTest.java` | 5 | 0 | 0 | 0 | SEC-007 |
| `src/test/java/com/modocument/api/DataSubjectRightsGateC12Test.java` | 3 | 0 | 0 | 0 | PRIV-013 |
| `src/test/java/com/modocument/api/ExportCapGateD2Test.java` | 5 | 0 | 0 | 0 | PRIV-002 |
| `src/test/java/com/modocument/infrastructure/ratelimit/FixedWindowRateLimiterTest.java` | 5 | 0 | 0 | 0 | SEC-007 |
| `src/test/java/com/modocument/application/privacy/MaskingMatrixDocTest.java` | 2 | 0 | 0 | 0 | PRIV-004 |
| `src/test/java/com/modocument/release/MigrationPolicyTest.java` | 58 | 0 | 0 | 0 | SEC-025 |
| `src/test/java/com/modocument/api/contract/OpenApiContractTest.java` | 7 | 0 | 0 | 0 | SEC-006 |
| `src/test/java/com/modocument/application/privacy/PiiTokenizerTest.java` | 15 | 0 | 0 | 0 | PRIV-004, SUP-S13 |
| `src/test/java/com/modocument/api/RequestTraceabilityE9Test.java` | 3 | 0 | 0 | 0 | SEC-034 |
| `src/test/java/com/modocument/api/RetentionPurgeGateC5Test.java` | 2 | 0 | 0 | 0 | PRIV-013 (retention) |
| `src/test/java/com/modocument/infrastructure/settings/SettingsCatalogueDocTest.java` | 1 | 0 | 0 | 0 | SEC-025 (settings catalogue) |
| `src/test/java/com/modocument/api/SiemFeedE6Test.java` | 6 | 0 | 0 | 0 | SEC-031 |
| `src/test/java/com/modocument/api/SyntheticProbeTest.java` | 3 | 0 | 0 | 0 | SEC-005, SUP-S11 |
| `src/test/java/com/modocument/api/security/UploadContentGateSec030Test.java` | 7 | 0 | 0 | 0 | SUP-S01 |
| **Total (18 classes)** | **155** | **0** | **0** | **0** | |

### B4

| Test class | Tests | Failures | Errors | Skipped | Controls |
|---|---|---|---|---|---|
| `src/test/java/com/modocument/pgit/AiResultCacheRlsPgIT.java` | 1 | 0 | 0 | 0 | SUP-S13 |
| `src/test/java/com/modocument/pgit/AuditAppendOnlyPgIT.java` | 5 | 0 | 0 | 0 | SEC-035, SEC-015, SUP-S06 |
| `src/test/java/com/modocument/pgit/RlsPolicyCoveragePgIT.java` | 2 | 0 | 0 | 0 | SEC-013, SUP-S06 |
| `src/test/java/com/modocument/application/security/SodPolicyTest.java` | 6 | 0 | 0 | 0 | SEC-012, SEC-024 |
| `src/test/java/com/modocument/pgit/TenantIsolationGatePgIT.java` | 5 | 0 | 0 | 0 | SEC-013, SEC-011 |
| **Total (5 classes)** | **19** | **0** | **0** | **0** | |

## 2. Other re-performed checks

| # | Check | Command or method | Time (UTC) | Result | Controls |
|---|---|---|---|---|---|
| X1 | Terraform format and validation | Copy of `infra/terraform` in the scratch area; `terraform fmt -check -recursive`; per env `terraform init -backend=false` and `terraform validate` | 2026-10-19 17:03:42 | fmt exit 0; staging and production "Success! The configuration is valid." | SEC-001, SEC-004, PRIV-003, PRIV-006, SEC-033, SUP-S03, SUP-S06, SUP-S07 |
| X2 | Semgrep repository rules | `semgrep scan --config .semgrep/modocument.yml --metrics=off --error --json .` | 17:04:36 | exit 0; 0 results, 0 errors, 1,033 files scanned; 6 rules | SEC-029, SEC-009 |
| X3 | Reference check of the security catalogue, CIA table and threat matrix | A7's checker (every backticked path and test class exists), copied to the A9 scratch area and run with `python3 -I` | 17:05 | Catalogue: 106 paths, 100 test classes, 0 missing. CIA table: 31 paths, 49 test classes, 0 missing. Threat matrix: 10 paths, 64 test classes; one flagged token, `infra/terraform/modules/{backups,security-monitoring}`, is a brace pattern, and both folders exist (observation O-3) | SEC-027, PRIV-011, SEC-040 |
| X4 | RBAC probe raw output against the report figures (MO-019) | Re-computed every expected value of `docs/compliance/evidence/A4-rbac-probe.json` from `docs/security/role-permission-matrix.csv` (API client: role grant ∩ scopes ingest, results; anonymous: 401) and every actual value from the HTTP status (401 / 403 / other) | 17:00 | 352 probes; 126 allow, 182 deny, 44 unauthenticated; **0 mismatches**; all 41 permissions probed. Scenarios 39, 38 PASS, 1 FAIL (X-01: READ_ONLY malformed body → 400 instead of 403). Matches `docs/compliance/mo-019-role-based-access-system-report.md` §1 and the JSON summary block | SEC-011, SEC-013, SEC-016, SEC-017, SEC-023, SEC-024, PRIV-017 |
| X5 | Spring Boot version behind issue #52 | `pom.xml` `spring-boot-starter-parent` | 17:10 | 3.5.3 (unchanged; the critical CVEs remain) | SEC-026, SEC-009 |
| X6 | Code owners | `.github/CODEOWNERS` | 17:10 | One account owns every path | SEC-010, SEC-025 |
| X7 | Egress and lock defaults | `infra/terraform/modules/network/variables.tf` (`app_egress_https_cidrs`), `infra/terraform/modules/edge/variables.tf` (`enforce_egress_nacl` default `false`), `infra/terraform/envs/production/variables.tf` (`enforce_backup_locks` default `false`) | 17:10 | Egress open to 0.0.0.0/0 by default; NACL and locks off | PRIV-002, PRIV-003, SEC-033 |
| X8 | DEF-1 and DEF-2 still present | `infra/terraform/modules/network/main.tf` (interface endpoints use the app security group), `infra/terraform/modules/security-monitoring/main.tf` (`kms_master_key_id = "alias/aws/sns"`) | 17:10 | Both present | SEC-001, SUP-S11, SEC-032 |
| X9 | `admin-login` alert roles | `src/main/resources/application.yml` | 17:12 | `actor-roles: [ADMIN]` (PLATFORM_ADMIN not covered; MO-019 X-05) | SEC-017, SEC-016 |
| X10 | Security contact | `frontend/public/.well-known/security.txt` | 17:12 | Contact on the reserved `.example` domain; Expires 2027-10-04 | SUP-S12 |
| X11 | Compose admin MFA default | `docker-compose.yml` | 17:12 | `MODOCUMENT_MFA_REQUIRE_FOR_ADMIN` defaults to `false` | SEC-014 |
| X12 | Audit hash chain | search of `src/main` for hash-chain code | 17:12 | none found | SEC-035 |
| X13 | Counts claimed by control pages | 26 alert rules (`src/main/resources/application.yml`); 11,384 breached-password entries (`src/main/resources/security/common-passwords.txt`, comments excluded); 58 of 58 external actions SHA-pinned (`.github/workflows/`); 131 audit actions (`docs/security/audit-events.md`); 245 routes (`docs/api/openapi-routes.txt`) | 17:15 | All five match the control pages | SEC-032, SEC-021, SUP-S05, SEC-035, SEC-006 |
| X14 | Argon2id parameters and h2 scope | `src/main/java/com/modocument/api/config/SecurityConfig.java`; `pom.xml` | 17:15 | `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`; h2 has `runtime` scope (ships in the jar) | SEC-018, SEC-042 |
| X15 | Commits cited by control pages | `git cat-file -t` for 1035294, e43719c, c51ac6c, 64160f9 | 17:15 | All four are commits | PRIV-004, SEC-026, SEC-025 |
| X16 | Pack completeness | `python3 scripts/compliance/check_pack.py` | at commit | see `docs/compliance/mo-034-internal-control-audit-report.md` §3 | all |

## 3. Not re-performed, and why

| Check | Why not |
|---|---|
| `scripts/check-nginx-headers.sh` (SUP-S01) | The agent sandbox refuses to run repository shell scripts it cannot inspect for git use. A6 and A5 ran it on 2026-10-19 (`docs/compliance/evidence/A6-test-runs.md`, `docs/compliance/evidence/A5-scans-and-tests.md`); A9 relies on those runs. |
| gitleaks history scan (SEC-018, SEC-009) | Not installed for A9; A5 and A6 runs relied on |
| Trivy, OWASP ZAP, OWASP dependency-check | No tool or no feed access in the sandbox; latest CI results are from 2026-10-12/13 (`docs/compliance/evidence/A5-vulnerability-assessment-report-2026-10.md`) |
| Live RBAC probe (`scripts/compliance/rbac-probe.mjs`) | Not re-run; its archived raw output was re-checked line by line instead (X4) |
| Anything in AWS, GitHub settings, DNS, MDM or HR records | `NOT IN REPO (live cloud)` or `TO CONFIRM (organisational)` |

## 4. Stress-test raw output re-computed (MO-017)

| Item | Value |
|---|---|
| When | 2026-10-19, finished 17:35:32 UTC, after A3's merge (`7cc0815`) |
| Input | `perf/results/stress-2026-10/main/` (`s3`, `s4`, `s5c`, `s6`, `soak`), `perf/results/stress-2026-10/one-cpu/` (`s4-lite`, `s6`), `perf/results/stress-2026-10/default-limits/` (`s5a`): the `*.requests.csv.gz` per-request files and the `*.summary.json` stage windows |
| Method | A9's own Python script (scratch area, run with `python3 -I`): for each stage, select the requests whose timestamp falls in [stage start, start + stage seconds), count failures (`ok` = 0) and 5xx, and compute nearest-rank p95 per request class; probe calls (`probe.synthetic`) are in the files but not in the summaries |
| Result | Every p95 the report states for S4 (1×, 2×, spike, recovery), S6 (each step, including the 400-user dashboard p95 608.9 ms), S5c, one-CPU S4-lite and one-CPU S6 is reproduced exactly; 0 failed requests and 0 5xx in every file; S3 50,000-row job: VALIDATED in 5,780.4 ms, job read 764.9 ms and 17,945,399 bytes; S5a: 22 responses 429 (uploads 3, exports 3, generate 5, ingestion 5, sign-in 6) |
| Difference found | S4 stage totals sum to 26,438 (as in the report); the scenario total in `s4.summary.json` is 26,439 (MO-034 observation O-6) |
| Not done | The stress run itself was not repeated (about one hour of the shared machine) |
