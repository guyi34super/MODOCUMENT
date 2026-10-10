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
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test, expect, openAs } from '../../lib/fixtures.js';
import { OPERATOR, uniqueName } from '../../lib/seed.js';

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
  test.fixme('AC-ADM-01 dashboard KPIs match the seeded data exactly', async () => {
    // seed: 3 users (1 locked, 1 admin without MFA), 2 jobs today (1 VALIDATED, 1 FAILED),
    // 1 code-parsed job, 1 pending approval, 1 shared + 1 private template -> tiles equal.
  });

  test.fixme('AC-ADM-03 unlock clears a locked account directly (USER_UNLOCKED)', async () => {
    // 6 wrong passwords -> ACCOUNT_LOCKED -> admin clicks Unlock -> user signs in.
  });

  test.fixme('AC-ADM-04 password reset goes through approval, kills sessions, forces a change', async () => {});

  test.fixme('AC-ADM-05 MFA reset goes through approval; the admin re-enrols at next sign-in', async () => {});

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
    const uploadToggle = page.getByRole('button', { name: 'Upload a document' });
    if (await uploadToggle.isVisible()) await uploadToggle.click();
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

  test.fixme('AC-ADM-10 pending changes page labels and diffs every new change type', async () => {
    // e.g. "Security setting: password.min-length: 12 → 14", "Password reset: <email>".
  });
});
