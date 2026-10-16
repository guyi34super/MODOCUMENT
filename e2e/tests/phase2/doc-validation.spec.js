// Field editing, field rules and validate-before-save in document creation
// (docs/doccreation/field-validation-contract.md, ws33).
//
// The template is a generated one-page "Contract form" PDF with five boxes;
// its fields are set through the overlay API (setup), everything the
// feature adds is then driven through the UI.
//
//   VAL-01 template editor: "Status" becomes a Dropdown with options, the
//          start date gets the PDF date format dd/MM/yyyy; Field rules tab:
//          REQUIRED on Name, E-mail address on E-mail, Compare End date >=
//          Start date; saved, and still there after a reload
//   VAL-02 import: a flow template (same field names) with flow rules
//          (REQUIRED, REGEX via /ValidationRules/Upsert) is picked in the
//          Import dialog: the preview shows same-name matches; Import ->
//          the rules are listed with an "Imported from …" origin
//   VAL-03 builder: wrong / missing values -> Save -> the document stays a
//          DRAFT, the Issues panel lists the errors, Generate and Download
//          are disabled ("Save and validate first"); the API refuses
//          generate / download with 409 NOT_VALIDATED
//   VAL-04 the values fixed -> Save -> VALIDATED -> Generate -> Download:
//          the PDF shows the dropdown value and the date as dd/MM/yyyy
//   VAL-05 an edit after validation -> Draft again (Generate disabled), on
//          the server too; Save -> VALIDATED again
//   VAL-06 editor tools: Shift-click two fields, Align left, Ctrl+Z restores,
//          Ctrl+D duplicates a field; saved as a new version
//   VAL-07 permissions: READ_ONLY cannot PUT rules (403); another bank gets
//          404 for the rules of this template
//
// UI contract (frontend/src/components/rules/*, components/doc/*,
// components/pdf/*): tab "Field rules" (role=tab) -> data-testid
// field-rules, one section rules-field-<key> with "Add rule to <label>";
// dialog "Add a rule — <label>" (Rule type, Other field, This value must
// be, "Add rule"); "Save rules"; "Import rules" dialog (radios "Data
// sources" / "A flow template", select "Flow template", "Show what would be
// imported", rows import-row-<key>, "Import rules of N fields"). Builder:
// "Save" (validate), badge DRAFT / VALIDATED, data-testid issues-panel and
// field-messages-<key>, #gate-reason "Save and validate first". Editor:
// region-properties (Kind, Options, "Add option", "Date format in the
// PDF"), toolbar "Arrange fields" (Undo, Duplicate, Align left, …).
import fs from 'node:fs/promises';
import { test, expect, openAs } from '../../lib/fixtures.js';
import { createTemplate, sessionFor } from '../../lib/api.js';
import { OPERATOR, createUserViaMakerChecker, uniqueName } from '../../lib/seed.js';
import { onboardBank } from '../../lib/phase2.js';
import { buildPdf } from '../../fixtures/pdf-form.js';
import { containsText, pageTexts } from '../../lib/pdf-inspect.js';
import { getBlueprint, getBytes, saveAndValidate, uploadBlueprint } from '../../lib/doccreation.js';

const DOCS = '/api/doccreation';
const PAGE_W = 595.28;
const PAGE_H = 841.89;

// The form's boxes in PDF points (x, y = bottom-left), and the fields on them.
const FIELDS = [
  { key: 'name', label: 'Name', kind: 'TEXT', text: 'Name:', box: [170, 700, 260, 20] },
  { key: 'email', label: 'E-mail', kind: 'TEXT', text: 'E-mail:', box: [170, 665, 260, 20] },
  { key: 'start_date', label: 'Start date', kind: 'DATE', text: 'Start date:', box: [170, 630, 140, 20] },
  { key: 'end_date', label: 'End date', kind: 'DATE', text: 'End date:', box: [170, 595, 140, 20] },
  { key: 'status', label: 'Status', kind: 'TEXT', text: 'Status:', box: [170, 560, 140, 20] },
];
const STATUS_OPTIONS = ['Active', 'Suspended', 'Closed'];
const NAME_PATTERN = "^[A-Za-z][A-Za-z .'-]*$";

const GOOD = {
  name: 'Jane Doe',
  email: 'jane.doe@example.com',
  start_date: '2026-01-15',
  end_date: '2026-12-31',
  status: 'Active',
};

/** The one-page "Contract form": a title and a label + box per field (no "*": nothing is required by itself). */
function contractForm() {
  return buildPdf((addPage) => {
    const p = addPage(PAGE_W, PAGE_H);
    p.text(72, 780, 'Contract Form', { size: 16, font: 'F2' });
    for (const f of FIELDS) {
      const [x, y, w, h] = f.box;
      p.text(72, y + 6, f.text);
      p.rect(x, y, w, h);
    }
    p.text(72, 300, 'Please check every detail before signing.');
  });
}

/** The overlay region of a field: its box as top-left page fractions. */
function regionOf(field, index) {
  const [x, y, w, h] = field.box;
  return {
    key: field.key,
    label: field.label,
    group: null,
    kind: field.kind,
    page: 0,
    fx: x / PAGE_W,
    fy: (PAGE_H - (y + h)) / PAGE_H,
    fw: w / PAGE_W,
    fh: h / PAGE_H,
    fontName: null,
    fontSize: 0,
    color: null,
    align: 'LEFT',
    maxLines: 1,
    acroFieldName: null,
    required: false,
    sortOrder: index,
    source: 'MANUAL',
    confidence: 1,
    suggested: false,
    props: null,
  };
}

/** The Generate / Download PDF / Save buttons of the builder header. */
const button = (page, name) => page.getByRole('button', { name, exact: true });

test.describe.configure({ mode: 'serial' });

test.describe('field rules and validate-before-save @phase2 @validation', () => {
  let blueprintId;
  let blueprintName;
  let flowTemplate; // {templateId, name}
  let instanceId;
  let instanceTitle;

  test('VAL-01 template editor: Dropdown + date format; Field rules tab adds REQUIRED, EMAIL and COMPARE', async ({
    page,
    operatorApi,
  }) => {
    // Setup (API): the PDF and its five fields, confirmed as version 1.
    blueprintName = uniqueName('E2E Contract form');
    const uploaded = await uploadBlueprint(operatorApi, {
      bytes: contractForm(),
      fileName: 'contract-form.pdf',
      flow: 'BLANK',
      name: blueprintName,
    });
    expect([200, 201]).toContain(uploaded.status);
    blueprintId = uploaded.body.id;
    const draft = await getBlueprint(operatorApi, blueprintId);
    await operatorApi.put(`${DOCS}/blueprints/${blueprintId}/overlay`, {
      baseVersion: draft.currentVersion ?? draft.version,
      regions: FIELDS.map(regionOf),
      edits: [],
      confirm: true,
    });
    const v1 = await getBlueprint(operatorApi, blueprintId);
    expect(v1).toMatchObject({ status: 'READY', currentVersion: 1 });
    expect(v1.regions.map((r) => r.key).sort()).toEqual(FIELDS.map((f) => f.key).sort());
    const noRules = await operatorApi.get(`${DOCS}/blueprints/${blueprintId}/rules`);
    expect(noRules.rules).toEqual([]);

    // Layout: Status becomes a Dropdown with three options.
    await openAs(page, OPERATOR(), `/creation/blueprints/${blueprintId}`);
    await expect(page.getByRole('heading', { name: blueprintName })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'Field rules' })).toBeVisible();
    const properties = page.getByTestId('region-properties');
    await page.getByTestId('region-status').click();
    await expect(properties.getByLabel('Label')).toHaveValue('Status');
    await properties.getByLabel('Kind').selectOption({ label: 'Dropdown' });
    await expect(properties.getByText('No options yet.')).toBeVisible();
    for (const [i, option] of STATUS_OPTIONS.entries()) {
      await properties.getByRole('button', { name: 'Add option' }).click();
      await properties.getByLabel(`Option ${i + 1}`, { exact: true }).fill(option);
    }
    // ... and the start date is drawn as dd/MM/yyyy in the PDF.
    await page.getByTestId('region-start_date').click();
    await expect(properties.getByLabel('Label')).toHaveValue('Start date');
    await properties.getByLabel('Date format in the PDF').selectOption('dd/MM/yyyy');
    await page.getByRole('button', { name: 'Save as new version' }).click();
    await expect(page.getByText('Saved as version 2')).toBeVisible();

    const v2 = await getBlueprint(operatorApi, blueprintId);
    const byKey = Object.fromEntries(v2.regions.map((r) => [r.key, r]));
    expect(v2.currentVersion).toBe(2);
    expect(byKey.status).toMatchObject({ kind: 'CHOICE', props: { options: STATUS_OPTIONS } });
    expect(byKey.start_date.props).toMatchObject({ dateFormat: 'dd/MM/yyyy' });

    // Field rules tab.
    await page.getByRole('tab', { name: 'Field rules' }).click();
    const rulesTab = page.getByTestId('field-rules');
    await expect(rulesTab.getByText('revision 0')).toBeVisible();

    const addRule = async (field, ruleType, extra = async () => {}) => {
      await page.getByTestId(`rules-field-${field.key}`).getByRole('button', { name: `Add rule to ${field.label}` }).click();
      const dialog = page.getByRole('dialog', { name: `Add a rule — ${field.label}` });
      await expect(dialog).toBeVisible();
      await dialog.getByLabel('Rule type').selectOption(ruleType);
      await extra(dialog);
      await dialog.getByRole('button', { name: 'Add rule', exact: true }).click();
      await expect(dialog).toBeHidden();
    };
    const [name, email, , endDate] = FIELDS;
    await addRule(name, 'REQUIRED');
    await addRule(email, 'EMAIL');
    await addRule(endDate, 'COMPARE', async (dialog) => {
      // A COMPARE rule needs the other field and the operator: refused without them.
      await dialog.getByRole('button', { name: 'Add rule', exact: true }).click();
      await expect(dialog.getByRole('alert')).toHaveText('Choose the other field.');
      await dialog.getByLabel('Other field').selectOption('start_date');
      await dialog.getByLabel('This value must be').selectOption('>=');
    });
    await expect(rulesTab.getByText('Unsaved rule changes.')).toBeVisible();
    await rulesTab.getByRole('button', { name: 'Save rules' }).click();
    await expect(page.getByText('Field rules saved')).toBeVisible();
    await expect(rulesTab.getByText('revision 1')).toBeVisible();

    const expectRulesListed = async () => {
      const tab = page.getByTestId('field-rules');
      const nameRules = tab.getByTestId('rules-field-name');
      await expect(nameRules.getByRole('cell', { name: 'Required', exact: true })).toBeVisible();
      await expect(nameRules.getByText('Must be filled in')).toBeVisible();
      await expect(nameRules.getByText('Manual')).toBeVisible();
      await expect(tab.getByTestId('rules-field-email').getByRole('cell', { name: 'E-mail address', exact: true })).toBeVisible();
      const endRules = tab.getByTestId('rules-field-end_date');
      await expect(endRules.getByRole('cell', { name: 'Compare with a field', exact: true })).toBeVisible();
      await expect(endRules.getByText('On or after / at least Start date')).toBeVisible();
      await expect(tab.getByTestId('rules-field-status').getByText('No rules.')).toBeVisible();
    };
    await expectRulesListed();

    // What the server keeps, and the page after a reload.
    const saved = await operatorApi.get(`${DOCS}/blueprints/${blueprintId}/rules`);
    expect(saved.revision).toBe(1);
    expect(saved.rules.map((r) => [r.regionKey, r.ruleType, r.origin])).toEqual(
      expect.arrayContaining([
        ['name', 'REQUIRED', 'MANUAL'],
        ['email', 'EMAIL', 'MANUAL'],
        ['end_date', 'COMPARE', 'MANUAL'],
      ])
    );
    expect(saved.rules).toHaveLength(3);
    expect(saved.rules.find((r) => r.ruleType === 'COMPARE').config).toEqual({ Field: 'start_date', Op: '>=' });
    // The Required flag is derived from the rules.
    expect((await getBlueprint(operatorApi, blueprintId)).regions.find((r) => r.key === 'name').required).toBe(true);

    await page.reload();
    await page.getByRole('tab', { name: 'Field rules' }).click();
    await expect(page.getByTestId('field-rules').getByText('revision 1')).toBeVisible();
    await expectRulesListed();
  });

  test('VAL-02 rules are imported from a flow template: same-name matches, "Imported" origin', async ({ page, operatorApi }) => {
    test.skip(!blueprintId, 'needs the template from VAL-01');
    // Setup (API): a flow template with the same field names, and its flow rules.
    flowTemplate = await createTemplate(operatorApi, {
      name: uniqueName('Contracts'),
      fields: ['name', 'email', 'status'],
    });
    await operatorApi.put('/ValidationRules/Upsert', {
      templateId: flowTemplate.templateId,
      fieldName: 'name',
      ruleType: 'REGEX',
      config: { Pattern: NAME_PATTERN },
    });
    await operatorApi.put('/ValidationRules/Upsert', {
      templateId: flowTemplate.templateId,
      fieldName: 'email',
      ruleType: 'REQUIRED',
      config: {},
    });

    await openAs(page, OPERATOR(), `/creation/blueprints/${blueprintId}`);
    await page.getByRole('tab', { name: 'Field rules' }).click();
    const rulesTab = page.getByTestId('field-rules');
    await rulesTab.getByRole('button', { name: 'Import rules' }).click();
    const dialog = page.getByRole('dialog', { name: 'Import rules' });
    await dialog.getByRole('radio', { name: 'A flow template' }).check();
    // (the select is labelled "Flow template"; the radio "A flow template" also matches a loose label)
    const templateSelect = dialog.getByRole('combobox', { name: 'Flow template' });
    await expect(templateSelect).toBeEnabled();
    await templateSelect.selectOption({ label: flowTemplate.name });
    await dialog.getByRole('button', { name: 'Show what would be imported' }).click();

    // The preview: the same-name fields matched by themselves, with the rules each brings.
    const nameRow = dialog.getByTestId('import-row-name');
    const emailRow = dialog.getByTestId('import-row-email');
    await expect(nameRow).toBeVisible();
    await expect(nameRow.getByLabel('Template field for Name')).toHaveValue('name');
    await expect(nameRow.getByText('same name')).toBeVisible();
    await expect(nameRow.getByText(`Matches ${NAME_PATTERN}`)).toBeVisible();
    await expect(emailRow.getByLabel('Template field for E-mail')).toHaveValue('email');
    await expect(emailRow.getByText('same name')).toBeVisible();
    await expect(emailRow.getByText('Must be filled in')).toBeVisible();
    await expect(dialog.getByTestId('import-row-status').getByLabel('Template field for Status')).toHaveValue('status');
    await expect(dialog.getByTestId('import-row-status').getByText('No rules to import.')).toBeVisible();

    await dialog.getByRole('button', { name: /^Import rules of \d+ fields?$/ }).click();
    await expect(page.getByText('Rules imported')).toBeVisible();
    await expect(dialog).toBeHidden();

    const origin = (field) => `Imported from ${flowTemplate.name}.${field}`;
    const nameRules = rulesTab.getByTestId('rules-field-name');
    await expect(nameRules.getByRole('cell', { name: 'Pattern', exact: true })).toBeVisible();
    await expect(nameRules.getByText(origin('name'))).toBeVisible();
    await expect(nameRules.getByText('Manual')).toBeVisible(); // the hand-written REQUIRED stays (MERGE)
    const emailRules = rulesTab.getByTestId('rules-field-email');
    await expect(emailRules.getByRole('cell', { name: 'Required', exact: true })).toBeVisible();
    await expect(emailRules.getByText(origin('email'))).toBeVisible();

    const rules = await operatorApi.get(`${DOCS}/blueprints/${blueprintId}/rules`);
    expect(rules.revision).toBe(2);
    const imported = rules.rules.filter((r) => r.origin === 'IMPORTED');
    expect(imported.map((r) => [r.regionKey, r.ruleType, r.sourceTemplateId, r.sourceFieldName]).sort()).toEqual(
      [
        ['email', 'REQUIRED', flowTemplate.templateId, 'email'],
        ['name', 'REGEX', flowTemplate.templateId, 'name'],
      ].sort()
    );
    expect(imported.find((r) => r.ruleType === 'REGEX').config).toEqual({ Pattern: NAME_PATTERN });
    expect(rules.rules.filter((r) => r.origin === 'MANUAL')).toHaveLength(3);
  });

  test('VAL-03 builder: errors keep the document a draft; Generate and Download wait for a passing Save', async ({
    page,
    operatorApi,
  }) => {
    test.skip(!blueprintId, 'needs the template from VAL-01');
    instanceTitle = uniqueName('E2E Contract');
    await openAs(page, OPERATOR(), '/creation/documents');
    const heading = page.getByRole('heading', { name: 'New document', level: 2 });
    await expect(async () => {
      if (!(await heading.isVisible())) await page.getByRole('button', { name: 'New document' }).click({ timeout: 2000 });
      await expect(heading).toBeVisible({ timeout: 2000 });
    }).toPass({ timeout: 30_000 });
    await page.getByLabel('Template').selectOption({ label: `${blueprintName} (1 page)` });
    await page.getByPlaceholder(/Survey/).fill(instanceTitle);
    await page.getByRole('button', { name: 'Create document' }).click();
    await expect(page).toHaveURL(/\/creation\/documents\/\d+$/);
    instanceId = Number(new URL(page.url()).pathname.split('/').pop());
    await expect(page.getByRole('heading', { name: instanceTitle })).toBeVisible();

    // A new document is a draft: the gates are closed before anything is typed.
    await expect(page.getByText('DRAFT', { exact: true })).toBeVisible();
    await expect(button(page, 'Generate')).toBeDisabled();
    await expect(button(page, 'Download PDF')).toBeDisabled();
    await expect(page.locator('#gate-reason')).toHaveText('Save and validate first');

    // The dropdown offers exactly the template's options.
    const status = page.getByTestId('field-status').locator('select');
    await expect(status.locator('option')).toHaveText(['', ...STATUS_OPTIONS]);
    await expect(page.getByTestId('field-start_date').locator('input')).toHaveAttribute('type', 'date');

    // Name left empty, a malformed e-mail, an end date before the start date.
    await page.getByTestId('field-email').locator('input').fill('jane.doe-at-example');
    await page.getByTestId('field-start_date').locator('input').fill(GOOD.start_date);
    await page.getByTestId('field-end_date').locator('input').fill('2025-12-31');
    await status.selectOption(GOOD.status);
    await expect(page.getByTestId('save-state')).toHaveText('Saved');

    const result = await saveAndValidate(page, { expectValidated: false });
    expect(result.status).toBe('DRAFT');
    expect(result.validation).toMatchObject({ state: 'DRAFT', checked: true, rulesRevision: 2 });
    expect(result.validation.errors.map((e) => [e.regionKey, e.code]).sort()).toEqual(
      [
        ['email', 'EMAIL'],
        ['end_date', 'COMPARE'],
        ['name', 'REQUIRED'],
      ].sort()
    );
    // Messages never carry the value.
    for (const error of result.validation.errors) expect(error.message).not.toContain('jane.doe-at-example');

    await expect(page.getByText('Saved as a draft — 3 issues to fix before generating')).toBeVisible();
    await expect(page.getByText('DRAFT', { exact: true })).toBeVisible();
    const issues = page.getByTestId('issues-panel');
    await expect(issues.getByRole('heading', { name: 'Issues (3 errors)' })).toBeVisible();
    await expect(issues.getByText('Name is required.')).toBeVisible();
    await expect(issues.getByText('E-mail must be an e-mail address.')).toBeVisible();
    await expect(issues.getByRole('button', { name: 'Go to field: End date' })).toBeVisible();
    await expect(page.getByTestId('field-messages-name')).toContainText('Name is required.');
    await expect(page.getByTestId('field-email').locator('input')).toHaveAttribute('aria-invalid', 'true');

    await expect(button(page, 'Generate')).toBeDisabled();
    await expect(button(page, 'Generate')).toHaveAttribute('title', 'Save and validate first');
    await expect(button(page, 'Download PDF')).toBeDisabled();
    await expect(page.locator('#gate-reason')).toHaveText('Save and validate first');

    // The server refuses too.
    for (const [method, route] of [
      ['POST', 'generate'],
      ['GET', 'download'],
    ]) {
      const refused = await operatorApi.request(method, `${DOCS}/instances/${instanceId}/${route}`);
      expect(refused.status, route).toBe(409);
      expect(refused.body).toMatchObject({ code: 'NOT_VALIDATED' });
    }
    expect((await operatorApi.get(`${DOCS}/instances/${instanceId}`)).status).toBe('DRAFT');
  });

  test('VAL-04 fixed values validate; the PDF shows the dropdown value and the date as dd/MM/yyyy', async ({
    page,
    operatorApi,
  }) => {
    test.skip(!instanceId, 'needs the document from VAL-03');
    await openAs(page, OPERATOR(), `/creation/documents/${instanceId}`);
    await expect(page.getByRole('heading', { name: instanceTitle })).toBeVisible();
    // The last result is shown again on opening.
    await expect(page.getByTestId('issues-panel').getByRole('heading', { name: 'Issues (3 errors)' })).toBeVisible();

    await page.getByTestId('field-name').locator('input').fill(GOOD.name);
    await page.getByTestId('field-email').locator('input').fill(GOOD.email);
    await page.getByTestId('field-end_date').locator('input').fill(GOOD.end_date);
    await expect(page.getByTestId('save-state')).toHaveText('Saved');
    const dto = await saveAndValidate(page);
    expect(dto.validation).toMatchObject({ state: 'VALIDATED', checked: true, errors: [] });
    expect(dto.values).toMatchObject(GOOD);
    await expect(page.getByText('Saved and validated')).toBeVisible();
    await expect(page.getByTestId('issues-panel')).toHaveCount(0);
    await expect(page.locator('#gate-reason')).toHaveCount(0);
    await expect(button(page, 'Download PDF')).toBeEnabled();

    const generated = page.waitForResponse((r) => r.url().endsWith(`/instances/${instanceId}/generate`));
    await button(page, 'Generate').click();
    const generation = await generated;
    expect(generation.status()).toBe(200);
    expect((await generation.json()).pdfReady).toBe(true);
    await expect(page.getByText('GENERATED', { exact: true })).toBeVisible();

    const downloadEvent = page.waitForEvent('download');
    await button(page, 'Download PDF').click();
    const pdf = await fs.readFile(await (await downloadEvent).path());
    expect(pdf.subarray(0, 5).toString('latin1')).toBe('%PDF-');
    const text = pageTexts(pdf)[0];
    expect(text).toContain('Contract Form');
    for (const value of [GOOD.name, GOOD.status]) {
      expect(containsText(text, value), `the PDF shows "${value}"`).toBe(true);
    }
    // REPORT masking applies to the e-mail address in the PDF (as for NICs in flow-records REC-05).
    expect(containsText(text, GOOD.email)).toBe(false);
    expect(containsText(text, 'j***@example.com')).toBe(true);
    // Start date in the field's format; End date (no format set) as ISO.
    expect(containsText(text, '15/01/2026'), 'start date drawn as dd/MM/yyyy').toBe(true);
    expect(containsText(text, GOOD.start_date)).toBe(false);
    expect(containsText(text, GOOD.end_date)).toBe(true);
    expect((await operatorApi.get(`${DOCS}/instances/${instanceId}`)).status).toBe('GENERATED');
  });

  test('VAL-05 an edit after validation makes it a draft again; Save validates it again', async ({ page, operatorApi }) => {
    test.skip(!instanceId, 'needs the document from VAL-04');
    await openAs(page, OPERATOR(), `/creation/documents/${instanceId}`);
    await expect(page.getByText('GENERATED', { exact: true })).toBeVisible();
    await expect(button(page, 'Generate')).toBeEnabled();

    await page.getByTestId('field-status').locator('select').selectOption('Suspended');
    // At once in the page ...
    await expect(page.getByText('DRAFT', { exact: true })).toBeVisible();
    await expect(button(page, 'Generate')).toBeDisabled();
    await expect(button(page, 'Download PDF')).toBeDisabled();
    await expect(page.locator('#gate-reason')).toHaveText('Save and validate first');
    // ... and on the server once the change is stored.
    await expect(page.getByTestId('save-state')).toHaveText('Saved');
    const changed = await operatorApi.get(`${DOCS}/instances/${instanceId}`);
    expect(changed).toMatchObject({ status: 'DRAFT', values: { status: 'Suspended' } });
    const refused = await operatorApi.request('POST', `${DOCS}/instances/${instanceId}/generate`);
    expect(refused.status).toBe(409);
    expect(refused.body).toMatchObject({ code: 'NOT_VALIDATED' });

    await saveAndValidate(page);
    await expect(button(page, 'Download PDF')).toBeEnabled();
    expect((await operatorApi.get(`${DOCS}/instances/${instanceId}`)).status).toBe('VALIDATED');
  });

  test('VAL-06 editor tools: multi-select, align left, undo, duplicate; saved', async ({ page, operatorApi }) => {
    test.skip(!blueprintId, 'needs the template from VAL-01');
    const before = await getBlueprint(operatorApi, blueprintId);
    await openAs(page, OPERATOR(), `/creation/blueprints/${blueprintId}`);
    await expect(page.getByRole('heading', { name: blueprintName })).toBeVisible();
    const toolbar = page.getByRole('toolbar', { name: 'Arrange fields' });
    const nameBox = page.getByTestId('region-name');
    const startBox = page.getByTestId('region-start_date');
    await expect(nameBox).toBeVisible();

    // Move the start date right first, so aligning has something to do.
    const startX = (await startBox.boundingBox()).x;
    await startBox.click();
    await startBox.evaluate((el) => el.scrollIntoView({ block: 'center' }));
    const box = await startBox.boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width / 2 + 30, box.y + box.height / 2, { steps: 4 });
    await page.mouse.move(box.x + box.width / 2 + 60, box.y + box.height / 2, { steps: 4 });
    await page.mouse.up();
    await expect.poll(async () => (await startBox.boundingBox()).x).toBeGreaterThan(startX + 30);
    const movedX = (await startBox.boundingBox()).x;
    const nameX = (await nameBox.boundingBox()).x;

    // Two fields: click one, Shift-click the other.
    await nameBox.click();
    await startBox.click({ modifiers: ['Shift'] });
    await expect(toolbar.getByText('2 fields selected')).toBeVisible();
    await expect(nameBox).toHaveAttribute('aria-pressed', 'true');
    await expect(startBox).toHaveAttribute('aria-pressed', 'true');
    await toolbar.getByRole('button', { name: 'Align left' }).click();
    await expect.poll(async () => Math.abs((await startBox.boundingBox()).x - nameX)).toBeLessThan(1.5);

    // Undo (Ctrl+Z) puts it back where it was.
    await page.keyboard.press('Control+z');
    await expect.poll(async () => Math.abs((await startBox.boundingBox()).x - movedX)).toBeLessThan(1.5);

    // Duplicate (Ctrl+D) one field: one more field on the page.
    const countBefore = await page.locator('[data-testid^="region-"][data-kind]').count();
    expect(countBefore).toBe(before.regions.length);
    await page.getByTestId('region-email').click();
    await expect(toolbar.getByText(/fields selected/)).toHaveCount(0);
    await page.keyboard.press('Control+d');
    await expect(page.locator('[data-testid^="region-"][data-kind]')).toHaveCount(countBefore + 1);
    await expect(page.getByText(/Unsaved changes/)).toBeVisible();

    await page.getByRole('button', { name: 'Save as new version' }).click();
    await expect(page.getByText(`Saved as version ${before.currentVersion + 1}`)).toBeVisible();
    const after = await getBlueprint(operatorApi, blueprintId);
    expect(after.currentVersion).toBe(before.currentVersion + 1);
    expect(after.regions).toHaveLength(before.regions.length + 1);
    const added = after.regions.find((r) => !before.regions.some((b) => b.key === r.key));
    expect(added).toMatchObject({ kind: 'TEXT', page: 0 });
    // The undone alignment is not saved: the start date keeps its move, right of the name.
    const byKey = Object.fromEntries(after.regions.map((r) => [r.key, r]));
    const beforeByKey = Object.fromEntries(before.regions.map((r) => [r.key, r]));
    expect(byKey.start_date.fx).toBeGreaterThan(beforeByKey.start_date.fx + 0.02);
    expect(byKey.start_date.fx).toBeGreaterThan(byKey.name.fx + 0.02);
    // The properties survive the new version.
    expect(byKey.status).toMatchObject({ kind: 'CHOICE', props: { options: STATUS_OPTIONS } });
  });

  test('VAL-07 READ_ONLY cannot change rules (403); another bank cannot see them (404)', async ({
    operatorApi,
    platformApi,
    platformCheckerApi,
  }) => {
    test.skip(!blueprintId, 'needs the template from VAL-01');
    const current = await operatorApi.get(`${DOCS}/blueprints/${blueprintId}/rules`);
    const body = {
      baseRevision: current.revision,
      rules: [{ regionKey: 'name', ruleType: 'REQUIRED', config: {} }],
    };

    const { email } = await createUserViaMakerChecker({ role: 'READ_ONLY', displayName: 'E2E rules reader' });
    const reader = await sessionFor(email);
    expect((await reader.request('PUT', `${DOCS}/blueprints/${blueprintId}/rules`, { json: body })).status).toBe(403);
    const importAttempt = await reader.request('POST', `${DOCS}/blueprints/${blueprintId}/rules/import`, {
      json: { baseRevision: current.revision, mapping: {}, mode: 'MERGE' },
    });
    expect(importAttempt.status).toBe(403);
    // Nothing changed.
    const unchanged = await operatorApi.get(`${DOCS}/blueprints/${blueprintId}/rules`);
    expect(unchanged.revision).toBe(current.revision);
    expect(unchanged.rules).toHaveLength(current.rules.length);

    if (!platformApi) {
      test.info().annotations.push({ type: 'skipped-part', description: 'no platform admins: second bank not checked' });
      return;
    }
    const bankB = await onboardBank(platformApi, platformCheckerApi);
    const adminB = await sessionFor(bankB.admins[0]);
    expect((await adminB.request('GET', `${DOCS}/blueprints/${blueprintId}/rules`)).status).toBe(404);
    expect((await adminB.request('PUT', `${DOCS}/blueprints/${blueprintId}/rules`, { json: body })).status).toBe(404);
    expect(
      (await adminB.request('POST', `${DOCS}/blueprints/${blueprintId}/rules/test`, { json: { values: {} } })).status
    ).toBe(404);
    // ... nor the document.
    if (instanceId) {
      expect((await adminB.request('POST', `${DOCS}/instances/${instanceId}/validate`)).status).toBe(404);
    }
    const stillThere = await getBytes(operatorApi, `${DOCS}/blueprints/${blueprintId}/rules`);
    expect(stillThere.status).toBe(200);
  });
});
