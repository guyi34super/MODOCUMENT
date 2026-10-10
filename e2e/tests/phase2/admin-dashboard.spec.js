// Phase 2 — admin dashboard, user actions, bank-shared templates (SE3).
// Acceptance criteria: docs/phase2/test-plan.md §5.3.
//
// UI contract (SE3): /admin -> AdminDashboardPage with KPI tiles
// data-testid="kpi-<metric>" (e.g. kpi-users-active, kpi-admins-without-mfa,
// kpi-ingestion-today, kpi-parsed-by-code, kpi-pending-approvals) whose
// value is in data-testid="kpi-value"; quick actions role=button
// name="Add user" | "Invite" | "New shared template" | "Review approvals".
// AdminUsersPage row actions (as merged, frontend/src/pages/AdminUsersPage.jsx):
// role=button names "Unlock <email>" | "Reset password of <email>" |
// "Reset MFA of <email>" | "Edit <email>"; dialogs role=dialog named
// "Unlock account" (button "Unlock"), "Reset password" (field "Temporary
// password", button "Request reset"), "Reset MFA" (field "Reason", button
// "Request MFA reset"), "Edit user". TemplatesPage: badge text "Shared" |
// "Private" and role=switch name="Share with bank".
// Live since Wave 2 (ws22 merged).
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test, expect, openAs, openUploadPanel } from '../../lib/fixtures.js';
import { ApiClient, approveChange, sessionFor, strongPassword } from '../../lib/api.js';
import { getAccount, saveAccount } from '../../lib/accounts.js';
import { bankSetting, freshDashboard, putBankSetting } from '../../lib/phase2.js';
import { BOOTSTRAP, OPERATOR, createUserViaMakerChecker, uniqueName } from '../../lib/seed.js';

/** A user created through maker/checker who has signed in once (password changed). */
async function activeUser(role = 'READ_WRITE') {
  const { email } = await createUserViaMakerChecker({ role });
  return { email, api: await sessionFor(email) };
}

/** The admin listing row of a user. */
async function userRow(adminApi, email) {
  return (await adminApi.get('/api/admin/users')).find((u) => u.email === email);
}

/** Password sign-in until the account locks (allowed attempts + 1 wrong passwords). */
async function lockOut(email, attempts) {
  const anon = new ApiClient();
  for (let i = 0; i <= attempts; i += 1) {
    await anon.request('POST', '/api/auth/login', { json: { email, password: `Wrong-${i}-${Date.now()}!` } });
  }
}

/**
 * A template shared with the bank (ws22), created the product way: the
 * admin's CreateWithFields with visibility BANK (template:publish).
 */
async function sharedTemplate(adminApi) {
  return adminApi.post('/Templates/CreateWithFields', {
    name: uniqueName('E2E Shared Employees'),
    description: 'shared with the bank by the e2e suite',
    visibility: 'BANK',
    fields: ['EmployeeId', 'FullName', 'Department'].map((fieldName) => ({
      fieldName,
      dataType: 'Text',
      rules: [],
      piiKind: null,
    })),
  });
}

/** A CSV with unique bytes (the C6 duplicate-upload gate refuses a re-upload). */
function uniqueCsv() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'modoc-e2e-shared-'));
  const file = path.join(dir, `${uniqueName('shared')}.csv`);
  fs.writeFileSync(
    file,
    `EmployeeId,FullName,Department\nE-${Date.now()},Ada Lovelace,Engineering\n`
  );
  return file;
}

/** Multipart upload without running the pipeline: the job stays PENDING. */
async function pendingJob(api, templateId) {
  const file = uniqueCsv();
  const form = new FormData();
  form.append('file', new Blob([fs.readFileSync(file)]), path.basename(file));
  form.append('templateId', String(templateId));
  form.append('autoRun', 'false');
  return (await api.request('POST', '/api/ingestion/jobs', { form, expect: 201 })).body;
}

test.describe('admin dashboard @phase2 @wave2', () => {
  test('AC-ADM-01 dashboard KPIs match the seeded data exactly', async ({ page, adminApi, checkerApi }) => {
    // The shared E2E database already holds other specs' data, so the seed
    // is a known DELTA on top of a baseline, and the absolute numbers are
    // checked against independent sources (the user listing, the queue).
    const baseline = await adminApi.get('/api/admin/dashboard');
    const startedAt = new Date().toISOString();
    await adminApi.post('/Templates/CreateWithFields', {
      name: uniqueName('E2E KPI private'),
      fields: [{ fieldName: 'A', dataType: 'Text', rules: [], piiKind: null }],
    });
    await sharedTemplate(adminApi);
    const lockout = await bankSetting(adminApi, 'security.lockout.allowedAttempts');
    const locked = await activeUser('READ_ONLY');
    await lockOut(locked.email, Number(lockout.value));
    const pending = await adminApi.request('POST', '/api/admin/users', {
      json: {
        email: `${uniqueName('e2e-kpi')}@e2e.modocument.test`.toLowerCase(),
        displayName: 'KPI pending',
        role: 'READ_ONLY',
        temporaryPassword: strongPassword(),
      },
      expect: 202,
    });

    try {
      const now = await freshDashboard(adminApi, startedAt);
      expect(now.templates.private).toBe(baseline.templates.private + 1);
      expect(now.templates.shared).toBe(baseline.templates.shared + 1);
      const listing = await adminApi.get('/api/admin/users');
      expect(now.users.total).toBe(listing.length);
      expect(now.users.locked).toBe(listing.filter((u) => u.locked).length);
      expect(now.users.disabled).toBe(listing.filter((u) => u.disabled).length);
      expect(now.users.locked).toBeGreaterThanOrEqual(1);
      const queue = await adminApi.get('/api/admin/changes?status=REQUESTED');
      expect(now.approvals.pendingChanges).toBe(queue.length);
      expect(now.approvals.pendingChanges).toBeGreaterThanOrEqual(baseline.approvals.pendingChanges + 1);

      // The tiles show exactly the API's numbers (same 30 s snapshot).
      await openAs(page, BOOTSTRAP(), '/admin');
      await expect(page.getByRole('heading', { name: 'Admin dashboard' })).toBeVisible();
      const tile = (id) => page.getByTestId(id).getByTestId('kpi-value');
      await expect(tile('kpi-users-active')).toHaveText(String(now.users.active));
      await expect(tile('kpi-users-locked')).toHaveText(String(now.users.locked));
      await expect(tile('kpi-pending-approvals')).toHaveText(String(now.approvals.pendingChanges));
      await expect(tile('kpi-templates-private')).toHaveText(String(now.templates.private));
      await expect(tile('kpi-templates-shared')).toHaveText(String(now.templates.shared));
      await expect(tile('kpi-version')).toHaveText(String(now.version.version));
      // Quick actions are there.
      for (const name of ['Add user', 'Invite', 'New shared template', 'Review approvals']) {
        await expect(page.getByRole('region', { name: 'Quick actions' }).getByRole('button', { name })).toBeVisible();
      }
    } finally {
      await checkerApi.request('POST', `/api/admin/changes/${pending.body.pendingChangeId}/reject`, {
        json: { comment: 'e2e cleanup' },
      });
      await adminApi.request('POST', `/api/admin/users/${(await userRow(adminApi, locked.email)).id}/unlock`);
    }
  });

  test('AC-ADM-03 unlock clears a locked account directly (USER_UNLOCKED)', async ({ page, adminApi }) => {
    const lockout = await bankSetting(adminApi, 'security.lockout.allowedAttempts');
    const user = await activeUser('READ_WRITE');
    await lockOut(user.email, Number(lockout.value));
    // Locked: even the right password answers the generic 401.
    const refused = await new ApiClient().request('POST', '/api/auth/login', {
      json: { email: user.email, password: getAccount(user.email).password },
    });
    expect(refused.status).toBe(401);
    expect((await userRow(adminApi, user.email)).locked).toBe(true);

    await openAs(page, BOOTSTRAP(), '/admin/users');
    await page.getByRole('button', { name: `Unlock ${user.email}` }).click();
    const dialog = page.getByRole('dialog', { name: 'Unlock account' });
    await dialog.getByRole('button', { name: 'Unlock' }).click();
    await expect(page.getByText(`${user.email} unlocked — they can sign in again`)).toBeVisible();
    await expect(page.getByRole('button', { name: `Unlock ${user.email}` })).toHaveCount(0);

    expect((await userRow(adminApi, user.email)).locked).toBe(false);
    const again = await new ApiClient().request('POST', '/api/auth/login', {
      json: { email: user.email, password: getAccount(user.email).password },
    });
    expect(again.status).toBe(200);
    // The caller's own row offers no action at all.
    await expect(page.getByRole('button', { name: `Unlock ${BOOTSTRAP()}` })).toHaveCount(0);
    await expect(page.getByRole('button', { name: `Reset password of ${BOOTSTRAP()}` })).toHaveCount(0);
  });

  test('AC-ADM-04 password reset goes through approval, kills sessions, forces a change', async ({
    page,
    checkerApi,
  }) => {
    const user = await activeUser('READ_WRITE');
    const oldPassword = getAccount(user.email).password;
    const temporary = strongPassword();

    await openAs(page, BOOTSTRAP(), '/admin/users');
    await page.getByRole('button', { name: `Reset password of ${user.email}` }).click();
    const dialog = page.getByRole('dialog', { name: 'Reset password' });
    await dialog.getByLabel('Temporary password').fill(temporary);
    await dialog.getByRole('button', { name: 'Request reset' }).click();
    await expect(page.getByText(/submitted for approval|sent for approval/i).first()).toBeVisible();

    // Pending: the live session still works.
    expect((await user.api.request('GET', '/api/auth/me')).status).toBe(200);
    const queue = await checkerApi.get('/api/admin/changes?status=REQUESTED');
    const change = queue.find(
      (c) => c.changeType === 'USER_PASSWORD_RESET' && JSON.stringify(c.payload).includes(user.email)
    );
    expect(change, 'USER_PASSWORD_RESET in the queue').toBeTruthy();
    expect(JSON.stringify(change.payload)).not.toContain(temporary);
    await approveChange(checkerApi, change.pendingChangeId);

    // Applied: the old token dies, the old password is dead, the temporary one forces a change.
    expect((await user.api.request('GET', '/api/auth/me')).status).toBe(401);
    const anon = new ApiClient();
    expect((await anon.request('POST', '/api/auth/login', { json: { email: user.email, password: oldPassword } })).status).toBe(401);
    const temp = await anon.request('POST', '/api/auth/login', { json: { email: user.email, password: temporary }, expect: 200 });
    expect(temp.body.user.mustChangePassword).toBe(true);
    saveAccount(user.email, { password: temporary, token: null, tokenExp: null });
  });

  test('AC-ADM-05 MFA reset goes through approval; the admin re-enrols at next sign-in', async ({
    page,
    checkerApi,
  }) => {
    // An ADMIN created through maker/checker, enrolled in TOTP by its first sign-in.
    const target = await activeUser('ADMIN');
    expect((await target.api.get('/api/auth/mfa')).enabled).toBe(true);

    await openAs(page, BOOTSTRAP(), '/admin/users');
    await page.getByRole('button', { name: `Reset MFA of ${target.email}` }).click();
    const dialog = page.getByRole('dialog', { name: 'Reset MFA' });
    await dialog.getByLabel('Reason').fill('E2E: lost phone, ticket HD-0001');
    await dialog.getByRole('button', { name: 'Request MFA reset' }).click();
    await expect(page.getByText(/submitted for approval|sent for approval/i).first()).toBeVisible();

    const change = (await checkerApi.get('/api/admin/changes?status=REQUESTED')).find(
      (c) => c.changeType === 'USER_MFA_RESET' && JSON.stringify(c.payload).includes(target.email)
    );
    expect(change, 'USER_MFA_RESET in the queue').toBeTruthy();
    await approveChange(checkerApi, change.pendingChangeId);

    expect((await target.api.request('GET', '/api/auth/me')).status).toBe(401);
    const login = await new ApiClient().request('POST', '/api/auth/login', {
      json: { email: target.email, password: getAccount(target.email).password },
      expect: 200,
    });
    expect(login.body).toMatchObject({ mfaRequired: true, enrollmentRequired: true });
    // The book's TOTP secret is void now; the next sessionFor() re-enrols.
    saveAccount(target.email, { totpSecret: null, lastCounter: null, token: null, tokenExp: null });
  });

  test('AC-ADM-07 a bank-shared template is usable by every user of the bank but editable only with TEMPLATE_PUBLISH', async ({
    page,
    adminApi,
    operatorApi,
  }) => {
    // Private by default; the admin publishes it (template:publish).
    const created = await adminApi.post('/Templates/CreateWithFields', {
      name: uniqueName('E2E Published'),
      description: 'published by the e2e suite',
      fields: [{ fieldName: 'EmployeeId', dataType: 'Text', rules: [], piiKind: null }],
    });
    expect(created.visibility).toBe('PRIVATE');
    expect((await operatorApi.request('GET', `/Templates/GetById/${created.templateId}`)).status).toBe(404);
    expect(
      (await operatorApi.request('PUT', `/Templates/${created.templateId}/Visibility`, {
        json: { visibility: 'BANK' },
      })).status,
      'a READ_WRITE operator holds no template:publish'
    ).toBe(403);
    const published = await adminApi.put(`/Templates/${created.templateId}/Visibility`, {
      visibility: 'BANK',
    });
    expect(published.visibility).toBe('BANK');

    // The operator reads and USES it ...
    const template = await sharedTemplate(adminApi);
    const listed = (await operatorApi.get('/Templates/GetAll')).find(
      (t) => t.templateId === template.templateId
    );
    expect(listed).toMatchObject({ visibility: 'BANK', ownedByMe: false, editable: false });
    const fields = await operatorApi.get(`/TemplateFields/GetAll?templateId=${template.templateId}`);
    expect(fields.map((f) => f.fieldName).sort()).toEqual(['Department', 'EmployeeId', 'FullName']);
    const job = await pendingJob(operatorApi, template.templateId);
    expect(job.templateId).toBe(template.templateId);

    // ... but cannot change it (403: it can see it, so hiding it would be pointless).
    const update = await operatorApi.request('PUT', '/Templates/Update', {
      json: { templateId: template.templateId, name: template.name, description: 'hijacked' },
    });
    expect(update.status).toBe(403);
    expect((await operatorApi.request('DELETE', `/Templates/${template.templateId}`)).status).toBe(403);
    const addField = await operatorApi.request('POST', '/TemplateFields/Create', {
      json: { templateId: template.templateId, fieldName: 'Extra', templateDataTypeId: 1 },
    });
    expect(addField.status).toBe(403);

    // UI: Shared badge, no share switch for the operator, and the template is
    // in the upload picker.
    await openAs(page, OPERATOR(), '/templates');
    const row = page.getByRole('row').filter({ hasText: template.name });
    await expect(row.getByText('Shared', { exact: true })).toBeVisible();
    await expect(page.getByRole('switch', { name: 'Share with bank' })).toHaveCount(0);
    await page.goto('/ingestion');
    await openUploadPanel(page);
    await expect(
      page.getByLabel('Template').locator('option', { hasText: template.name })
    ).toHaveCount(1);
  });

  test('AC-ADM-08 deleting a shared template that is in use returns 409', async ({
    adminApi,
    operatorApi,
  }) => {
    const template = await sharedTemplate(adminApi);
    await pendingJob(operatorApi, template.templateId);

    // The maker hears it at once (submit-time check), nothing is queued.
    const remove = await adminApi.request('DELETE', `/Templates/${template.templateId}`);
    expect(remove.status, JSON.stringify(remove.body)).toBe(409);
    expect(JSON.stringify(remove.body)).toContain('in use');
    // Making it private under the colleague's unfinished job is refused too.
    const unshare = await adminApi.request('PUT', `/Templates/${template.templateId}/Visibility`, {
      json: { visibility: 'PRIVATE' },
    });
    expect(unshare.status).toBe(409);
    expect((await operatorApi.request('GET', `/Templates/GetById/${template.templateId}`)).status).toBe(200);
  });

  test('AC-ADM-10 pending changes page labels and diffs the new change types', async ({
    page,
    adminApi,
    checkerApi,
  }) => {
    const key = 'security.lockout.allowedAttempts';
    const view = await bankSetting(adminApi, key);
    const next = Number(view.value) === 6 ? 7 : 6;
    const setting = await putBankSetting(adminApi, key, next, { comment: 'e2e diff' });
    expect(setting.status).toBe(202);
    const user = await activeUser('READ_ONLY');
    const reset = await adminApi.request('POST', `/api/admin/users/${(await userRow(adminApi, user.email)).id}/password-reset`, {
      json: { temporaryPassword: strongPassword() },
      expect: 202,
    });
    try {
      await openAs(page, BOOTSTRAP(), '/admin/changes');
      const settingId = setting.body.pendingChange.pendingChangeId;
      await expect(page.getByText('Change settings').first()).toBeVisible();
      await expect(page.getByRole('list', { name: `Changes in #${settingId}` })).toContainText(
        new RegExp(`${key.replace(/\./g, '\\.')}: .+ → ${next}`)
      );
      await expect(page.getByText('Reset password').first()).toBeVisible();
      await expect(page.getByText(`Password reset for ${user.email}`)).toBeVisible();
    } finally {
      for (const id of [setting.body.pendingChange.pendingChangeId, reset.body.pendingChangeId]) {
        await checkerApi.request('POST', `/api/admin/changes/${id}/reject`, { json: { comment: 'e2e cleanup' } });
      }
    }
  });
});
