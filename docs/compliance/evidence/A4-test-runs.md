# A4 evidence: RBAC enforcement test runs

Runs made for this pack by agent A4 on 2026-10-19 in a clean worktree of the repository (code identical to the pack's base commit). Raw Maven logs were kept in the agent's scratch area; the per-class results are copied below.

## Run 1: role, permission, SoD and isolation tests (H2, Spring Boot test context)

- Command: `mvn -B -Dtest='RoutePermissionCoverageTest,RolePermissionMatrixTest,SodPolicyTest,RoleAssignmentPolicyTest,PermissionCheckerPlatformGuardTest,PermissionEnforcementTest,PlatformAccessIntegrationTest,PlatformBoundaryQaTest,MakerCheckerIntegrationTest,UserAdminIntegrationTest,UserAdminActionsIntegrationTest,PlatformUserAdminIntegrationTest,DocReleaseSodIntegrationTest,PendingChangeAudienceTest,MaskingMatrixDocTest,MaskingGateC2Test,TemplateVisibilityMatrixTest,TemplateVisibilityMakerCheckerTest,AdminDashboardIsolationTest,UploadOwnershipEnforcementTest,AuditLogApiTest,BankLifecycleIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`
- Started 2026-10-19T16:21:19Z, finished 2026-10-19T16:26:02Z. Result: **BUILD SUCCESS, 155 tests, 0 failures, 0 errors, 0 skipped** in 22 classes.

| Test class | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| `src/test/java/com/modocument/api/UserAdminActionsIntegrationTest.java` | 9 | 0 | 0 | 0 |
| `src/test/java/com/modocument/api/TemplateVisibilityMakerCheckerTest.java` | 4 | 0 | 0 | 0 |
| `src/test/java/com/modocument/api/AdminDashboardIsolationTest.java` | 3 | 0 | 0 | 0 |
| `src/test/java/com/modocument/api/MaskingGateC2Test.java` | 4 | 0 | 0 | 0 |
| `src/test/java/com/modocument/api/AuditLogApiTest.java` | 9 | 0 | 0 | 0 |
| `src/test/java/com/modocument/api/MakerCheckerIntegrationTest.java` | 6 | 0 | 0 | 0 |
| `src/test/java/com/modocument/api/UserAdminIntegrationTest.java` | 10 | 0 | 0 | 0 |
| `src/test/java/com/modocument/api/BankLifecycleIntegrationTest.java` | 7 | 0 | 0 | 0 |
| `src/test/java/com/modocument/api/TemplateVisibilityMatrixTest.java` | 8 | 0 | 0 | 0 |
| `src/test/java/com/modocument/api/PlatformUserAdminIntegrationTest.java` | 3 | 0 | 0 | 0 |
| `src/test/java/com/modocument/api/DocReleaseSodIntegrationTest.java` | 6 | 0 | 0 | 0 |
| `src/test/java/com/modocument/api/security/PermissionEnforcementTest.java` | 7 | 0 | 0 | 0 |
| `src/test/java/com/modocument/api/security/RoutePermissionCoverageTest.java` | 2 | 0 | 0 | 0 |
| `src/test/java/com/modocument/api/security/PlatformAccessIntegrationTest.java` | 4 | 0 | 0 | 0 |
| `src/test/java/com/modocument/api/security/PlatformBoundaryQaTest.java` | 6 | 0 | 0 | 0 |
| `src/test/java/com/modocument/api/security/PermissionCheckerPlatformGuardTest.java` | 7 | 0 | 0 | 0 |
| `src/test/java/com/modocument/api/security/UploadOwnershipEnforcementTest.java` | 4 | 0 | 0 | 0 |
| `src/test/java/com/modocument/application/usecase/PendingChangeAudienceTest.java` | 40 | 0 | 0 | 0 |
| `src/test/java/com/modocument/application/privacy/MaskingMatrixDocTest.java` | 2 | 0 | 0 | 0 |
| `src/test/java/com/modocument/application/security/RolePermissionMatrixTest.java` | 2 | 0 | 0 | 0 |
| `src/test/java/com/modocument/application/security/SodPolicyTest.java` | 6 | 0 | 0 | 0 |
| `src/test/java/com/modocument/application/security/RoleAssignmentPolicyTest.java` | 6 | 0 | 0 | 0 |
| **Total** | **155** | **0** | **0** | **0** |

## Run 2: PostgreSQL row-level security and tenant isolation (real PostgreSQL 16)

- Command: `MODOCUMENT_IT_JDBC_URL=jdbc:postgresql://127.0.0.1:55432/postgres MODOCUMENT_IT_SUPERUSER=postgres mvn -B -Ppostgres-it -Dtest=SodPolicyTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='TenantIsolationGatePgIT,RlsPolicyCoveragePgIT,PlatformRoleTriggerPgIT,TemplateVisibilityPgIT,UserAdminActionsRlsPgIT,AuditLogRlsPgIT,AdminDashboardIsolationPgIT,NonTenantTableInventoryPgIT' verify`
- Database: a scratch PostgreSQL 16 on 127.0.0.1:55432; `src/test/java/com/modocument/pgit/PgItSupport.java` creates a fresh database, runs every Flyway migration as the owner, and connects the tests as the RLS-subject role `modocument_app`.
- Started 2026-10-19T16:27:41Z, finished 2026-10-19T16:29:34Z. Result: **BUILD SUCCESS, 28 integration tests, 0 failures, 0 errors, 0 skipped** in 8 classes (plus the 6 `SodPolicyTest` unit tests that the profile runs first).

| Test class | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| `src/test/java/com/modocument/pgit/RlsPolicyCoveragePgIT.java` | 2 | 0 | 0 | 0 |
| `src/test/java/com/modocument/pgit/PlatformRoleTriggerPgIT.java` | 5 | 0 | 0 | 0 |
| `src/test/java/com/modocument/pgit/TenantIsolationGatePgIT.java` | 5 | 0 | 0 | 0 |
| `src/test/java/com/modocument/pgit/TemplateVisibilityPgIT.java` | 5 | 0 | 0 | 0 |
| `src/test/java/com/modocument/pgit/AuditLogRlsPgIT.java` | 3 | 0 | 0 | 0 |
| `src/test/java/com/modocument/pgit/UserAdminActionsRlsPgIT.java` | 5 | 0 | 0 | 0 |
| `src/test/java/com/modocument/pgit/NonTenantTableInventoryPgIT.java` | 1 | 0 | 0 | 0 |
| `src/test/java/com/modocument/pgit/AdminDashboardIsolationPgIT.java` | 2 | 0 | 0 | 0 |
| **Total** | **28** | **0** | **0** | **0** |

## What these runs prove

| Claim | Proven by |
|---|---|
| Every route carries exactly one permission or is on the public allowlist; every permission guards at least one route | `src/test/java/com/modocument/api/security/RoutePermissionCoverageTest.java` |
| The committed matrix CSV is byte-identical to the code's matrix | `src/test/java/com/modocument/application/security/RolePermissionMatrixTest.java` |
| Platform permissions work only inside the platform bank; no domain permission for PLATFORM_ADMIN | `src/test/java/com/modocument/api/security/PermissionCheckerPlatformGuardTest.java`, `src/test/java/com/modocument/api/security/PlatformBoundaryQaTest.java`, `src/test/java/com/modocument/pgit/PlatformRoleTriggerPgIT.java` |
| Maker/checker on user, role and disable changes; self-approval blocked (409) | `src/test/java/com/modocument/api/MakerCheckerIntegrationTest.java`, `src/test/java/com/modocument/application/usecase/PendingChangeAudienceTest.java` |
| Author cannot release own document; nightly SoD scan finds a manufactured violation | `src/test/java/com/modocument/api/DocReleaseSodIntegrationTest.java` |
| Joiner, mover, leaver behave as the JML runbook says; user list export and access review | `src/test/java/com/modocument/api/UserAdminIntegrationTest.java`, `src/test/java/com/modocument/api/UserAdminActionsIntegrationTest.java` |
| Tenant tables carry RLS policies and a bank cannot read another bank's rows as `modocument_app` | `src/test/java/com/modocument/pgit/RlsPolicyCoveragePgIT.java`, `src/test/java/com/modocument/pgit/TenantIsolationGatePgIT.java`, `src/test/java/com/modocument/pgit/NonTenantTableInventoryPgIT.java` |
| PII masked in every export and read-only screen | `src/test/java/com/modocument/api/MaskingGateC2Test.java`, `src/test/java/com/modocument/application/privacy/MaskingMatrixDocTest.java` |
