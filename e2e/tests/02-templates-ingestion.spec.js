// Smoke: design a template in the editor, then push a CSV through
// upload -> parse -> map -> validate, and a free-text PDF through the AI
// parse path served by the fake LLM.
import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs';
import { test, expect, openAs, openUploadPanel } from '../lib/fixtures.js';
import { createTemplate, getJob, uploadForIngestion, waitForJob } from '../lib/api.js';
import { OPERATOR, uniqueName } from '../lib/seed.js';
import { E2E_ROOT } from '../lib/env.js';
import { writeTextPdf } from '../fixtures/pdf.js';

const CSV = path.join(E2E_ROOT, 'fixtures', 'employees.csv');

test.describe.configure({ mode: 'serial' });

test.describe('templates and ingestion @smoke', () => {
  let templateName;
  let templateId;

  test('SMK-TPL-01 an operator designs a template with three fields in the editor', async ({ page, operatorApi }) => {
    templateName = uniqueName('E2E Employees');
    await openAs(page, OPERATOR(), '/templates/new');
    await expect(page.getByRole('heading', { name: 'New Template' })).toBeVisible();

    await page.getByLabel('Name').fill(templateName);
    await page.getByLabel('Description').fill('Created by the E2E smoke suite');
    const names = ['EmployeeId', 'FullName', 'Department'];
    for (let i = 0; i < names.length; i += 1) {
      if (i > 0) await page.getByRole('button', { name: '+ Add field' }).click();
      await page.getByPlaceholder('Field name').nth(i).fill(names[i]);
    }
    await page.getByRole('button', { name: 'Create template' }).click();

    await expect(page).toHaveURL(/\/templates\/\d+$/);
    await expect(page.getByRole('heading', { name: `Edit: ${templateName}` })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Fields (3)' })).toBeVisible();
    templateId = Number(page.url().split('/').pop());

    const fields = await operatorApi.get(`/TemplateFields/GetAll?templateId=${templateId}`);
    expect(fields.map((f) => f.fieldName).sort()).toEqual([...names].sort());

    await page.goto('/templates');
    await expect(page.getByRole('link', { name: templateName })).toBeVisible();
  });

  test('SMK-ING-01 a CSV upload is parsed, mapped and validated (job reaches VALIDATED)', async ({
    page,
    operatorApi,
    llm,
  }) => {
    test.skip(!templateId, 'needs the template from SMK-TPL-01');
    await llm.reset();
    await openAs(page, OPERATOR(), '/ingestion');
    await expect(page.getByRole('heading', { name: 'Upload & Process' })).toBeVisible();
    await openUploadPanel(page);

    await page.getByLabel('File').setInputFiles(CSV);
    await page.getByLabel('Template').selectOption({ label: templateName });
    await page.getByRole('button', { name: 'Upload & process' }).click();

    await expect(page).toHaveURL(/\/ingestion\/\d+/, { timeout: 120_000 });
    const jobId = Number(new URL(page.url()).pathname.split('/').pop());

    // Upload ran until MAPPED; exact header names map without any AI.
    let job = await waitForJob(operatorApi, jobId, (j) => j.currentStep === 'MAPPED');
    expect(job.unmappedFields ?? []).toEqual([]);

    await page.getByRole('button', { name: 'Continue to validation' }).click();
    job = await waitForJob(operatorApi, jobId, (j) => j.currentStep === 'VALIDATED');
    expect(job.status).not.toBe('FAILED');
    expect(job.validationErrorCount ?? 0).toBe(0);
    await expect(page.getByRole('heading', { name: 'Validation' })).toBeVisible();

    // CSV is the deterministic path: the LLM was never asked to read the table.
    const stats = await llm.stats();
    const tableCalls = Object.entries(stats.byKind)
      .filter(([kind]) => kind.startsWith('TABLE_'))
      .reduce((sum, [, n]) => sum + n, 0);
    expect(tableCalls).toBe(0);
  });

  test('SMK-ING-02 a free-text PDF is extracted through the AI path (fake LLM, ok mode)', async ({
    operatorApi,
    llm,
  }) => {
    const template = await createTemplate(operatorApi, {
      name: uniqueName('E2E Invoices'),
      fields: ['Reference', 'Amount', 'Currency'],
    });
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'modoc-e2e-'));
    const pdf = writeTextPdf(path.join(dir, 'invoices.pdf'), [
      'Invoice register',
      '',
      'Reference: INV-A1',
      'Amount: 125.50',
      'Currency: MUR',
      '',
      'Reference: INV-B2',
      'Amount: 80.00',
      'Currency: EUR',
    ]);
    await llm.reset();

    const created = await uploadForIngestion(operatorApi, {
      filePath: pdf,
      templateId: template.templateId,
      runUntil: 'MAPPED',
      wait: true,
    });
    const job = await waitForJob(operatorApi, created.ingestionJobId, (j) => j.currentStep === 'MAPPED');
    const full = await getJob(operatorApi, job.ingestionJobId);

    const stats = await llm.stats();
    expect(stats.calls, 'the parse step asked the (fake) model').toBeGreaterThan(0);
    expect(Object.keys(stats.byKind).some((kind) => kind.startsWith('TABLE_'))).toBe(true);
    expect(stats.byMode.ok).toBe(stats.calls);

    const rows = full.mappedRows?.rows ?? full.parsedTable?.rows ?? [];
    expect(rows.length).toBe(2);
    expect(JSON.stringify(rows)).toContain('INV-B2');
  });
});
