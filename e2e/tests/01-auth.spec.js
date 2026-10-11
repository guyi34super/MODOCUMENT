// Smoke: sign-in, forced MFA enrolment, maker/checker user creation and the
// forced first-login password change — all against main's existing features.
import { test, expect, sessionFromPage } from '../lib/fixtures.js';
import { getAccount, jwtExp, saveAccount } from '../lib/accounts.js';
import { ApiClient, approveChange, strongPassword, submitUserCreate } from '../lib/api.js';
import { nextCode } from '../lib/totp.js';
import { BOOTSTRAP, uniqueName } from '../lib/seed.js';

test.describe.configure({ mode: 'serial' });

/** Password + TOTP code step for an account that already enrolled. */
async function signInWithCode(page, email) {
  const account = getAccount(email);
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(account.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Two-factor authentication' })).toBeVisible();
  const { code, counter } = await nextCode(account.totpSecret, account.lastCounter);
  await page.getByLabel('Verification code').fill(code);
  await page.getByRole('button', { name: 'Verify' }).click();
  saveAccount(email, { lastCounter: counter });
}

test.describe('authentication @smoke', () => {
  test('SMK-AUTH-01 bootstrap admin signs in, enrols TOTP and must replace the bootstrap password', async ({
    page,
  }) => {
    const email = BOOTSTRAP();
    const account = getAccount(email);

    await page.goto('/login');
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill(account.password);
    await page.getByRole('button', { name: 'Sign in' }).click();

    if (account.totpSecret) {
      // Re-run against a database where the admin already enrolled: plain code step.
      test.info().annotations.push({ type: 'note', description: 'already enrolled; code step only' });
      await expect(page.getByRole('heading', { name: 'Two-factor authentication' })).toBeVisible();
      const { code, counter } = await nextCode(account.totpSecret, account.lastCounter);
      await page.getByLabel('Verification code').fill(code);
      await page.getByRole('button', { name: 'Verify' }).click();
      saveAccount(email, { lastCounter: counter });
    } else {
      // ADMIN without MFA: enrolment is mandatory (require-for-admin, gate B3).
      await expect(page.getByRole('heading', { name: 'Set up two-factor authentication' })).toBeVisible();
      const secret = (await page.locator('p:text-is("Secret") + div code').innerText()).trim();
      expect(secret).toMatch(/^[A-Z2-7]{16,}$/);
      const otpauth = await page.locator('p:text-is("otpauth URI") + div code').innerText();
      expect(otpauth).toContain(`secret=${secret}`);
      expect(otpauth).toContain('algorithm=SHA1&digits=6&period=30');
      await expect(page.locator('p:text-is("Recovery codes") + ul li')).toHaveCount(10);
      await expect(page.getByAltText('QR code for authenticator app')).toBeVisible();
      saveAccount(email, { totpSecret: secret, lastCounter: null });

      await page.getByRole('button', { name: 'I saved my recovery codes' }).click();
      await expect(page.getByRole('heading', { name: 'Confirm your authenticator' })).toBeVisible();
      const { code, counter } = await nextCode(secret, null);
      await page.getByLabel('Verification code').fill(code);
      await page.getByRole('button', { name: 'Activate and sign in' }).click();
      saveAccount(email, { lastCounter: counter });
    }

    // Phase 2 (OBS-02): the bootstrap password comes from deployment
    // configuration, so it is temporary like an admin-issued one. After the
    // second factor, nothing but the password change is reachable.
    if (await page.waitForURL(/\/force-password-change$/, { timeout: 15_000 }).then(() => true, () => false)) {
      await expect(page.getByRole('heading', { name: 'Choose your own password' })).toBeVisible();
      const { token: changeToken } = await sessionFromPage(page);
      expect(changeToken).toBeTruthy();
      const blocked = await new ApiClient({ token: changeToken }).request('GET', '/api/admin/users');
      expect(blocked.status).toBe(403);
      expect(blocked.body.code).toBe('PASSWORD_CHANGE_REQUIRED');

      const newPassword = strongPassword();
      await page.getByLabel('Temporary password').fill(getAccount(email).password);
      await page.getByLabel('New password').fill(newPassword);
      await page.getByRole('button', { name: 'Change password' }).click();
      await expect(page).toHaveURL(/\/login$/);
      // Book the new password before any further assertion: every later
      // spec signs in (and clones users) with it.
      saveAccount(email, { password: newPassword, passwordChangedAt: new Date().toISOString() });
      // The confirmation toast, and the inline login-page notice (BUG-04,
      // fixed in Wave 4; regression test in 04-known-bugs.spec.js).
      await expect(page.getByText('Password changed — sign in with your new password')).toBeVisible();
      await expect(
        page.getByRole('status').filter({ hasText: 'Password changed — sign in again' })
      ).toBeVisible();

      // The env-provided password is dead; the new one plus TOTP opens a session.
      const stale = await new ApiClient().request('POST', '/api/auth/login', {
        json: { email, password: account.password },
      });
      expect(stale.status).toBe(401);
      await signInWithCode(page, email);
    } else {
      test.info().annotations.push({ type: 'note', description: 'bootstrap password already replaced' });
    }

    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('button', { name: 'Log out' })).toBeVisible();
    const nav = page.getByRole('complementary', { name: 'Main navigation' });
    await expect(nav.getByRole('link', { name: 'Users' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Approvals' })).toBeVisible();

    // Keep the UI session for the API helpers (saves a TOTP step).
    const session = await sessionFromPage(page);
    expect(session.user.role).toBe('ADMIN');
    saveAccount(email, { token: session.token, tokenExp: jwtExp(session.token), user: session.user });

    const api = new ApiClient({ token: session.token });
    expect(await api.get('/api/auth/mfa')).toEqual({ enrolled: true, enabled: true });
  });

  test('SMK-AUTH-02 a wrong TOTP code is refused and does not sign in', async ({ page }) => {
    const email = BOOTSTRAP();
    const account = getAccount(email);
    await page.goto('/login');
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill(account.password);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('heading', { name: 'Two-factor authentication' })).toBeVisible();
    // Off-by-one digit of a real code: well-formed, wrong.
    const { code } = await nextCode(account.totpSecret, account.lastCounter);
    const wrong = code.slice(0, 5) + String((Number(code[5]) + 1) % 10);
    await page.getByLabel('Verification code').fill(wrong);
    await page.getByRole('button', { name: 'Verify' }).click();
    await expect(page.getByText('Invalid verification code.')).toBeVisible();
    await expect(page).toHaveURL(/\/login$/);
    expect(await page.evaluate(() => localStorage.getItem('modoc.token'))).toBeNull();
  });

  let created;

  test('SMK-AUTH-03 user creation needs a second admin: self-approval 409, other admin applies', async ({
    adminApi,
    checkerApi,
  }) => {
    const email = `${uniqueName('e2e-rw')}@e2e.modocument.test`.toLowerCase();
    const temporaryPassword = strongPassword();
    const change = await submitUserCreate(adminApi, {
      email,
      displayName: 'E2E Read Write',
      role: 'READ_WRITE',
      temporaryPassword,
    });
    expect(change.changeType).toBe('USER_CREATE');
    expect(change.status).toBe('REQUESTED');
    // The queue never shows credential material.
    expect(JSON.stringify(change.payload)).not.toContain(temporaryPassword);
    expect(JSON.stringify(change.payload).toLowerCase()).not.toContain('passwordhash');

    // Nothing applied yet.
    const before = await adminApi.get('/api/admin/users');
    expect(before.map((u) => u.email)).not.toContain(email);

    // Maker approving their own change: hard 409 (gate B7).
    const self = await adminApi.request('POST', `/api/admin/changes/${change.pendingChangeId}/approve`, {
      json: {},
    });
    expect(self.status).toBe(409);

    const applied = await approveChange(checkerApi, change.pendingChangeId);
    expect(applied.status).toBe('APPLIED');
    const after = await adminApi.get('/api/admin/users');
    const user = after.find((u) => u.email === email);
    expect(user).toMatchObject({ role: 'READ_WRITE', disabled: false, mfaEnabled: false });

    saveAccount(email, { password: temporaryPassword, role: 'READ_WRITE', origin: 'maker-checker' });
    created = { email, temporaryPassword };
  });

  test('SMK-AUTH-04 first sign-in with a temporary password forces a password change', async ({ page }) => {
    test.skip(!created, 'needs the user from SMK-AUTH-03');
    const { email, temporaryPassword } = created;

    await page.goto('/login');
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill(temporaryPassword);
    await page.getByRole('button', { name: 'Sign in' }).click();

    await expect(page).toHaveURL(/\/force-password-change$/);
    await expect(page.getByRole('heading', { name: 'Choose your own password' })).toBeVisible();

    // While the flag is set, every non-self-service route is refused.
    const { token } = await sessionFromPage(page);
    const blocked = await new ApiClient({ token }).request('GET', '/api/ingestion/jobs');
    expect(blocked.status).toBe(403);
    expect(blocked.body.code).toBe('PASSWORD_CHANGE_REQUIRED');

    const newPassword = strongPassword();
    await page.getByLabel('Temporary password').fill(temporaryPassword);
    await page.getByLabel('New password').fill(newPassword);
    await page.getByRole('button', { name: 'Change password' }).click();
    // Back on the login page. (`?expired=1` + a "session expired" banner
    // is BUG-02 — asserted separately in 04-known-bugs.spec.js.)
    await expect(page).toHaveURL(/\/login(\?expired=1)?$/);
    saveAccount(email, { password: newPassword, token: null, tokenExp: null });

    // The temporary password is dead; the new one works.
    const stale = await new ApiClient().request('POST', '/api/auth/login', {
      json: { email, password: temporaryPassword },
    });
    expect(stale.status).toBe(401);

    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill(newPassword);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('button', { name: 'Log out' })).toBeVisible();
    // A READ_WRITE user gets no Administration section.
    await expect(
      page.getByRole('complementary', { name: 'Main navigation' }).getByRole('link', { name: 'Users' })
    ).toHaveCount(0);
  });
});
