// Phase 2 — platform owner: platform bank, onboarding, suspension (SE2).
// Acceptance criteria: docs/phase2/test-plan.md §5.2.
//
// Seeding the specs will need (SE2): the two bootstrap PLATFORM_ADMIN
// accounts' credentials via env (MODOCUMENT_BOOTSTRAP_PLATFORM_ADMIN_* or the
// names SE2 picks) — run-local.sh/compose generate them per run and export
// them as E2E_PLATFORM_ADMIN_{1,2}_{EMAIL,PASSWORD}.
// UI contract: /platform (overview table, role=table name="Banks"),
// /platform/banks with role=button name="Onboard bank" opening a dialog
// (role=dialog name="Onboard bank") with two admin rows; bank detail with
// role=button name="Suspend bank" / "Reactivate bank".
import { test } from '../../lib/fixtures.js';

test.describe('platform owner @phase2 @wave2', () => {
  test.fixme('AC-PLT-01 bootstrap creates the platform bank and two platform admins (forced change + MFA)', async () => {});

  test.fixme('AC-PLT-03 a platform admin sees only the Platform console and no customer data', async () => {
    // nav shows only Platform; "/" redirects to /platform; GET /Templates/GetAll and
    // /api/ingestion/jobs -> 403; a bank admin on /api/platform/overview -> 403.
  });

  test.fixme('AC-PLT-05 onboarding a bank needs two admins and a second platform approver', async () => {
    // POST /api/platform/banks with 1 user -> 400; with 2 (>=1 ADMIN) -> 202 BANK_CREATE;
    // self-approve -> 409; second platform admin approves -> bank listed ACTIVE;
    // the new bank admin signs in: forced password change + TOTP enrolment.
  });

  test.fixme('AC-PLT-06 suspending a bank kills live sessions and refuses login; reactivation restores it', async () => {
    // bank admin token valid -> BANK_SUSPEND approved -> same token 401, login 401/403;
    // reactivate (direct) -> login works again.
  });

  test.fixme('AC-PLT-09 the platform overview shows counts only (no customer rows)', async () => {
    // GET /api/platform/overview -> BankSummary fields only; no template/job names leak.
  });

  test.fixme('AC-PLT-10 platform console pages: overview, banks, bank detail, settings, announcements, releases', async () => {});
});
