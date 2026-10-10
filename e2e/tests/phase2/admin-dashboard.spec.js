// Phase 2 — admin dashboard, user actions, bank-shared templates (SE3).
// Acceptance criteria: docs/phase2/test-plan.md §5.3.
//
// UI contract (SE3): /admin -> AdminDashboardPage with KPI tiles
// data-testid="kpi-<metric>" (e.g. kpi-users-active, kpi-admins-without-mfa,
// kpi-ingestion-today, kpi-parsed-by-code, kpi-pending-approvals) whose
// value is in data-testid="kpi-value"; quick actions role=button
// name="Add user" | "Invite" | "New shared template" | "Review approvals".
// AdminUsersPage row actions: role=button name="Unlock" | "Reset password" |
// "Reset MFA" | "Edit" inside the row of the user's email; ConfirmDialog =
// role=dialog with role=button name="Confirm". TemplatesPage: badge text
// "Shared" | "Private" and role=switch name="Share with bank".
import { test } from '../../lib/fixtures.js';

test.describe('admin dashboard @phase2 @wave2', () => {
  test.fixme('AC-ADM-01 dashboard KPIs match the seeded data exactly', async () => {
    // seed: 3 users (1 locked, 1 admin without MFA), 2 jobs today (1 VALIDATED, 1 FAILED),
    // 1 code-parsed job, 1 pending approval, 1 shared + 1 private template -> tiles equal.
  });

  test.fixme('AC-ADM-03 unlock clears a locked account directly (USER_UNLOCKED)', async () => {
    // 6 wrong passwords -> ACCOUNT_LOCKED -> admin clicks Unlock -> user signs in.
  });

  test.fixme('AC-ADM-04 password reset goes through approval, kills sessions, forces a change', async () => {});

  test.fixme('AC-ADM-05 MFA reset goes through approval; the admin re-enrols at next sign-in', async () => {});

  test.fixme('AC-ADM-07 a bank-shared template is usable by every user of the bank but editable only with TEMPLATE_PUBLISH', async () => {
    // admin publishes (PUT /Templates/{id}/Visibility BANK); READ_WRITE operator sees it in
    // the upload template picker and can ingest with it, but cannot edit/delete it.
  });

  test.fixme('AC-ADM-08 deleting a shared template that is in use returns 409', async () => {});

  test.fixme('AC-ADM-10 pending changes page labels and diffs every new change type', async () => {
    // e.g. "Security setting: password.min-length: 12 → 14", "Password reset: <email>".
  });
});
