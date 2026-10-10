// Regression tests for product bugs QA found end to end. An open bug's test
// FAILS until the bug is fixed (that is the point); the report and
// docs/phase2/test-plan.md §8 carry the write-up. Do not mark them
// fixme/skip to get a green run — use the documented workaround switch
// instead and keep the failure visible. BUG-01 and BUG-02 are fixed (Wave 1
// and 2); their tests stay as regression tests.
//
// Since OBS-02 the bootstrap password is temporary and SMK-AUTH-01 replaces
// it: seeded clones copy the CURRENT hash, hence bootstrapPassword().
import { test, expect } from '../lib/fixtures.js';
import { ApiClient, strongPassword } from '../lib/api.js';
import { saveAccount } from '../lib/accounts.js';
import { seedClonedUser } from '../lib/db.js';
import { env } from '../lib/env.js';
import { nextCode } from '../lib/totp.js';
import { BOOTSTRAP, bootstrapPassword, registerBootstrapAdmin, uniqueName } from '../lib/seed.js';

test.describe('known bugs @bug', () => {
  test('BUG-01 MFA verify completes a login under the RLS runtime role (no 502 on LOGIN_SUCCESS audit)', async () => {
    test.skip(
      env.appRole !== 'rls',
      'only observable when the backend runs as modocument_app (E2E_DB_APP_ROLE=rls)'
    );
    // A brand-new ADMIN (forced enrolment), so the test owns its TOTP steps.
    registerBootstrapAdmin();
    const email = `${uniqueName('e2e-bug01')}@e2e.modocument.test`.toLowerCase();
    await seedClonedUser({ email, displayName: 'BUG-01 probe', role: 'ADMIN', cloneFromEmail: BOOTSTRAP() });
    saveAccount(email, { password: bootstrapPassword(), role: 'ADMIN', origin: 'sql-seed' });

    const anon = new ApiClient();
    const login = await anon.request('POST', '/api/auth/login', {
      json: { email, password: bootstrapPassword() },
      expect: 200,
    });
    expect(login.body).toMatchObject({ mfaRequired: true, enrollmentRequired: true });
    const { challenge } = login.body;
    const enrollment = await anon.post('/api/auth/mfa/enroll', undefined, { bearer: challenge });
    const { code } = await nextCode(enrollment.secret, null);
    await anon.post('/api/auth/mfa/activate', { code }, { bearer: challenge });

    // Observed on PostgreSQL with the modocument_app role: 502
    // {"error":"The database request failed."} — "new row violates
    // row-level security policy for table auditevent". MfaUseCase.verify is
    // @Transactional and starts with no tenant bound; JpaAuditRecorder binds
    // the event's bank only around writer.append(), but the INSERT is
    // flushed at COMMIT, after the binding is gone. Every MFA sign-in (i.e.
    // every ADMIN) fails in a production-shaped deployment. H2 tests (no
    // RLS) and the PgITs (no MFA login) do not catch it.
    const verify = await anon.request('POST', '/api/auth/mfa/verify', { json: { challenge, code } });
    expect(verify.status, JSON.stringify(verify.body)).toBe(200);
    expect(verify.body.token).toBeTruthy();
    expect(verify.body.user.role).toBe('ADMIN');
  });

  test('BUG-02 after a successful forced password change the login page does not claim the session expired', async ({
    page,
  }) => {
    // Independent of BUG-01: a READ_WRITE user owes no MFA.
    registerBootstrapAdmin();
    const email = `${uniqueName('e2e-bug02')}@e2e.modocument.test`.toLowerCase();
    await seedClonedUser({ email, displayName: 'BUG-02 probe', role: 'READ_WRITE', cloneFromEmail: BOOTSTRAP() });

    await page.goto('/login');
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill(bootstrapPassword());
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/\/force-password-change$/);

    const newPassword = strongPassword();
    await page.getByLabel('Temporary password').fill(bootstrapPassword());
    await page.getByLabel('New password').fill(newPassword);
    await page.getByRole('button', { name: 'Change password' }).click();
    saveAccount(email, { password: newPassword, role: 'READ_WRITE', origin: 'sql-seed' });

    // Observed: /login?expired=1 + "Your session expired. Please sign in
    // again." right under the "Password changed" toast. The password change
    // revokes every token (TokenVersion bump), then ForceChangePasswordPage
    // calls logout() with the dead token -> 401 -> client.js takes the
    // expired-session redirect. Expected: plain /login, no expiry banner.
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
    await expect(page.getByText('Your session expired. Please sign in again.')).toHaveCount(0);
    await expect(page).toHaveURL(/\/login$/);
  });

  // fixme per the phase-2 QA rule for newly found bugs (Supervisor decides
  // when it is switched on); P4, cosmetic — the toast still confirms.
  test.fixme('BUG-04 after a forced password change the login page shows the "Password changed" notice', async ({
    page,
  }) => {
    registerBootstrapAdmin();
    const email = `${uniqueName('e2e-bug04')}@e2e.modocument.test`.toLowerCase();
    await seedClonedUser({ email, displayName: 'BUG-04 probe', role: 'READ_WRITE', cloneFromEmail: BOOTSTRAP() });

    await page.goto('/login');
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill(bootstrapPassword());
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/\/force-password-change$/);
    const newPassword = strongPassword();
    await page.getByLabel('Temporary password').fill(bootstrapPassword());
    await page.getByLabel('New password').fill(newPassword);
    await page.getByRole('button', { name: 'Change password' }).click();
    saveAccount(email, { password: newPassword, role: 'READ_WRITE', origin: 'sql-seed' });

    // Observed: plain login page, no notice. ForceChangePasswordPage awaits
    // logout({revoke:false}) — setUser(null) re-renders the route's
    // ProtectedRoute, which redirects to /login WITHOUT state — and only then
    // calls navigate('/login', {state:{notice:'password-changed'}}) from an
    // unmounted component. LoginPage reads location.state.notice, so the
    // role=status notice is never rendered.
    await expect(page.getByRole('status').filter({ hasText: 'Password changed — sign in again' })).toBeVisible();
  });
});
