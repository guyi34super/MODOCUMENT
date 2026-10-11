// Phase 2 — platform owner: platform bank, onboarding, suspension (SE2).
// Acceptance criteria: docs/phase2/test-plan.md §5.2. Live since Wave 2 (ws21).
//
// Needs the two bootstrap PLATFORM_ADMIN accounts: run-local.sh / the compose
// job generate them per run (MODOCUMENT_PLATFORM_ADMIN_EMAILS/PASSWORDS) and
// export E2E_PLATFORM_ADMIN_{1,2}_{EMAIL,PASSWORD}; without them every test
// here skips.
// UI contract (as merged): /platform overview (heading "Platform overview",
// role=table name="Banks"), /platform/banks (button "Onboard bank" opening
// role=dialog "Onboard bank"), bank detail (buttons "Suspend bank" /
// "Reactivate bank"), /platform/settings, /platform/announcements,
// /platform/releases.
import { test, expect, openAs } from '../../lib/fixtures.js';
import { ApiClient, approveChange, sessionFor, strongPassword } from '../../lib/api.js';
import { getAccount } from '../../lib/accounts.js';
import { onboardBank } from '../../lib/phase2.js';
import { PLATFORM_ADMIN, uniqueName } from '../../lib/seed.js';

test.describe.configure({ mode: 'serial' });

let onboarded;

test.describe('platform owner @phase2 @wave2', () => {
  test.beforeEach(async ({ platformApi }) => {
    test.skip(!platformApi, 'the stack was started without platform admins');
  });

  test('AC-PLT-01 bootstrap creates the platform bank and two platform admins (forced change + MFA)', async ({
    platformApi,
    platformCheckerApi,
  }) => {
    // Both sessions went through the forced password change and TOTP enrolment.
    for (const [n, api] of [[1, platformApi], [2, platformCheckerApi]]) {
      const me = await api.get('/api/auth/me');
      expect(me.role).toBe('PLATFORM_ADMIN');
      expect(me.mustChangePassword ?? false).toBe(false);
      expect(await api.get('/api/auth/mfa')).toMatchObject({ enabled: true });
      expect(getAccount(PLATFORM_ADMIN(n)).passwordChangedAt, 'bootstrap password replaced').toBeTruthy();
    }
    const banks = await platformApi.get('/api/platform/banks');
    expect(banks.filter((b) => b.kind === 'PLATFORM')).toHaveLength(1);
    expect(banks.some((b) => b.code === 'DEFAULT' && b.kind === 'CUSTOMER')).toBe(true);
  });

  test('AC-PLT-03 a platform admin sees only the Platform console and no customer data', async ({
    page,
    platformApi,
    adminApi,
  }) => {
    for (const route of ['/Templates/GetAll', '/api/ingestion/jobs', '/api/admin/dashboard', '/api/admin/settings']) {
      expect((await platformApi.request('GET', route)).status, route).toBe(403);
    }
    for (const route of ['/api/platform/overview', '/api/platform/banks', '/api/platform/settings']) {
      expect((await adminApi.request('GET', route)).status, route).toBe(403);
    }

    await openAs(page, PLATFORM_ADMIN(1), '/');
    await expect(page).toHaveURL(/\/platform$/);
    const nav = page.getByRole('complementary', { name: 'Main navigation' });
    await expect(nav.getByRole('link', { name: 'Banks' })).toBeVisible();
    for (const hidden of ['Design Templates', 'Upload & Process', 'Admin dashboard']) {
      await expect(nav.getByRole('link', { name: hidden })).toHaveCount(0);
    }
  });

  test('AC-PLT-05 onboarding a bank needs two admins and a second platform approver', async ({
    platformApi,
    platformCheckerApi,
  }) => {
    const tooFew = await platformApi.request('POST', '/api/platform/banks', {
      json: {
        code: `E2EX${Date.now().toString(36).toUpperCase()}`,
        name: 'Too few users',
        users: [
          {
            email: `${uniqueName('e2e-solo')}@e2e.modocument.test`.toLowerCase(),
            displayName: 'Solo',
            role: 'ADMIN',
            temporaryPassword: strongPassword(),
          },
        ],
      },
    });
    expect(tooFew.status).toBe(400);

    // Self-approval is blocked, then the second platform admin applies it.
    const maker = {
      request: (...args) => platformApi.request(...args),
      get: (...args) => platformApi.get(...args),
    };
    const selfCheck = {
      post: async (url) => {
        const res = await platformApi.request('POST', url, { json: {} });
        expect(res.status, 'maker approving their own BANK_CREATE').toBe(409);
        return platformCheckerApi.post(url, { comment: 'e2e approval' });
      },
    };
    onboarded = await onboardBank(maker, selfCheck);
    expect(onboarded.change.changeType).toBe('BANK_CREATE');
    expect(onboarded.bank).toMatchObject({ kind: 'CUSTOMER', status: 'ACTIVE' });

    // The new bank's first admin signs in: forced password change + TOTP enrolment.
    const bankAdmin = await sessionFor(onboarded.admins[0]);
    const me = await bankAdmin.get('/api/auth/me');
    expect(me.role).toBe('ADMIN');
    expect(await bankAdmin.get('/api/auth/mfa')).toMatchObject({ enabled: true });
    // ... and sees only its own bank.
    expect(await bankAdmin.get('/Templates/GetAll')).toEqual([]);
  });

  test('AC-PLT-06 suspending a bank kills live sessions and refuses login; reactivation restores it', async ({
    platformApi,
    platformCheckerApi,
  }) => {
    test.skip(!onboarded?.bank, 'needs the bank from AC-PLT-05');
    const email = onboarded.admins[0];
    const live = await sessionFor(email);
    expect((await live.request('GET', '/api/auth/me')).status).toBe(200);

    const suspend = await platformApi.request('POST', `/api/platform/banks/${onboarded.bank.bankId}/suspend`, {
      json: { reason: 'E2E suspension' },
      expect: 202,
    });
    await approveChange(platformCheckerApi, suspend.body.pendingChangeId);

    expect((await live.request('GET', '/api/auth/me')).status).toBe(401);
    const refused = await new ApiClient().request('POST', '/api/auth/login', {
      json: { email, password: getAccount(email).password },
    });
    expect(refused.status).toBe(401);

    const reactivated = await platformApi.post(`/api/platform/banks/${onboarded.bank.bankId}/reactivate`);
    expect(reactivated.status).toBe('ACTIVE');
    const back = await new ApiClient().request('POST', '/api/auth/login', {
      json: { email, password: getAccount(email).password },
    });
    expect(back.status).toBe(200);
  });

  test('AC-PLT-09 the platform overview shows counts only (no customer rows)', async ({ platformApi }) => {
    const overview = await platformApi.get('/api/platform/overview');
    const allowed = new Set([
      'bankId', 'code', 'name', 'kind', 'status', 'users', 'mfaEnrolledPct', 'jobs7d',
      'failures7d', 'aiShare7d', 'codeShare7d', 'lastActivityAt', 'suspendedAt', 'createdAt',
    ]);
    expect(overview.banks.length).toBeGreaterThan(0);
    for (const bank of overview.banks) {
      const extra = Object.keys(bank).filter((key) => !allowed.has(key));
      expect(extra, `BankSummary carries only counts (${bank.code})`).toEqual([]);
      for (const key of ['users', 'jobs7d', 'failures7d']) {
        expect(typeof bank[key], `${bank.code}.${key}`).toBe('number');
      }
    }
    expect(JSON.stringify(overview)).not.toMatch(/templateName|fileName|@e2e\.modocument\.test/);
  });

  test('AC-PLT-10 platform console pages: overview, banks, bank detail, settings, announcements, releases', async ({
    page,
  }) => {
    await openAs(page, PLATFORM_ADMIN(1), '/platform');
    await expect(page.getByRole('heading', { name: 'Platform overview' })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Banks' })).toBeVisible();

    await page.goto('/platform/banks');
    await page.getByRole('button', { name: 'Onboard bank' }).click();
    const dialog = page.getByRole('dialog', { name: 'Onboard bank' });
    await expect(dialog.getByRole('listitem', { name: 'First user 1' })).toBeVisible();
    await expect(dialog.getByRole('listitem', { name: 'First user 2' })).toBeVisible();
    await dialog.getByRole('button', { name: 'Cancel' }).click();

    if (onboarded?.bank) {
      await page.goto(`/platform/banks/${onboarded.bank.bankId}`);
      await expect(page.getByRole('button', { name: 'Suspend bank' })).toBeVisible();
    }
    await page.goto('/platform/settings');
    await expect(page.getByRole('heading', { name: 'Platform settings' })).toBeVisible();
    await expect(page.getByText('Banks may choose').first()).toBeVisible();
    await page.goto('/platform/announcements');
    await expect(page).toHaveURL(/\/platform\/announcements$/);
    await page.goto('/platform/releases');
    await expect(page.getByTestId('app-version')).toBeVisible();
  });
});
