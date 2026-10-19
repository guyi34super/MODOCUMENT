# MO-019 — Role-based access system report

| Task | Deliverable | Author | Reviewers / approval | DPO action | Status | Date |
|---|---|---|---|---|---|---|
| MO-019 | Role-Based Access System Report | Agent A4 for Bhavish Nobeen | Rushal (review); the DPO (privacy review, MO-056); Reezvi Pydiah (approval) | The DPO reviews the report for access to personal data and client segregation (§5, §7, §8) | Draft for review | 2026-10-19 |

## 1. Summary

This report checks that MoDocument enforces the role and permission matrix of MO-018 (`docs/compliance/mo-018-rbac-role-permission-matrix.md`). Everything below was re-performed on 2026-10-19. The checks used the repository's own tests and a live probe of each role on a production-profile stack, with synthetic users and data only.

| Check | Result | Evidence |
|---|---|---|
| Automated RBAC, SoD, JML, masking and isolation tests (H2) | 155 tests in 22 classes, 0 failures | `docs/compliance/evidence/A4-test-runs.md` |
| PostgreSQL row-level security and tenant isolation tests | 28 tests in 8 classes, 0 failures | `docs/compliance/evidence/A4-test-runs.md` |
| Route coverage: every route carries one permission | 238 of 245 routes guarded; the 7 others are the public allowlist | `docs/compliance/evidence/A4-route-permission-inventory.md` |
| Live probe: each permission × each role (6 roles + a second bank's ADMIN + anonymous) | **352/352 as the matrix says**: 126 allowed, 182 denied (403), 44 anonymous refused (401) | `docs/compliance/evidence/A4-rbac-probe.md` |
| Live scenarios: isolation, SoD, personal data, JML, account review | **38/39 as expected**. The one deviation is exception X-01 (Low) | `docs/compliance/evidence/A4-rbac-probe.md` |

**Conclusion.** The platform enforces the matrix for every role tested:

- Client data stays separate between banks.
- Self-approval and self-release are refused.
- A leaver is locked out the moment the disable is approved.

No defect lets a role do more than the matrix allows. Nine exceptions are recorded in §8: one product behaviour (X-01), five design or configuration points for decision (X-02 to X-06), one documentation error (X-07) and two organisational gaps (X-08, X-09). Remediation for each is in §8.

## 2. Scope and method

- **Code under test.** The repository at the pack's base commit. The backend jar is `target/modocument-1.2.0.jar` (production profile, no `SPRING_PROFILES_ACTIVE`).
- **Stack.** `e2e/run-local.sh` started a fresh PostgreSQL database for each run. The backend connected as the row-level-security role `modocument_app`, and the browser API path ran behind `vite preview`. The command was `SKIP_BUILD=1 E2E_RUN_CMD='node scripts/compliance/rbac-probe.mjs' e2e/run-local.sh`. The final run is dated 2026-10-19T16:44:14Z.
- **Accounts.** The probe (`scripts/compliance/rbac-probe.mjs`) created them through the product's own flows:

  | Bank | Accounts |
  |---|---|
  | Bank A (`DEFAULT`) | The bootstrap ADMIN. A second ADMIN, the checker: the E2E harness's one SQL-seeded account, because a fresh install has a single admin and every user change needs a second one (`e2e/lib/db.js`). READ_ONLY, READ_WRITE and APPROVER, each by `USER_CREATE` maker/checker. One API client by `API_CLIENT_CREATE` maker/checker. |
  | Platform bank | The two bootstrap `PLATFORM_ADMIN` accounts. |
  | Bank B | Two ADMINs, created by `BANK_CREATE` (platform maker/checker). |

  Every person completed the forced password change, and every ADMIN and PLATFORM_ADMIN enrolled TOTP.
- **Permission probes.** For each of the 41 permissions, one representative route (three for `user:admin`, two for `integration:admin`: 44 routes) was called by every account. Writes used non-existent ids or well-formed bodies, so an allowed call answers inside the handler (200, 201, 202, 400, 404 or 409) and changes nothing important. A denied call answers 403 from `@RequiresPermission`.
- **Expected values.** They come from `docs/security/role-permission-matrix.csv`. For the API client, the expectation is the role grant ∩ the client's scopes (ingest and results).
- **Limits.** The test is pre-production: there are no production accounts to review (owner statement in the workbooks: nothing deployed, no bank data received). Infrastructure accounts (AWS, Azure, RDS) are outside the application: `NOT IN REPO (live cloud)`.

## 3. How access is enforced

| Layer | What it does | Code |
|---|---|---|
| 1. Authentication | JWT bearer token for each request. Revocation is checked per request: token version, disabled flag, suspended bank. MFA is forced for ADMIN and PLATFORM_ADMIN | `src/main/java/com/modocument/infrastructure/security/RevocationValidator.java`, `src/main/java/com/modocument/application/usecase/AuthUseCase.java` |
| 2. Permission | Exactly one `@RequiresPermission` on every route, evaluated by `PermissionChecker` against `RolePermissions`. Deny by default. Platform permissions only in the platform bank. API-client scopes narrowed. Only self-service while a temporary password is set | `src/main/java/com/modocument/api/security/PermissionChecker.java`, `src/main/java/com/modocument/application/security/RolePermissions.java` |
| 3. Ownership | Inside a bank, private templates, jobs, extraction jobs and documents belong to their owner. Other users get 404 | `src/main/java/com/modocument/application/usecase/TemplateOwnership.java`, `src/main/java/com/modocument/application/usecase/IngestionJobUseCase.java` |
| 4. Tenant (database) | PostgreSQL row-level security on every table that has a `BankId`. The tenant is bound per connection and per transaction; no tenant means zero rows (fail closed) | `src/main/resources/db/migration/V13__rls_policies.sql`, `src/main/resources/db/migration/afterMigrate__rls_enforce.sql` |
| 5. Dual control and SoD | Pending changes need a different approver. Documents are released by someone other than the author. Nightly SoD scan | `src/main/java/com/modocument/application/security/SodPolicy.java`, `src/main/java/com/modocument/infrastructure/sod/SodScanJob.java` |
| 6. Masking | Exports and reports are masked for every role; screens are masked for READ_ONLY | `src/main/java/com/modocument/application/privacy/MaskingPolicy.java` |

## 4. Each role tested

Results from `docs/compliance/evidence/A4-rbac-probe.md`, showing for each account the probed permissions it was allowed (A) or denied (D). Every cell matched the matrix.

| Account | Allowed | Denied | Notable results |
|---|---|---|---|
| ADMIN (bank A) | All bank permissions | `lookup:admin`, `platform:*`, the integration and agent scopes | Gets 403 on `GET /api/platform/audit` (platform bank only) |
| APPROVER | Reads, `change:approve`, `doc:release`, `audit:read` | Every write, upload, export, AI, user administration, settings, integrations | Approved and released other people's work; owns no data |
| READ_WRITE | Reads, writes, upload, AI, export, document generation, `integration:run` | User administration, approvals, audit, settings, integration admin, platform | Submitting `USER_CREATE` through the generic queue: 403 |
| READ_ONLY | The 9 read and self-service permissions | Everything else, including export, upload and AI use | Reads a bank-shared template (200) but not a colleague's job (404) |
| PLATFORM_ADMIN | `platform:*`, `lookup:admin`, own-bank user admin, `change:approve`, `audit:read`, `siem:read` | **Every bank data permission** (templates, jobs, documents, exports) | Opening a bank A template or job: 403 |
| ADMIN (bank B) | Same as bank A's ADMIN | Same | Every bank A object answered 404 (§7) |
| API_CLIENT (scopes ingest + results) | `integration:ingest`, `integration:results` | Every user route, plus `integration:docs`, `integration:events` and `agent:connect` (not in its scopes) | — |
| Anonymous | — | All 44 guarded probes answered 401 | — |

## 5. Personal data seen by each role (for the DPO)

The probe ran a CSV with a synthetic name, NIC and phone number to VALIDATED on a **bank-shared** template, using the READ_WRITE account. The same records report (`GET /api/doccreation/records`) was then requested as six different callers (scenarios 28–35):

| Caller | Result |
|---|---|
| READ_WRITE (owner) | 200, raw NIC |
| APPROVER (colleague) | 200, raw NIC |
| ADMIN (colleague) | 200, raw NIC |
| READ_ONLY (colleague) | 200, NIC shown as `********` |
| ADMIN of bank B | 404 |
| PLATFORM_ADMIN | 403 |
| Owner's CSV export of the job | 200; the raw NIC is **not** in the file |

This is the documented masking policy (`docs/security/masking-matrix.md`). It shows, however, that on bank-shared templates the raw working-screen view extends to colleagues' rows: see exception X-03. The full per-role view of personal data is in MO-018 §5.

## 6. Account review: users and privileged accounts

| Population | How it is listed in the product | Live check |
|---|---|---|
| A bank's users (all roles) | *Administration → Users* (`/admin/users`); `GET /api/admin/users` (audited `SENSITIVE_DATA_VIEWED`) | Bank B's list held 2 users and none of bank A's (scenario 15) |
| Users with last login (gate B10) | `GET /api/admin/users/export` → `users-{bankCode}-{date}.csv` (audited `USER_LIST_EXPORTED`, `privileged-change` alert) | Header `Email,DisplayName,Role,Disabled,LastLoginAt,MfaEnabled,AuthProvider,CreatedDate` (scenario 38) |
| Quarterly recertification worksheet | `GET /api/admin/users/access-review` (audited `ACCESS_REVIEW_GENERATED`) | 7 rows with `role`, `disabled`, `mfaEnabled`, `lastLoginAt`, `daysSinceLastLogin`, `inactive90d` (scenario 39) |
| Platform staff (`PLATFORM_ADMIN`) | The same screens inside the platform bank | Two bootstrap accounts; never fewer than two active (`src/main/java/com/modocument/application/security/RoleAssignmentPolicy.java`) |
| Bootstrap accounts | First bank ADMIN (plus an optional second) from `BootstrapAdminRunner`; two platform admins from `BootstrapPlatformRunner`. Created once, with a temporary password, and audited `BOOTSTRAP_ADMIN_CREATED` with a `privileged-change` alert | `src/main/java/com/modocument/infrastructure/bootstrap/BootstrapAdminRunner.java`, `src/main/java/com/modocument/infrastructure/bootstrap/BootstrapPlatformRunner.java` |
| Break-glass | `BANK_ADMIN_ADD` (platform maker/checker); local password login when an IdP is down. Procedure written for this pack | `docs/compliance/policies/privileged-access-and-break-glass.md` |
| Machine identities | `GET /api/admin/integrations/api-clients`, `GET /api/admin/integrations/agents` | One API client created and used (§4) |
| Database and cloud administrators | Outside the product | `NOT IN REPO (live cloud)`; owners: `TO CONFIRM (organisational)` |

**Current accounts.** No production environment exists, so there is no live population to review. The workbooks record, as an owner statement, two administrators (Bhavish Nobeen and Rushal) across AWS, Azure and MoDocument, using their everyday accounts. `TO CONFIRM (organisational)`. Their grant record and the separation of admin identities are tracked in SEC-017 (`docs/compliance/controls/SEC-017.md`).

## 7. How access is granted, changed and removed (JML) and how clients are kept apart

**Joiners, movers and leavers.** The procedure is `docs/security/jml-runbook.md`, updated by this pack for bank onboarding, platform staff and machine identities.

| Step | In the product | Live result |
|---|---|---|
| Grant (joiner) | ADMIN requests `POST /api/admin/users` → 202. A different ADMIN or APPROVER approves. The account has a temporary password, and MFA for ADMINs | 3 users and 2 JML test users created this way. Bank B created by `BANK_CREATE` with 2 ADMINs |
| Change (mover) | `PUT /api/admin/users/{id}/role` → 202. Applies only on a second person's approval; the old tokens die | Token 200 while pending, 401 after approval; new sign-in carries READ_WRITE (scenario 36) |
| Remove (leaver) | `PUT /api/admin/users/{id}/disabled` → 202. Second approval. Disabled, not deleted | Old token 401, password login 401 right after approval (scenario 37) |
| Guards | No self-approval, no change of one's own role, no `PLATFORM_ADMIN` in a customer bank | 409 / 409 / 400 (scenarios 19, 21, 22) |

**Client data separation.**

1. **Bank (tenant).**
   - Every tenant table carries `BankId` and is under row-level security for `modocument_app`. `RlsPolicyCoveragePgIT` and `NonTenantTableInventoryPgIT` (`src/test/java/com/modocument/pgit/`) pin the coverage, and `TenantIsolationGatePgIT` proves isolation (all passed).
   - `AppUsers` is deliberately outside RLS, because sign-in needs it before a tenant is known. User administration therefore filters by the caller's bank in the application. The probe confirmed that bank B sees none of bank A's users and cannot target them (404).
2. **Owner.** Inside a bank, private templates, jobs and documents are visible only to their owner. Even bank A's ADMIN got 404 on a READ_WRITE user's job and template (scenarios 7–9).
3. **Template visibility.** A template is PRIVATE unless an ADMIN (`template:publish`) shares it with its bank. Sharing never crosses banks: bank B got 404 on bank A's shared template (scenario 3).
4. **Platform.** `PLATFORM_ADMIN` has no data permission (403). Its cross-bank views are counts only.

Live cross-bank results: bank B's ADMIN got **404** on all of the following in bank A:

- private template and bank-shared template;
- ingestion job, its export, its validation rules;
- document, and an attempt to release it;
- the shared template's records;
- a role change of a bank A user.

## 8. Exceptions and remediation

| # | Exception | Severity | Type | Remediation | Owner | Proposed date |
|---|---|---|---|---|---|---|
| X-01 | On routes with a validated JSON body, request validation (`@Valid`) runs before `@RequiresPermission`. An unauthorised caller who sends a malformed body gets **400 instead of 403**: READ_ONLY with an empty body on `PUT /api/admin/settings` (scenario 17). Nothing is executed and no data is returned. But the call never reaches the permission check, so it is not counted as an authorisation denial | Low | Build | Check the permission before binding the body: a URL-level authorisation rule for the admin and platform route prefixes, or validation inside the use case. Add a test that a denied role gets 403 whatever the body | Software Engineering — Bhavish Nobeen | 2026-11-30 (proposed) |
| X-02 | `APPROVER` holds `change:approve`, so it can approve user creation (including a new ADMIN) and role changes. Yet MFA is required only for ADMIN and PLATFORM_ADMIN, unless the bank turns on `security.mfa.requireForAllRoles` (default false, `docs/settings/catalogue.md`) | Medium | Config | Require MFA for APPROVER in code, or set `security.mfa.requireForAllRoles=true` as the onboarding default for every bank | Security — Bhavish Nobeen | 2026-11-30 (proposed) |
| X-03 | Raw working-screen values (SCREEN_WORKING) now cover the **records of bank-shared templates**. ADMIN, APPROVER and READ_WRITE colleagues see each other's raw validated personal data (scenarios 29–31). The justification in `docs/security/masking-matrix.md` (jobs are strictly owner-scoped) predates the records feature | Medium | Design / DPO | DPO decision: either mask colleagues' records by default (set `privacy.masking.screenWorking` per bank, or mask records the caller does not own), or accept it with a documented purpose. Update `docs/security/masking-matrix.md` | The DPO with Bhavish Nobeen | 2026-11-14 (proposed) |
| X-04 | Break-glass `BANK_ADMIN_ADD`: the platform maker chooses the new ADMIN's temporary password, so MO Intelligence staff can create a way into a client's data. The action is dual-controlled, alerted and audited in the client bank | Medium | Design | Procedure written (`docs/compliance/policies/privileged-access-and-break-glass.md`: bank's written request, password given only to the named bank officer, bank confirms first sign-in). Product option: deliver the temporary password to the bank, not to the maker | Security — Bhavish Nobeen | 2026-11-30 (proposed) |
| X-05 | The `admin-login` alert rule lists `actor-roles: [ADMIN]` (`src/main/resources/application.yml`). `PLATFORM_ADMIN` sign-ins do not raise it | Low | Config | Add `PLATFORM_ADMIN` to the rule's `actor-roles` | Software Engineering — Bhavish Nobeen | 2026-11-14 (proposed) |
| X-06 | Data-subject export (raw personal data) and erasure (irreversible) are single-ADMIN actions, not maker/checker. They are audited and alerted (`privileged-change`) | Low | Design / DPO | DPO decision on putting DSR export and erasure under dual control | The DPO | 2026-11-14 (proposed) |
| X-07 | `docs/security/maker-checker.md` §"Bank onboarding needs two admins" says no bank-creation endpoint exists. `BANK_CREATE` exists and enforces the rule. `docs/security/jml-runbook.md` said `user:admin` was ADMIN-only (corrected by this pack) | Low | Doc | Correct `docs/security/maker-checker.md` | Software Engineering — Bhavish Nobeen | 2026-11-14 (proposed) |
| X-08 | Organisational: admins use their everyday accounts; no grant record exists for the two administrators; the shared pre-production account must be retired | Medium | Org | SEC-017 and PRIV-017 actions | Security — Bhavish Nobeen | 2026-10-30 (workbook) |
| X-09 | No time-bound elevation of human roles; no quarterly recertification held yet | Medium | Build / Org | SEC-013 actions | Security — Bhavish Nobeen | 2026-11-30 (proposed) |

No exception lets a role act beyond the matrix, or lets one bank reach another bank's data.

## 9. Review and approval

| Role | Name | Decision | Date |
|---|---|---|---|
| Review | Rushal | `TO CONFIRM (organisational)` | |
| Privacy review (MO-056) | The DPO | `TO CONFIRM (organisational)`: decisions on X-03 and X-06 | |
| Approval | Reezvi Pydiah | `TO CONFIRM (organisational)` | |

Re-run this report after any change to `RolePermissions`, `PermissionChecker` or the masking policy. Command: `SKIP_BUILD=1 E2E_RUN_CMD='node scripts/compliance/rbac-probe.mjs' e2e/run-local.sh`.

## To confirm

- `TO CONFIRM (organisational)`: the current administrator accounts (owner statement: Bhavish Nobeen and Rushal), and the retirement of the shared team account.
- `TO CONFIRM (organisational)`: the DPO's decisions on X-03 and X-06; Rushal's review; Reezvi Pydiah's approval.
- `NOT IN REPO (live cloud)`: AWS, Azure and RDS privileged accounts, and their sessions in a deployed environment.
