// Phase 2 — editable settings with guardrails + maker/checker (SE1 backend,
// SE3 SettingsPage). Acceptance criteria: docs/phase2/test-plan.md §5.1.
// Live since Wave 2 (merged ws20 + ws22).
//
// UI contract (SE3, frontend/src/pages/SettingsPage.jsx + SettingField.jsx):
//   /admin/settings (and /platform/settings), role=tablist "Settings groups",
//   one role=tab per group label; each setting row data-testid="setting-<key>"
//   with the input labelled by the setting label, badges "Requires approval" |
//   "Pending" | "Locked" | "Clamped by platform", source in
//   data-testid="setting-source" (Bank | Platform | Default); per-tab button
//   "Save <group label>"; toast "N applied, M sent for approval".
// API contract: docs/phase2/contracts.md §3 (SE1).
import { test, expect, openAs } from '../../lib/fixtures.js';
import { approveChange, strongPassword, submitUserCreate } from '../../lib/api.js';
import {
  bankSetting,
  platformSetting,
  putBankSetting,
  resetBankSetting,
  settingsByKey,
} from '../../lib/phase2.js';
import { BOOTSTRAP, uniqueName } from '../../lib/seed.js';

const PASSWORD_MIN = 'security.password.minLength';
const RETENTION_DAYS = 'retention.customerDataDays';
const MFA_ADMINS = 'security.mfa.requireForAdmins';
const RATE_LIMITING = 'ratelimit.enabled';
const CODE_MIN_CONFIDENCE = 'extraction.code.minConfidence';
const MAX_ROWS = 'integration.maxRowsPerRun';

test.describe.configure({ mode: 'serial' });

test.describe('settings @phase2 @wave2', () => {
  test('AC-SET-01 settings catalogue lists every tab with value, source, limits and tier', async ({
    page,
    adminApi,
  }) => {
    const catalogue = await adminApi.get('/api/admin/settings');
    expect(catalogue.layer).toBe('BANK');
    expect(catalogue.groups.length).toBeGreaterThanOrEqual(5);
    const minLength = settingsByKey(catalogue)[PASSWORD_MIN];
    expect(minLength).toMatchObject({
      key: PASSWORD_MIN,
      tier: 'SECURITY',
      scope: 'BANK',
      requiresApproval: true,
      locked: false,
      hardMin: expect.anything(),
    });
    for (const field of ['value', 'source', 'min', 'max', 'hardMax', 'version', 'clamped', 'pendingChangeId']) {
      expect(minLength, `SettingView.${field}`).toHaveProperty(field);
    }

    await openAs(page, BOOTSTRAP(), '/admin/settings');
    const tabs = page.getByRole('tablist', { name: 'Settings groups' });
    for (const group of catalogue.groups) {
      await expect(tabs.getByRole('tab', { name: group.label })).toBeVisible();
    }
    const row = page.getByTestId(`setting-${PASSWORD_MIN}`);
    await expect(row.getByText('Requires approval')).toBeVisible();
    await expect(row.getByTestId('setting-source')).toHaveText(/^(Bank|Platform|Default)$/);
  });

  test('AC-SET-03 an out-of-range value is rejected (SETTING_OUT_OF_RANGE) and nothing changes', async ({
    page,
    adminApi,
  }) => {
    const before = await bankSetting(adminApi, PASSWORD_MIN);
    const retentionBefore = await bankSetting(adminApi, RETENTION_DAYS);
    const tooShort = await putBankSetting(adminApi, PASSWORD_MIN, 11);
    expect(tooShort.status).toBe(400);
    expect(tooShort.body.code).toBe('SETTING_OUT_OF_RANGE');
    const tooLong = await putBankSetting(adminApi, RETENTION_DAYS, 31);
    expect(tooLong.status).toBe(400);
    expect(tooLong.body.code).toBe('SETTING_OUT_OF_RANGE');
    expect(await bankSetting(adminApi, PASSWORD_MIN)).toMatchObject({
      value: before.value,
      version: before.version,
      pendingChangeId: null,
    });

    // The UI shows the server's refusal under the field; nothing is saved.
    await openAs(page, BOOTSTRAP(), '/admin/settings?tab=dataProtection');
    const row = page.getByTestId(`setting-${RETENTION_DAYS}`);
    await row.getByLabel('Customer data retention').fill('31');
    await page.getByRole('button', { name: 'Save Data protection' }).click();
    await expect(row.getByRole('alert')).toBeVisible();
    await expect(page.getByText(/^Nothing was saved/)).toBeVisible();
    expect(await bankSetting(adminApi, RETENTION_DAYS)).toMatchObject({
      value: retentionBefore.value,
      version: retentionBefore.version,
    });
  });

  test('AC-SET-04 locked settings cannot be changed (SETTING_LOCKED) and render disabled', async ({
    page,
    adminApi,
  }) => {
    for (const key of [MFA_ADMINS, RATE_LIMITING]) {
      const res = await putBankSetting(adminApi, key, false);
      expect(res.status, key).toBe(400);
      expect(res.body.code, key).toBe('SETTING_LOCKED');
      expect((await bankSetting(adminApi, key)).locked).toBe(true);
    }
    await openAs(page, BOOTSTRAP(), '/admin/settings?tab=signIn');
    const row = page.getByTestId(`setting-${MFA_ADMINS}`);
    await expect(row.getByText('Locked', { exact: true })).toBeVisible();
    await expect(row.getByRole('switch')).toBeDisabled();
  });

  test('AC-SET-05 an operational setting applies immediately and reads back from the bank layer', async ({
    page,
    adminApi,
  }) => {
    const before = await bankSetting(adminApi, CODE_MIN_CONFIDENCE);
    expect(before.tier).toBe('OPERATIONAL');
    const target = Number(before.value) === 0.8 ? '0.85' : '0.8';
    try {
      await openAs(page, BOOTSTRAP(), '/admin/settings?tab=aiExtraction');
      const row = page.getByTestId(`setting-${CODE_MIN_CONFIDENCE}`);
      await row.getByLabel('Code extraction: minimum confidence').fill(target);
      await page.getByRole('button', { name: 'Save AI & extraction' }).click();
      await expect(page.getByText('1 applied, 0 sent for approval')).toBeVisible();
      await expect(row.getByTestId('setting-source')).toHaveText('Bank');

      const after = await bankSetting(adminApi, CODE_MIN_CONFIDENCE);
      expect(Number(after.value)).toBe(Number(target));
      expect(after.source).toBe('BANK');
      expect(after.version).toBeGreaterThan(before.version);
    } finally {
      await resetBankSetting(adminApi, CODE_MIN_CONFIDENCE);
    }
  });

  let pendingMinLength;

  test('AC-SET-06 a security setting goes to approval and stays unchanged until approved', async ({
    page,
    adminApi,
  }) => {
    const before = await bankSetting(adminApi, PASSWORD_MIN);
    expect(before.pendingChangeId).toBeNull();

    await openAs(page, BOOTSTRAP(), '/admin/settings?tab=signIn');
    const row = page.getByTestId(`setting-${PASSWORD_MIN}`);
    await row.getByLabel('Minimum password length').fill('14');
    await page.getByRole('button', { name: 'Save Sign-in & passwords' }).click();
    await expect(page.getByText('0 applied, 1 sent for approval')).toBeVisible();
    await expect(row.getByText('Pending', { exact: true })).toBeVisible();

    const view = await bankSetting(adminApi, PASSWORD_MIN);
    expect(view.value).toEqual(before.value);
    expect(view.pendingChangeId).toBeTruthy();
    pendingMinLength = view.pendingChangeId;

    // The key is frozen while its change is open.
    const second = await putBankSetting(adminApi, PASSWORD_MIN, 16, { expectedVersion: view.version });
    expect(second.status).toBe(409);
    expect(second.body.code).toBe('SETTING_CHANGE_PENDING');
  });

  test('AC-SET-07 self-approval is blocked; a second admin applies; the new value is enforced', async ({
    adminApi,
    checkerApi,
  }) => {
    test.skip(!pendingMinLength, 'needs the pending change from AC-SET-06');
    try {
      const self = await adminApi.request('POST', `/api/admin/changes/${pendingMinLength}/approve`, {
        json: {},
      });
      expect(self.status).toBe(409);
      const applied = await approveChange(checkerApi, pendingMinLength);
      expect(applied.status).toBe('APPLIED');
      expect((await bankSetting(adminApi, PASSWORD_MIN)).value).toBe(14);

      // AC-SET-11 end to end: a 13-character temporary password is now too short.
      const refused = await adminApi.request('POST', '/api/admin/users', {
        json: {
          email: `${uniqueName('e2e-short')}@e2e.modocument.test`.toLowerCase(),
          displayName: 'Too short',
          role: 'READ_ONLY',
          temporaryPassword: strongPassword().slice(0, 13),
        },
      });
      expect(refused.status).toBe(400);
      // 14+ characters still go through (to maker/checker).
      const accepted = await submitUserCreate(adminApi, {
        email: `${uniqueName('e2e-long')}@e2e.modocument.test`.toLowerCase(),
        displayName: 'Long enough',
        role: 'READ_ONLY',
        temporaryPassword: strongPassword(),
      });
      expect(accepted.changeType).toBe('USER_CREATE');
      await checkerApi.request('POST', `/api/admin/changes/${accepted.pendingChangeId}/reject`, {
        json: { comment: 'e2e cleanup' },
      });
    } finally {
      await resetBankSetting(adminApi, PASSWORD_MIN, checkerApi);
    }
  });

  test('AC-SET-08 a stale expectedVersion is refused with SETTING_VERSION_CONFLICT', async ({ adminApi }) => {
    const view = await bankSetting(adminApi, CODE_MIN_CONFIDENCE);
    try {
      const first = await putBankSetting(adminApi, CODE_MIN_CONFIDENCE, 0.8, { expectedVersion: view.version });
      expect(first.status).toBe(200);
      const stale = await putBankSetting(adminApi, CODE_MIN_CONFIDENCE, 0.9, { expectedVersion: view.version });
      expect(stale.status).toBe(409);
      expect(stale.body.code).toBe('SETTING_VERSION_CONFLICT');
    } finally {
      await resetBankSetting(adminApi, CODE_MIN_CONFIDENCE);
    }
  });

  test('AC-SET-13 platform bankMin/bankMax clamp a bank value (Clamped by platform badge)', async ({
    page,
    adminApi,
    platformApi,
    platformCheckerApi,
  }) => {
    test.skip(!platformApi || !platformCheckerApi, 'the stack was started without platform admins');
    // The bank picks a high operational value ...
    const set = await putBankSetting(adminApi, MAX_ROWS, 400000);
    expect(set.status).toBe(200);
    const bounds = await platformSetting(platformApi, MAX_ROWS);
    try {
      // ... the platform narrows the bank range (bounds are maker/checker).
      const narrowed = await platformApi.request('PUT', '/api/platform/settings', {
        json: {
          changes: [{ key: MAX_ROWS, bankMin: 100, bankMax: 100000, expectedVersion: bounds.version }],
          comment: 'e2e: cap integration runs',
        },
      });
      expect(narrowed.status).toBe(202);
      expect(narrowed.body.pendingChange.changeType).toBe('PLATFORM_SETTING_UPDATE');
      const self = await platformApi.request(
        'POST',
        `/api/admin/changes/${narrowed.body.pendingChange.pendingChangeId}/approve`,
        { json: {} }
      );
      expect(self.status).toBe(409);
      await approveChange(platformCheckerApi, narrowed.body.pendingChange.pendingChangeId);

      const clamped = await bankSetting(adminApi, MAX_ROWS);
      expect(clamped).toMatchObject({ value: 100000, clamped: true, min: 100, max: 100000, source: 'BANK' });
      await openAs(page, BOOTSTRAP(), '/admin/settings?tab=integrations');
      await expect(page.getByTestId(`setting-${MAX_ROWS}`).getByText('Clamped by platform')).toBeVisible();
    } finally {
      const now = await platformSetting(platformApi, MAX_ROWS);
      if (now.bankMin !== null || now.bankMax !== null) {
        const reset = await platformApi.request('PUT', '/api/platform/settings', {
          json: { changes: [{ key: MAX_ROWS, bankMin: null, bankMax: null, expectedVersion: now.version }] },
        });
        if (reset.status === 202) {
          await approveChange(platformCheckerApi, reset.body.pendingChange.pendingChangeId, 'e2e cleanup');
        }
      }
      await resetBankSetting(adminApi, MAX_ROWS);
    }
  });

  test('AC-SET-16 settings page per-tab save reports "N applied, M sent for approval"', async ({
    page,
    adminApi,
    checkerApi,
  }) => {
    // One operational and one security change on the same tab (Rate limits
    // has none operational, AI & extraction has no security one), so: two
    // tabs, each saving only its own keys, each with its own summary.
    try {
      await openAs(page, BOOTSTRAP(), '/admin/settings?tab=aiExtraction');
      await page
        .getByTestId(`setting-${CODE_MIN_CONFIDENCE}`)
        .getByLabel('Code extraction: minimum confidence')
        .fill('0.9');
      await page.getByRole('tab', { name: 'Sign-in & passwords' }).click();
      await page
        .getByTestId('setting-security.lockout.allowedAttempts')
        .getByLabel('Wrong passwords before lockout')
        .fill('6');
      await expect(page.getByText('Other tabs have unsaved changes; each tab saves on its own.')).toBeVisible();
      await page.getByRole('button', { name: 'Save Sign-in & passwords' }).click();
      await expect(page.getByText('0 applied, 1 sent for approval')).toBeVisible();
      await page.getByRole('tab', { name: 'AI & extraction' }).click();
      await page.getByRole('button', { name: 'Save AI & extraction' }).click();
      await expect(page.getByText('1 applied, 0 sent for approval')).toBeVisible();
    } finally {
      await resetBankSetting(adminApi, CODE_MIN_CONFIDENCE);
      await resetBankSetting(adminApi, 'security.lockout.allowedAttempts', checkerApi);
    }
  });
});
