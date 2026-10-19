# Privileged access, break-glass and monthly review procedure

| Owner | Approver | Controls | Status | Version date |
|---|---|---|---|---|
| Security — Bhavish Nobeen | Reezvi Pydiah (`TO CONFIRM (organisational)`: approval not yet given) | SEC-017, SEC-013, PRIV-017 | Draft for approval | 2026-10-19 |

This procedure answers the SEC-017 evidence request: a **privileged-account inventory**, the **break-glass procedure** and the **monthly review record**. It covers the MoDocument application, written from the repository, and the cloud and database accounts around it, which the repository can only partly prove.

MoDocument is pre-production. The workbooks record, as an owner statement of 2 October 2026, that nothing is deployed and no bank data has been received. So no production account exists yet, and the first real inventory and review will follow the first deployment. `TO CONFIRM (organisational)`

## 1. What counts as privileged

| Class | Where | What makes it privileged | How it is enumerated |
|---|---|---|---|
| P1 Platform admin | MoDocument, platform bank | `PLATFORM_ADMIN`: onboards and suspends banks, sets platform defaults, adds a break-glass ADMIN to a customer bank (`src/main/java/com/modocument/application/security/RolePermissions.java`) | *Administration → Users* inside the platform bank, or `GET /api/admin/users` and `GET /api/admin/users/export` as a platform admin |
| P2 Bank administrator | MoDocument, each customer bank | `ADMIN`: user administration, settings, integrations, data-subject requests | `GET /api/admin/users/access-review` (role column) and `GET /api/admin/users/export` in that bank |
| P3 Bank checker | MoDocument, each customer bank | `APPROVER`: approves user, role and template changes (`change:approve`) and releases documents (`doc:release`) | Same as P2 |
| P4 Machine identity | MoDocument, each customer bank | API clients and connector agents: move data in and out of the bank | `GET /api/admin/integrations/api-clients`, `GET /api/admin/integrations/agents` |
| P5 Database owner | PostgreSQL / RDS | The Flyway owner and the RDS master user bypass row-level security; the runtime role `modocument_app` does not (`src/main/resources/db/migration/V12__app_role_grants.sql`) | RDS master password is generated and held by AWS Secrets Manager (`manage_master_user_password = true` in `infra/terraform/modules/rds/main.tf`). Live list: `NOT IN REPO (live cloud)` |
| P6 Cloud administrator | AWS (and Azure, per the workbook) | Root / global admin and IAM administrators | `NOT IN REPO (live cloud)`. CI deploys through a short-lived OIDC role (`infra/terraform/modules/ci-oidc/main.tf`), so CI holds no stored cloud key |

## 2. Inventory record

Each privileged account must map to a ticket that records who asked, who approved and why (SEC-017 acceptance).

| # | Class | Account (person) | Granted by / ticket | Approved by | MFA | Last reviewed |
|---|---|---|---|---|---|---|
| 1 | P6 | AWS / Azure administrator: Bhavish Nobeen (owner statement, 2 Oct 2026) | `TO CONFIRM (organisational)` | `TO CONFIRM (organisational)`: grant record to be backfilled and approved by Reezvi Pydiah | `TO CONFIRM (organisational)` | not yet |
| 2 | P6 | AWS / Azure administrator: Rushal (owner statement, 2 Oct 2026) | `TO CONFIRM (organisational)` | `TO CONFIRM (organisational)` | `TO CONFIRM (organisational)` | not yet |
| 3 | P6 | Root / global admin credentials, sealed in the password manager with an alert on use (owner statement) | n/a (break-glass) | `TO CONFIRM (organisational)` | `TO CONFIRM (organisational)` | not yet |
| 4–5 | P1 | The two bootstrap `PLATFORM_ADMIN` accounts, created once from `MODOCUMENT_PLATFORM_ADMIN_EMAILS` (`src/main/java/com/modocument/infrastructure/bootstrap/BootstrapPlatformRunner.java`) | Deployment ticket `TO CONFIRM (organisational)` | `TO CONFIRM (organisational)` | Always enforced by `AuthUseCase.mustEnrolMfa` (`src/main/java/com/modocument/application/usecase/AuthUseCase.java`) | not deployed |
| 6 | P5 | RDS master user | Terraform | n/a | n/a (Secrets Manager) | `NOT IN REPO (live cloud)` |

The workbook's gap remains: the two administrators use their everyday accounts for admin work. Separate admin identities through role elevation are planned (SEC-017, owner Bhavish Nobeen). The target of 30 October 2026 is not yet due. `TO CONFIRM (organisational)`

## 3. Granting, changing and removing privileged access

**In MoDocument**, privileged roles are granted only through the product's dual-control flows. These are covered by tests and by the A4 live probe (`docs/compliance/evidence/A4-rbac-probe.md`):

1. Raise a ticket that names the person, the role and the reason. `TO CONFIRM (organisational)`: ticket system.
2. A bank ADMIN requests the user or role change (`POST /api/admin/users`, `PUT /api/admin/users/{id}/role`). A **different** ADMIN or APPROVER approves it. Self-approval returns 409 (`src/main/java/com/modocument/application/security/SodPolicy.java`). For a `PLATFORM_ADMIN`, a second platform admin approves. A customer bank can never hand out `PLATFORM_ADMIN` (`src/main/java/com/modocument/application/security/RoleAssignmentPolicy.java`, backed by the V210 trigger in `src/main/resources/db/migration/V210__ws21_platform_bank.sql`).
3. Record the pending-change id in the ticket. The audit rows `CHANGE_REQUESTED`, `CHANGE_APPROVED` and `USER_ROLE_CHANGED` are the evidence. The `privileged-change` alert fires on `USER_ROLE_CHANGED` (`src/main/resources/application.yml`).
4. Removal: disable or demote through the same flow (see `docs/security/jml-runbook.md`). The old token stops working on its next request.

**For cloud and database accounts**, grants follow the same ticket and approval rule. Execution happens in AWS or Azure. `NOT IN REPO (live cloud)`

## 4. Break-glass procedure

Break-glass means using an access path that normal operation does not use. Each use is an incident record: write down who, when, why and what was done, then review it at the next monthly review.

| Situation | Path | Who | Steps | Evidence |
|---|---|---|---|---|
| A customer bank has no working ADMIN (left, locked, lost MFA) | `BANK_ADMIN_ADD`: `POST /api/platform/banks/{id}/admins` | Platform admin A requests, platform admin B approves | 1. Obtain the bank's written request from a named bank officer (`TO CONFIRM (organisational)`: channel). 2. Platform admin A submits the request with a policy-compliant temporary password and the ticket reference as `reason`. 3. Platform admin B checks the request against the ticket and approves it. 4. Send the temporary password to the named bank officer over a channel separate from the e-mail. The new ADMIN must change it and enrol TOTP at first sign-in. 5. Within one working day, the bank confirms the new ADMIN's first sign-in. | `BANK_ADMIN_ADDED` in the customer bank and the platform bank; `platform-change` alert (HIGH); `CHANGE_REQUESTED` / `CHANGE_APPROVED` |
| A bank ADMIN has lost the authenticator | `USER_MFA_RESET` by another ADMIN of the bank (maker/checker) | Bank admins | As in `docs/security/jml-runbook.md` (helpdesk actions) | `USER_MFA_RESET`, `privileged-change` alert |
| The bank's identity provider (OIDC) is down | Local password login stays available (`docs/security/identity-providers.md`) | Bank users with a local account | Use the local account; record the outage | `LOGIN_SUCCESS` rows |
| A platform admin is unavailable and two are needed | None in the product: two active platform admins are always required (`MIN_ACTIVE_PLATFORM_ADMINS` in `RoleAssignmentPolicy.java`) | — | Keep at least two named platform admins at all times, each the other's deputy | — |
| The cloud root / global admin is needed | Sealed credential in the password manager | Two named administrators | Owner statement: sealed with MFA and an alert on use. `TO CONFIRM (organisational)` | `NOT IN REPO (live cloud)`. CloudTrail is defined in `infra/terraform/modules/security-monitoring/main.tf`, which is not applied yet |
| Direct database access is needed (data repair) | RDS master user or the Flyway owner | `TO CONFIRM (organisational)` | Ticket, second-person approval, time-boxed session, password rotated afterwards | `log_statement = ddl` and connection logging in `infra/terraform/modules/rds/main.tf`. Live logs: `NOT IN REPO (live cloud)` |

**Residual risk for the DPO.** `BANK_ADMIN_ADD` is the one path by which MO Intelligence staff can create access to a customer bank's data. In this flow, platform admin A chooses the temporary password. If both platform admins acted together, one of them could sign in first as the new ADMIN. Two things make this visible. First, the action is audited in the customer bank, so its own admins see `BANK_ADMIN_ADDED`. Second, the new ADMIN's first sign-in must be confirmed by the bank (step 5).

## 5. Monthly privileged-activity review

**Who:** a platform admin who did not perform the activity under review, plus Bhavish Nobeen as Security owner. `TO CONFIRM (organisational)`

**When:** the first working week of each month.

**Steps:**

1. **Population.** Enumerate P1 to P4 with the queries in §1 and compare them with the inventory in §2. Any account without a ticket is a finding.
2. **Activity.** In each bank's audit trail (`GET /api/admin/audit`, CSV through `GET /api/admin/audit/export`), and in the platform trail (`GET /api/platform/audit`), filter the previous month for these actions:
   - `USER_ROLE_CHANGED`, `USER_CREATED`, `USER_DISABLED`;
   - `USER_PASSWORD_RESET`, `USER_MFA_RESET`, `MFA_DISABLED`;
   - `BANK_ADMIN_ADDED`, `BANK_ONBOARDED`, `BANK_SUSPENDED`;
   - `PLATFORM_SETTING_UPDATED`, `SECURITY_SETTING_UPDATED`;
   - `DSR_EXPORTED`, `DSR_ERASED`, `USER_LIST_EXPORTED`;
   - `SELF_APPROVAL_BLOCKED`, `DOC_SELF_RELEASE_BLOCKED`, `SOD_VIOLATION_FOUND`;
   - `API_CLIENT_CREATED`, `AGENT_REGISTERED`.

   The action vocabulary is `docs/security/audit-events.md`.
3. **Reconcile.** Each privileged action must map to a ticket. Each SoD refusal or violation must be explained.
4. **Dormant accounts.** In the access-review worksheet, act on `inactive90d` rows, following `docs/security/jml-runbook.md`.
5. **Cloud.** Review CloudTrail for root or administrator sign-ins. `NOT IN REPO (live cloud)`
6. **Record.** Fill one record as below and file it with the exported CSVs.

### Review record template

| Field | Value |
|---|---|
| Month reviewed | |
| Reviewer(s) | |
| Privileged accounts found (P1 / P2 / P3 / P4) | |
| Accounts without a ticket | |
| Privileged actions reviewed / without a ticket | |
| SoD refusals or violations, and their explanation | |
| Dormant accounts disabled | |
| Findings and follow-up owner | |
| Sign-off and date | |

### Record 2026-10 (dry run on the E2E stack, not a production review)

There is no production yet, so the first record is a dry run on the synthetic stack that the A4 probe built on 2026-10-19 (`docs/compliance/evidence/A4-rbac-probe.md`). It shows that the population and the activity can be enumerated with the product's own APIs. It is **not** evidence of a production review. The first production record is `TO CONFIRM (organisational)`.

| Field | Value |
|---|---|
| Month reviewed | 2026-10 (synthetic E2E stack) |
| Reviewer(s) | Agent A4 (automated). Human sign-off required |
| Privileged accounts found | 2 platform admins (P1); 2 bank ADMINs in bank A (the bootstrap admin and the harness's second admin) and 2 in bank B (P2); 1 APPROVER (P3); 1 API client (P4). Bank A's access-review worksheet listed 7 accounts |
| Accounts without a ticket | All of them (synthetic run, no ticket system) |
| Privileged actions reviewed | The probe's own maker/checker actions (USER_CREATE, role change, disable, BANK_CREATE, API_CLIENT_CREATE), each with its pending change |
| SoD refusals | `SELF_APPROVAL_BLOCKED` (bank and platform) and `DOC_SELF_RELEASE_BLOCKED`, all triggered on purpose by the probe |
| Dormant accounts disabled | None (accounts created the same day) |
| Findings | None for the product. Organisational items are listed in §2 |
| Sign-off and date | `TO CONFIRM (organisational)` |
