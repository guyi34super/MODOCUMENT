# PRIV-017 — Details of data access management control

| Workbook | Area | Priority | Status (repo-verified) | Evidence ID | Test ID | Risk ID |
|---|---|---|---|---|---|---|
| 03 Software Engineering | Software Engineering | P1 | Partially Implemented | PRIV-017-E01 | PRIV-017-T01 | R-PRIV-017 |

## Requirement
Operate a complete joiner–mover–leaver (JML) access lifecycle on top of the RBAC engine. A leaver's account must be fully inert the same day.

## How MoDocument meets it today
Everything the workbook planned to build is in the repository: individual logins, a user list for each bank with a last-login export, and onboarding that requires two bank administrators. What is missing is operation: the product is not deployed yet.

- **Joiner.**
  - A bank ADMIN requests the account (`POST /api/admin/users`). A different ADMIN or APPROVER approves it.
  - The account is created with a temporary password that must be changed at first sign-in.
  - Users of a bank that signs in through OIDC are created on their first SSO login (`docs/security/jml-runbook.md`).
  - Evidence rows: `CHANGE_REQUESTED`, `CHANGE_APPROVED`, `USER_CREATED`.
- **Mover.**
  - A role change is a pending change, and it applies only when a second admin approves it.
  - On approval, every outstanding token of the person dies at once and refresh tokens are revoked (`src/main/java/com/modocument/infrastructure/security/RevocationValidator.java`).
  - The new role *replaces* the old one; there are never two (`src/test/java/com/modocument/application/security/SodPolicyTest.java`).
- **Leaver.**
  - Disabling an account is a pending change. Once it is approved, the old token, refresh, password login, SSO login and MFA steps are all refused.
  - Accounts are disabled, not deleted, so the audit trail keeps their identity.
- **Onboarding needs two bank admins.** `BANK_CREATE` requires at least one ADMIN and at least two ADMIN/APPROVER users (`docs/platform/platform-owner.md`), so a new bank can approve its own changes from day one.
- **Access reviews.**
  - `GET /api/admin/users/export` returns `users-{bankCode}-{date}.csv` with `LastLoginAt` and `MfaEnabled` columns.
  - `GET /api/admin/users/access-review` gives the quarterly worksheet with an `inactive90d` flag.
  - Both are audited.
- **Drift.** Role permissions cannot drift at runtime. They are code, pinned by `RolePermissionMatrixTest` and `RoutePermissionCoverageTest`. The only state that can drift is who holds which role, and the quarterly review covers it. The nightly `SodScanJob` (`src/main/java/com/modocument/infrastructure/sod/SodScanJob.java`) re-checks the separation-of-duties invariants. There is no separate nightly job that reports who holds which role (see Gap).

**Policy document:** `docs/security/jml-runbook.md`, updated by this pack with bank onboarding, platform staff and machine identities.

## Questionnaire answer (final)
Each bank's administrator manages the bank's own users in MoDocument, and every joiner, role change and leaver needs the approval of a second administrator or approver.

- **New users** must change their temporary password at first sign-in. Administrators must also enrol multi-factor authentication.
- **A role change** replaces the old role and ends the person's sessions immediately.
- **A leaver's account** is unusable the moment the disable is approved. Existing sessions, password login and single sign-on are all refused.
- **New banks** are onboarded with at least two administrators.
- **Access reviews:** administrators can export the user list with last-login dates and produce a quarterly review worksheet that flags accounts unused for 90 days.
- **MO Intelligence staff** follow the same process inside a separate platform tenant that has no access to client data.

We verified the leaver and role-change behaviour on a running system. The first quarterly review will take place after go-live. Evidence PRIV-017-E01.

## Evidence PRIV-017-E01
| Item | Location | Kind (repo / run / to confirm) |
|---|---|---|
| JML runbook | `docs/security/jml-runbook.md` | repo |
| Leaver and mover tests | `src/test/java/com/modocument/api/UserAdminIntegrationTest.java` (`disabledLeaverIsInertImmediatelyAfterApprovalAndTheEvidenceIsAudited`, `approvedRoleChangeKillsTheTargetsOutstandingTokensImmediately`, `exportIsBankScopedCsvWithFormulaNeutralizationAndLastLogins`, `accessReviewFlagsNinetyDayInactiveAccounts`), run in `docs/compliance/evidence/A4-test-runs.md` | run |
| Live JML scenarios | `docs/compliance/evidence/A4-rbac-probe.md` scenarios 36–39 | run |
| Completed quarterly review | none (pre-production) | to confirm |
| Drift report job | Not built as a separate job. Permissions are code-pinned; `SodScanJob` covers SoD | repo |

## Test PRIV-017-T01
- **Procedure:** on the live stack (`e2e/run-local.sh`):
  1. Create a READ_WRITE user through maker/checker and sign them in.
  2. An ADMIN requests a disable. A second admin approves it.
  3. Call the API with the leaver's existing token, then try a fresh password login.
  4. Repeat for a mover, from READ_ONLY to READ_WRITE.
- **Expected result:** a leaver test account is fully inert the same day.
- **Actual result:**
  - Leaver: the existing token got 401 and the password login got 401, straight after approval.
  - Mover: the token kept working (200) while the change was pending, got 401 after approval, and a new sign-in carried the role READ_WRITE.
  - The user-list CSV and the access-review worksheet (7 accounts) were produced.
- **Result:** Pass · tester: Agent A4 (automated re-performance); human sign-off required · date: 2026-10-19

## Gap and remediation
- **Gap:**
  - The process has not yet operated: no completed quarterly review and no ticket trail for joiners.
  - The shared pre-production team account must be deleted (organisational).
  - The undertaking signed in the joiner flow depends on PRIV-021.
  - No nightly report of who holds which role (optional, since permissions are code-pinned).
- **Gap type:** Org.
- **Action:**
  - Delete the shared account.
  - Link each joiner request to a ticket.
  - Hold the first quarterly access review at go-live with the worksheet and CSV.
  - Decide whether a nightly role-holder report is needed.
- **Owner:** Software Engineering — Bhavish Nobeen.
- **Target date:** 2026-10-21 (workbook) for the shared account. First quarterly review: `TO CONFIRM (organisational)` (after go-live).
- **Roadmap phase:** Day 30.

## Risk R-PRIV-017
- **Risk:** ex-staff or over-privileged accounts keep access to bank data.
- **Cause:** leavers are disabled in the IdP, but database accounts or API keys survive.
- **Mitigation:**
  - The disable is immediate and dual-controlled.
  - Bank suspension signs everyone out.
  - API clients and agents are revoked directly.
  - The application connects to the database with a single application role, so there are no personal database accounts.

Likelihood, impact and residual are set in MO-031.

## To confirm
- `TO CONFIRM (organisational)`: the shared team account is deleted.
- `TO CONFIRM (organisational)`: the ticketing used for joiner requests, and the first quarterly review date.
- `TO CONFIRM (organisational)`: the PRIV-021 signed undertaking is in place before access is granted.
