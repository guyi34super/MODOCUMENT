// The document creation flow in the user's order (docs/doccreation/
// data-templates-contract.md, ws34): the sidebar "Document Creation" steps
// 1 "Upload document", 2 "Validation", 3 "Map fields", 4 "Fix documents";
// a reusable data template (fields + rules) copied from a flow template;
// the PDF template's steps Upload / Validation / Map fields / Documents; the
// builder's steps Choose data / Fix fields / Save & validate / Download, with
// Download locked until the document is validated with the current rules.
//
// Setup through the API only: the flow template "Staff" (full_name,
// email, start_date, status) with flow rules (REQUIRED on full_name, the
// e-mail preset REGEX on email) and one validated ingestion record whose
// full name is too long for the LENGTH rule the user adds by hand.
//
//   DCF-01 Upload document: sidebar step 1, upload the "Staff onboarding"
//          PDF, confirm -> lands on the template's Validation step
//   DCF-02 Validation: "Copy from a flow template" -> the fields ticked ->
//          Copy; the data template lists the imported rules ("Imported from
//          Staff.full_name"); a LENGTH max 20 rule is added by hand and saved;
//          chosen on the PDF template's Validation step, where the rules show
//          read-only under "Inherited from <data template>"
//   DCF-03 Map fields: "same name" badges pre-filled, "4 of 4 mapped"; one
//          row unmapped and saved ("3 of 4 mapped" in the Map fields list);
//          "Accept all suggestions" -> Save mapping -> "4 of 4 mapped"; the
//          managed source under "Data from the flow (advanced)"
//   DCF-04 Fix documents: Documents step -> "Create a document" -> Choose
//          data: search, preview "Full name ← Staff.full_name: …" with ⚠ on
//          the too-long name -> Apply; Download locked ("Save and validate
//          first"); Save & validate lists the error with "Go to field"; fixed
//          -> Validated -> Download unlocked -> Generate + Download PDF (the
//          PDF carries the values)
//   DCF-05 an edit locks Download again; My documents: Validation column
//          "Not checked" / "1 error" / "Validated", Download only for the
//          validated document
//   DCF-06 a rule of the data template changes -> "The rules changed — save
//          again", generate 409 NOT_VALIDATED (rules-changed message); Save
//          again -> unlocked
//   DCF-07 READ_ONLY cannot save a data template (403); another bank gets
//          404 for the data template and the mapping
//
// UI contract (frontend/src/pages/{DataTemplatesPage,DataTemplateEditorPage,
// DataMappingListPage,BlueprintDetailPage,DocBuilderPage,DocInstancesPage},
// components/{mapping/FieldMatcher,doc/BuilderSteps,blueprint/
// DataTemplateChooser,dataTemplates/DataTemplateDialogs}): steps are
// role=tab ("Upload", "Validation", "Map fields", "Documents"; "Choose
// data", "Fix fields", "Save & validate", "Download", aria-disabled when
// locked, the reason in #gate-reason); copy dialog rows copy-field-<name>;
// editor rows data-field-<name>, "Edit rules of <name>", drawer "Rules —
// <name>"; chooser data-template-chooser / data-template-summary; inherited
// rules inherited-rules / inherited-field-<key>; matcher field-matcher,
// match-row-<key>, mapped-count; Choose data choose-data, fill-row-<key>,
// would-fail-<key>; Save step save-step / save-result; Download step
// download-step; lists validation-state.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test, expect, openAs } from '../../lib/fixtures.js';
import { createTemplate, sessionFor, uploadForIngestion, waitForJob } from '../../lib/api.js';
import { OPERATOR, createUserViaMakerChecker, operator, uniqueName } from '../../lib/seed.js';
import { onboardBank } from '../../lib/phase2.js';
import { buildPdf } from '../../fixtures/pdf-form.js';
import { containsText, pageTexts } from '../../lib/pdf-inspect.js';
import { getBlueprint } from '../../lib/doccreation.js';

const DOCS = '/api/doccreation';
const DATA_TEMPLATES = `${DOCS}/data-templates`;
const FIELDS = ['full_name', 'email', 'start_date', 'status'];
// The flow UI's e-mail preset: copied as an EMAIL rule (FlowRuleConverter.FLOW_EMAIL_PATTERN).
const EMAIL_PATTERN = '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$';
const MAX_NAME = 20;

// Passes the flow's rules, but the full name is longer than the LENGTH max 20 added on the data template.
const PERSON = {
  full_name: 'Maximilian Alexander Worthington',
  email: 'max.worthington@example.com',
  start_date: '2026-02-01',
  status: 'Active',
};
const FIXED_NAME = 'Max Worthington';

/** The one-page "Staff onboarding" form: four labelled places to fill in. */
function onboardingForm() {
  return buildPdf((addPage) => {
    const p = addPage();
    p.text(72, 784, 'Staff Onboarding Form', { size: 16, font: 'F2' });
    p.text(72, 750, 'New starter', { size: 12, font: 'F2' });
    p.text(72, 715, 'Full name: ______________________________');
    p.text(72, 690, 'Email: ______________________________');
    p.text(72, 665, 'Start date: ____________');
    p.text(72, 640, 'Status:');
    p.rect(170, 635, 150, 18);
    p.text(72, 200, 'Please check every detail before signing.');
  });
}

/** A CSV file (header + rows) in a fresh temp dir. */
function writeCsv(name, columns, rows) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'modoc-e2e-dcf-'));
  const file = path.join(dir, name);
  fs.writeFileSync(file, [columns.join(','), ...rows.map((r) => columns.map((c) => r[c] ?? '').join(','))].join('\n') + '\n');
  return file;
}

/** A sidebar link of "Document Creation" by its route. */
const sidebarLink = (page, href) => page.getByRole('complementary', { name: 'Main navigation' }).locator(`a[href="${href}"]`);

/** The "Document steps" / "Template steps" tab of a step. */
const stepTab = (page, name) => page.getByRole('tab', { name, exact: true });

/** One row of a DataTable, by a text it shows (e.g. a title). */
const rowWith = (page, text) => page.getByRole('row').filter({ hasText: text });

test.describe.configure({ mode: 'serial' });

test.describe('document creation flow: data templates, mapping, validated download @phase2 @datatemplates', () => {
  let flow; // {templateId, name}
  let recordId;
  let blueprintId;
  let blueprintName;
  let dataTemplateId;
  let dataTemplateName;
  let instanceId; // the document built through the UI
  let instanceTitle;

  test.beforeAll(async () => {
    // Setup (API): the flow template with its flow rules and one validated record.
    const api = await operator();
    flow = await createTemplate(api, { name: uniqueName('Staff'), fields: FIELDS });
    await api.put('/ValidationRules/Upsert', {
      templateId: flow.templateId,
      fieldName: 'full_name',
      ruleType: 'REQUIRED',
      config: {},
    });
    await api.put('/ValidationRules/Upsert', {
      templateId: flow.templateId,
      fieldName: 'email',
      ruleType: 'REGEX',
      config: { Pattern: EMAIL_PATTERN },
    });
    const created = await uploadForIngestion(api, {
      filePath: writeCsv(`${uniqueName('staff')}.csv`, FIELDS, [PERSON]),
      templateId: flow.templateId,
      runUntil: 'VALIDATED',
      wait: true,
    });
    const job = await waitForJob(api, created.ingestionJobId, (j) => j.currentStep === 'VALIDATED');
    expect(job.validationErrorCount ?? 0, 'the person passes the flow rules').toBe(0);
    await expect
      .poll(async () => (await api.get(`${DOCS}/records?templateId=${flow.templateId}`)).total, { timeout: 30_000 })
      .toBe(1);
    recordId = (await api.get(`${DOCS}/records?templateId=${flow.templateId}`)).items[0].recordId;
  });

  test('DCF-01 Upload document: sidebar step 1, upload a PDF, confirm -> the Validation step', async ({ page, operatorApi }) => {
    blueprintName = uniqueName('E2E Staff onboarding');
    await openAs(page, OPERATOR(), '/');
    const steps = [
      ['/creation/blueprints', 'Upload document'],
      ['/creation/data-templates', 'Validation'],
      ['/creation/mapping', 'Map fields'],
      ['/creation/documents', 'Fix documents'],
    ];
    for (const [i, [href, label]] of steps.entries()) {
      await expect(sidebarLink(page, href)).toHaveText(`${i + 1}${label}`);
    }
    await sidebarLink(page, '/creation/blueprints').click();
    await expect(page).toHaveURL(/\/creation\/blueprints$/);

    const heading = page.getByRole('heading', { name: 'Upload a template', level: 2 });
    await expect(async () => {
      if (!(await heading.isVisible())) await page.getByRole('button', { name: 'Upload a template' }).click({ timeout: 2000 });
      await expect(heading).toBeVisible({ timeout: 2000 });
    }).toPass({ timeout: 30_000 });
    await page.getByLabel('PDF file').setInputFiles({
      name: 'staff-onboarding.pdf',
      mimeType: 'application/pdf',
      buffer: onboardingForm(),
    });
    await page.getByPlaceholder('e.g. Loan application').fill(blueprintName);
    await page.getByRole('radio', { name: 'Blank template' }).check();
    await page.getByRole('button', { name: 'Upload and review' }).click();
    await expect(page).toHaveURL(/\/creation\/blueprints\/\d+$/, { timeout: 60_000 });
    blueprintId = Number(new URL(page.url()).pathname.split('/').pop());
    await expect(page.getByRole('heading', { name: blueprintName })).toBeVisible();

    const draft = await getBlueprint(operatorApi, blueprintId);
    expect(draft.status).toBe('DRAFT');
    expect(draft.regions.filter((r) => !r.suggested).map((r) => r.key).sort()).toEqual([...FIELDS].sort());

    // The four template steps; Upload is the one on screen until the PDF is confirmed.
    for (const name of ['Upload', 'Validation', 'Map fields', 'Documents']) await expect(stepTab(page, name)).toBeVisible();
    await expect(stepTab(page, 'Upload')).toHaveAttribute('aria-selected', 'true');
    await page.getByRole('button', { name: 'Confirm template' }).first().click();
    await expect(page.getByText('Template confirmed — it is ready for new documents')).toBeVisible();
    await expect(page).toHaveURL(/[?&]step=validation/);
    await expect(stepTab(page, 'Validation')).toHaveAttribute('aria-selected', 'true');
    await expect(stepTab(page, 'Upload')).toHaveAttribute('data-done', 'true');
    await expect(page.getByTestId('data-template-chooser')).toBeVisible();
    expect(await getBlueprint(operatorApi, blueprintId)).toMatchObject({ status: 'READY', currentVersion: 1 });
  });

  test('DCF-02 Validation: copy a flow template into a data template, add a rule, choose it on the PDF template', async ({
    page,
    operatorApi,
  }) => {
    test.skip(!blueprintId, 'needs DCF-01');
    dataTemplateName = uniqueName('Staff data');
    await openAs(page, OPERATOR(), '/');
    await sidebarLink(page, '/creation/data-templates').click();
    await expect(page.getByRole('heading', { name: 'Validation', level: 1 })).toBeVisible();

    // Copy from a flow template: every field ticked, with the rules each brings.
    await page.getByRole('button', { name: 'Copy from a flow template' }).click();
    const dialog = page.getByRole('dialog', { name: 'Copy from a flow template' });
    await dialog.getByRole('combobox', { name: 'Flow template' }).selectOption({ label: flow.name });
    for (const name of FIELDS) {
      await expect(dialog.getByTestId(`copy-field-${name}`).getByRole('checkbox')).toBeChecked();
    }
    await expect(dialog.getByText('All fields (4 of 4 ticked)')).toBeVisible();
    await expect(dialog.getByTestId('copy-field-full_name').getByText('Required', { exact: true })).toBeVisible();
    await expect(dialog.getByTestId('copy-field-email').getByText('E-mail address', { exact: true })).toBeVisible();
    await dialog.getByLabel('Name of the new data template').fill(dataTemplateName);
    await dialog.getByRole('button', { name: 'Copy 4 fields' }).click();
    await expect(page.getByText(`Copied 4 fields into ${dataTemplateName}`)).toBeVisible();
    await expect(page).toHaveURL(/\/creation\/data-templates\/\d+$/);
    dataTemplateId = Number(new URL(page.url()).pathname.split('/').pop());
    await expect(page.getByRole('heading', { name: dataTemplateName })).toBeVisible();
    await expect(page.getByText(`Copied from the flow template ${flow.name}.`)).toBeVisible();

    // The fields and their imported rules.
    for (const name of FIELDS) await expect(page.getByTestId(`data-field-${name}`)).toBeVisible();
    await expect(page.getByRole('list', { name: 'Rules of full_name' })).toContainText('Must be filled in');
    await expect(page.getByRole('list', { name: 'Rules of email' })).toContainText('An e-mail address');
    await expect(page.getByTestId('data-field-status').getByText('No rules')).toBeVisible();

    // The rules drawer: the origin of the imported rule, and a LENGTH rule added by hand.
    await page.getByRole('button', { name: 'Edit rules of full_name' }).click();
    const drawer = page.getByRole('dialog', { name: 'Rules — full_name' });
    await expect(drawer.getByText(`Imported from ${flow.name}.full_name`)).toBeVisible();
    await drawer.getByRole('button', { name: 'Add rule to full_name' }).click();
    const ruleDialog = page.getByRole('dialog', { name: 'Add a rule — full_name' });
    await ruleDialog.getByLabel('Rule type').selectOption('LENGTH');
    await ruleDialog.getByLabel('Maximum length').fill(String(MAX_NAME));
    await ruleDialog.getByRole('button', { name: 'Add rule', exact: true }).click();
    await expect(drawer.getByText(`Length at most ${MAX_NAME} characters`)).toBeVisible();
    await expect(drawer.getByText('Manual', { exact: true })).toBeVisible();
    await drawer.getByRole('button', { name: 'Done' }).click();
    await expect(page.getByText('Unsaved changes: rules.')).toBeVisible();
    await page.getByRole('button', { name: 'Save rules' }).click();
    await expect(page.getByText('Rules saved')).toBeVisible();
    await expect(page.getByRole('list', { name: 'Rules of full_name' })).toContainText(`Length at most ${MAX_NAME} characters`);

    const saved = await operatorApi.get(`${DATA_TEMPLATES}/${dataTemplateId}`);
    expect(saved).toMatchObject({ name: dataTemplateName, sourceTemplateId: flow.templateId, revision: 1 });
    expect(saved.fields.map((f) => [f.name, f.sourceFieldName])).toEqual(FIELDS.map((f) => [f, f]));
    expect(saved.rules.map((r) => [r.fieldName, r.ruleType, r.origin])).toEqual(
      expect.arrayContaining([
        ['full_name', 'REQUIRED', 'IMPORTED'],
        ['email', 'EMAIL', 'IMPORTED'],
        ['full_name', 'LENGTH', 'MANUAL'],
      ])
    );
    expect(saved.rules).toHaveLength(3);

    // The Validation list shows it.
    await sidebarLink(page, '/creation/data-templates').click();
    const listed = rowWith(page, dataTemplateName);
    await expect(listed).toContainText(flow.name);
    await expect(listed).toContainText('Not used yet');

    // Choose it on the PDF template's Validation step.
    await sidebarLink(page, '/creation/blueprints').click();
    await page.getByRole('link', { name: blueprintName, exact: true }).click();
    await expect(page.getByRole('heading', { name: blueprintName })).toBeVisible();
    await stepTab(page, 'Validation').click();
    const chooser = page.getByTestId('data-template-chooser');
    await chooser.getByRole('combobox', { name: 'Data template' }).selectOption({ label: dataTemplateName });
    await expect(page.getByText('Data template chosen — 4 of 4 fields matched automatically')).toBeVisible();
    await expect(page.getByTestId('data-template-summary')).toContainText('4 of 4 PDF fields mapped');
    await expect(stepTab(page, 'Validation')).toHaveAttribute('data-done', 'true');

    // Its rules are inherited, read-only, above the PDF's own rules.
    const inherited = page.getByTestId('inherited-rules');
    await expect(inherited.getByRole('heading', { name: `Inherited from ${dataTemplateName}` })).toBeVisible();
    const nameRules = inherited.getByTestId('inherited-field-full_name');
    await expect(nameRules.getByText('Must be filled in')).toBeVisible();
    await expect(nameRules.getByText(`Length at most ${MAX_NAME} characters`)).toBeVisible();
    await expect(inherited.getByTestId('inherited-field-email').getByText('An e-mail address')).toBeVisible();
    await expect(inherited.getByRole('button', { name: /^(Edit|Delete) / })).toHaveCount(0);

    const rules = await operatorApi.get(`${DOCS}/blueprints/${blueprintId}/rules`);
    expect(rules.rules, 'the PDF has no rules of its own').toEqual([]);
    expect(rules.inherited.map((r) => [r.regionKey, r.ruleType, r.origin])).toEqual(
      expect.arrayContaining([
        ['full_name', 'REQUIRED', 'DATA_TEMPLATE'],
        ['full_name', 'LENGTH', 'DATA_TEMPLATE'],
        ['email', 'EMAIL', 'DATA_TEMPLATE'],
      ])
    );
  });

  test('DCF-03 Map fields: same-name matches, a change saved, Accept all suggestions, the managed data source', async ({
    page,
    operatorApi,
  }) => {
    test.skip(!dataTemplateId, 'needs DCF-02');
    await openAs(page, OPERATOR(), `/creation/blueprints/${blueprintId}`);
    await stepTab(page, 'Map fields').click();
    await expect(page).toHaveURL(/[?&]step=mapping/);
    const matcher = page.getByTestId('field-matcher');
    await expect(matcher.getByRole('heading', { name: `Match the PDF fields to ${dataTemplateName}` })).toBeVisible();
    await expect(matcher.getByTestId('mapped-count')).toHaveText('4 of 4 mapped');
    for (const key of FIELDS) {
      const row = matcher.getByTestId(`match-row-${key}`);
      await expect(row.getByRole('combobox')).toHaveValue(key);
      await expect(row.getByText('same name', { exact: true })).toBeVisible();
    }
    await expect(matcher.getByRole('button', { name: /^Accept all suggestions/ })).toHaveCount(0);
    await expect(matcher.getByRole('button', { name: 'Save mapping' })).toBeDisabled();

    // Change one mapping: Status is not mapped any more -> amber, 3 of 4; saved.
    const statusRow = matcher.getByTestId('match-row-status');
    await statusRow.getByRole('combobox').selectOption('');
    await expect(statusRow.getByText('not mapped', { exact: true })).toBeVisible();
    await expect(statusRow.getByText('suggestion: status')).toBeVisible();
    await expect(matcher.getByTestId('mapped-count')).toHaveText('3 of 4 mapped');
    await expect(matcher.getByText('Unsaved mapping changes.')).toBeVisible();
    await matcher.getByRole('button', { name: 'Save mapping' }).click();
    await expect(page.getByText('Mapping saved — 3 of 4 mapped')).toBeVisible();
    expect((await operatorApi.get(`${DOCS}/blueprints/${blueprintId}/data-mapping`)).mappedCount).toBe(3);

    // The "Map fields" list (sidebar step 3) counts it.
    await sidebarLink(page, '/creation/mapping').click();
    await expect(page.getByRole('heading', { name: 'Map fields', level: 1 })).toBeVisible();
    const listed = rowWith(page, blueprintName);
    await expect(listed).toContainText(dataTemplateName);
    await expect(listed).toContainText('3 of 4 mapped');
    await listed.getByRole('link', { name: `Map the fields of ${blueprintName}` }).click();
    await expect(page).toHaveURL(new RegExp(`/creation/blueprints/${blueprintId}\\?step=mapping`));

    // Accept all suggestions puts Status back; Save mapping.
    const acceptAll = matcher.getByRole('button', { name: 'Accept all suggestions (1)' });
    await acceptAll.click();
    await expect(statusRow.getByRole('combobox')).toHaveValue('status');
    await expect(statusRow.getByText('same name', { exact: true })).toBeVisible();
    await expect(matcher.getByTestId('mapped-count')).toHaveText('4 of 4 mapped');
    await matcher.getByRole('button', { name: 'Save mapping' }).click();
    await expect(page.getByText('Mapping saved — 4 of 4 mapped')).toBeVisible();
    await expect(stepTab(page, 'Map fields')).toHaveAttribute('data-done', 'true');

    // The managed data source of the flow template, under "Data from the flow (advanced)".
    await page.getByRole('button', { name: 'Data from the flow (advanced)' }).click();
    const card = page.getByTestId(`source-${flow.templateId}`);
    await expect(card.getByText('Primary', { exact: true })).toBeVisible();
    await expect(card.getByText('kept in step with the data template')).toBeVisible();

    const mapping = await operatorApi.get(`${DOCS}/blueprints/${blueprintId}/data-mapping`);
    expect(mapping).toMatchObject({ dataTemplateId, dataTemplateName, mappedCount: 4, mappableCount: 4 });
    const sources = await operatorApi.get(`${DOCS}/blueprints/${blueprintId}/sources`);
    expect(sources).toHaveLength(1);
    expect(sources[0]).toMatchObject({ templateId: flow.templateId, role: 'PRIMARY', managed: true });
    expect(sources[0].mapping).toEqual(Object.fromEntries(FIELDS.map((f) => [f, f])));

    await sidebarLink(page, '/creation/mapping').click();
    await expect(rowWith(page, blueprintName)).toContainText('4 of 4 mapped');
  });

  test('DCF-04 Fix documents: Choose data with ⚠, Download locked until Save & validate passes, then the PDF', async ({
    page,
    operatorApi,
  }) => {
    test.skip(!dataTemplateId || !recordId, 'needs DCF-01..03');
    instanceTitle = uniqueName('E2E Onboarding Max');
    await openAs(page, OPERATOR(), `/creation/blueprints/${blueprintId}`);
    await stepTab(page, 'Documents').click();
    const documents = page.getByTestId('documents-step');
    await expect(documents.getByText('No documents from this template yet.')).toBeVisible();
    await documents.getByLabel('Title').fill(instanceTitle);
    await documents.getByRole('button', { name: 'Create a document' }).click();
    await expect(page).toHaveURL(/\/creation\/documents\/\d+\?step=data$/);
    instanceId = Number(new URL(page.url()).pathname.split('/').pop());
    await expect(page.getByRole('heading', { name: instanceTitle })).toBeVisible();

    // Step 1, Choose data: the managed source is preselected; search the person.
    await expect(stepTab(page, 'Choose data')).toHaveAttribute('aria-selected', 'true');
    const choose = page.getByTestId('choose-data');
    await expect(choose.getByLabel('Data source')).toHaveValue(`t:${flow.templateId}`);
    await choose.getByPlaceholder('Name, ID number…').fill('Maximilian');
    const use = choose.getByRole('button', { name: /^Use / });
    await expect(use).toHaveCount(1);
    await use.click();

    // The preview: "document field ← flow field: value", ⚠ on the value that breaks LENGTH max 20.
    const preview = choose.getByTestId('fill-preview');
    const nameRow = preview.getByTestId('fill-row-full_name');
    await expect(nameRow).toContainText(`Full name ← ${flow.name}.full_name: ${PERSON.full_name}`);
    await expect(nameRow).toHaveAttribute('data-would-fail', 'true');
    await expect(preview.getByTestId('would-fail-full_name')).toContainText('⚠');
    for (const key of ['email', 'start_date', 'status']) {
      const row = preview.getByTestId(`fill-row-${key}`);
      await expect(row).toContainText(`← ${flow.name}.${key}: ${PERSON[key]}`);
      await expect(row.getByTestId(`would-fail-${key}`)).toHaveCount(0);
      await expect(row.getByRole('checkbox')).toBeChecked();
    }
    await expect(preview.getByText('4 of 4 fields selected')).toBeVisible();
    const fill = await operatorApi.get(`${DOCS}/instances/${instanceId}/fill-preview?recordId=${recordId}`);
    const nameFails = fill.fields.find((f) => f.regionKey === 'full_name').wouldFail;
    expect(nameFails.length).toBeGreaterThan(0);
    expect(JSON.stringify(nameFails), 'messages never carry the value').not.toContain(PERSON.full_name);
    await choose.getByRole('button', { name: 'Apply' }).click();
    await expect(page.getByText(`4 fields filled from ${flow.name}`)).toBeVisible();
    await expect(stepTab(page, 'Fix fields')).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('#field-input-full_name')).toHaveValue(PERSON.full_name);

    // Download is locked until the document is saved and validated.
    await expect(stepTab(page, 'Download')).toHaveAttribute('aria-disabled', 'true');
    await expect(page.locator('#gate-reason')).toHaveText('Save and validate first');
    await expect(page.getByRole('button', { name: 'Download PDF', exact: true })).toBeDisabled();

    // Step 3, Save & validate: the error, with Go to field.
    await stepTab(page, 'Save & validate').click();
    const saveStep = page.getByTestId('save-step');
    await expect(saveStep.getByTestId('save-result')).toHaveText('Not checked yet.');
    let validated = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith(`/instances/${instanceId}/validate`));
    await saveStep.getByRole('button', { name: 'Save and validate' }).click();
    expect((await (await validated).json()).status).toBe('DRAFT');
    await expect(saveStep.getByText('1 error to fix')).toBeVisible();
    const errors = saveStep.getByRole('list', { name: 'Errors to fix' });
    await expect(errors.getByRole('listitem')).toHaveCount(1);
    await expect(errors).toContainText('Full name:');
    await expect(stepTab(page, 'Download')).toHaveAttribute('aria-disabled', 'true');
    await errors.getByRole('button', { name: 'Go to field: Full name' }).click();
    await expect(stepTab(page, 'Fix fields')).toHaveAttribute('aria-selected', 'true');
    const nameInput = page.locator('#field-input-full_name');
    await expect(nameInput).toBeFocused();
    await nameInput.fill(FIXED_NAME);
    await expect(page.getByTestId('save-state')).toHaveText('Saved');

    // Fixed -> Save & validate -> Validated -> Download unlocked.
    await stepTab(page, 'Save & validate').click();
    validated = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith(`/instances/${instanceId}/validate`));
    await saveStep.getByRole('button', { name: 'Save and validate' }).click();
    expect((await (await validated).json()).status).toBe('VALIDATED');
    await expect(saveStep.getByTestId('save-result').getByText('Validated', { exact: true })).toBeVisible();
    await expect(page.locator('#gate-reason')).toHaveCount(0);
    await expect(stepTab(page, 'Download')).not.toHaveAttribute('aria-disabled', 'true');
    await saveStep.getByRole('button', { name: 'Continue to Download' }).click();
    await expect(stepTab(page, 'Download')).toHaveAttribute('aria-selected', 'true');

    // Step 4: Generate, then Download PDF.
    const downloadStep = page.getByTestId('download-step');
    const generated = page.waitForResponse((r) => r.url().endsWith(`/instances/${instanceId}/generate`));
    await downloadStep.getByRole('button', { name: 'Generate', exact: true }).click();
    expect((await generated).status()).toBe(200);
    await expect(page.getByText('GENERATED', { exact: true })).toBeVisible();
    const downloadEvent = page.waitForEvent('download');
    await downloadStep.getByRole('button', { name: 'Download PDF', exact: true }).click();
    const pdf = await fs.promises.readFile(await (await downloadEvent).path());
    const text = pageTexts(pdf)[0];
    for (const value of [FIXED_NAME, PERSON.start_date, PERSON.status]) {
      expect(containsText(text, value), `the PDF shows "${value}"`).toBe(true);
    }
    expect(containsText(text, PERSON.full_name)).toBe(false);
    // REPORT masking applies to the e-mail address in the PDF (as in doc-validation VAL-04).
    expect(containsText(text, PERSON.email)).toBe(false);
    expect(containsText(text, 'm***@example.com')).toBe(true);
    expect(text).toContain('Staff Onboarding Form');
  });

  test('DCF-05 an edit locks Download again; My documents: Validation column and Download only when validated', async ({
    page,
    operatorApi,
  }) => {
    test.skip(!instanceId, 'needs DCF-04');
    // Setup (API): one document saved with an error, one never checked.
    const withError = uniqueName('E2E Onboarding with error');
    const notChecked = uniqueName('E2E Onboarding not checked');
    const errorDoc = (await operatorApi.post(`${DOCS}/instances`, { blueprintId, title: withError })).id;
    await operatorApi.put(`${DOCS}/instances/${errorDoc}/values`, { values: { ...PERSON } });
    expect((await operatorApi.post(`${DOCS}/instances/${errorDoc}/validate`)).status).toBe('DRAFT');
    await operatorApi.post(`${DOCS}/instances`, { blueprintId, title: notChecked });

    // An edit on the validated document locks Download again.
    await openAs(page, OPERATOR(), `/creation/documents/${instanceId}?step=fix`);
    await expect(page.getByRole('heading', { name: instanceTitle })).toBeVisible();
    await expect(stepTab(page, 'Download')).not.toHaveAttribute('aria-disabled', 'true');
    await page.locator('#field-input-status').fill('Onboarding');
    await expect(stepTab(page, 'Download')).toHaveAttribute('aria-disabled', 'true');
    await expect(page.locator('#gate-reason')).toHaveText('Save and validate first');
    await expect(page.getByText('DRAFT', { exact: true })).toBeVisible();
    await expect(page.getByTestId('save-state')).toHaveText('Saved');
    expect((await operatorApi.get(`${DOCS}/instances/${instanceId}`)).status).toBe('DRAFT');

    // My documents: the edited document is "Not checked", Download disabled.
    await sidebarLink(page, '/creation/documents').click();
    await expect(page.getByRole('heading', { name: 'My Documents', level: 1 })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Validation' })).toBeVisible();
    await expect(rowWith(page, instanceTitle).getByTestId('validation-state')).toHaveText('Not checked');
    await expect(page.getByRole('button', { name: `Download ${instanceTitle}` })).toBeDisabled();

    // Saved again -> Validated.
    await rowWith(page, instanceTitle).getByRole('link', { name: `Open to fix: ${instanceTitle}` }).click();
    await expect(page).toHaveURL(new RegExp(`/creation/documents/${instanceId}\\?step=fix`));
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByText('VALIDATED', { exact: true })).toBeVisible();
    await expect(stepTab(page, 'Download')).not.toHaveAttribute('aria-disabled', 'true');

    await sidebarLink(page, '/creation/documents').click();
    const states = {
      [instanceTitle]: 'Validated',
      [withError]: '1 error',
      [notChecked]: 'Not checked',
    };
    for (const [title, state] of Object.entries(states)) {
      await expect(rowWith(page, title).getByTestId('validation-state'), title).toHaveText(state);
    }
    await expect(page.getByRole('button', { name: `Download ${instanceTitle}` })).toBeEnabled();
    await expect(page.getByRole('button', { name: `Download ${withError}` })).toBeDisabled();
    await expect(page.getByRole('button', { name: `Download ${withError}` })).toHaveAttribute('title', 'Fix its errors and save it first');
    await expect(page.getByRole('button', { name: `Download ${notChecked}` })).toBeDisabled();
    await expect(page.getByRole('button', { name: `Download ${notChecked}` })).toHaveAttribute('title', 'Save and validate it first');
    await expect(page.getByRole('link', { name: `Open to fix: ${instanceTitle}` })).toHaveCount(0);
    await expect(page.getByRole('link', { name: `Open to fix: ${withError}` })).toBeVisible();

    const downloadEvent = page.waitForEvent('download');
    await page.getByRole('button', { name: `Download ${instanceTitle}` }).click();
    const pdf = await fs.promises.readFile(await (await downloadEvent).path());
    expect(containsText(pageTexts(pdf)[0], 'Onboarding'), 'the edited value is in the PDF').toBe(true);

    const summaries = await operatorApi.get(`${DOCS}/instances?blueprintId=${blueprintId}`);
    const byTitle = Object.fromEntries(summaries.map((s) => [s.title, s]));
    expect(byTitle[instanceTitle]).toMatchObject({ validationState: 'VALIDATED' });
    expect(byTitle[withError]).toMatchObject({ validationState: 'ERRORS', errorCount: 1 });
    expect(byTitle[notChecked]).toMatchObject({ validationState: 'NOT_CHECKED' });
  });

  test('DCF-06 a rule of the data template changes: Download locked ("rules changed"), 409 on generate, Save again', async ({
    page,
    operatorApi,
  }) => {
    test.skip(!instanceId, 'needs DCF-04 and DCF-05');
    // Validated in DCF-05 (and generated by the Download from My documents).
    expect(['VALIDATED', 'GENERATED']).toContain((await operatorApi.get(`${DOCS}/instances/${instanceId}`)).status);

    // The data template's LENGTH rule changes from 20 to 25.
    await openAs(page, OPERATOR(), `/creation/data-templates/${dataTemplateId}`);
    await expect(page.getByRole('heading', { name: dataTemplateName })).toBeVisible();
    await expect(page.getByRole('link', { name: blueprintName })).toBeVisible(); // "Used by"
    await page.getByRole('button', { name: 'Edit rules of full_name' }).click();
    const drawer = page.getByRole('dialog', { name: 'Rules — full_name' });
    await drawer.getByRole('button', { name: 'Edit Length rule of full_name' }).click();
    const ruleDialog = page.getByRole('dialog', { name: 'Edit a rule — full_name' });
    await ruleDialog.getByLabel('Maximum length').fill('25');
    await ruleDialog.getByRole('button', { name: 'Update rule' }).click();
    await expect(drawer.getByText('Length at most 25 characters')).toBeVisible();
    await drawer.getByRole('button', { name: 'Done' }).click();
    await page.getByRole('button', { name: 'Save rules' }).click();
    await expect(page.getByText('Rules saved')).toBeVisible();

    // The document: Download locked with the reason; the API refuses to generate.
    await page.goto(`/creation/documents/${instanceId}?step=download`);
    await expect(page.getByRole('heading', { name: instanceTitle })).toBeVisible();
    await expect(page.locator('#gate-reason')).toHaveText('The rules changed — save again');
    await expect(stepTab(page, 'Download')).toHaveAttribute('aria-disabled', 'true');
    // A deep link to the locked step shows Save & validate instead.
    await expect(stepTab(page, 'Save & validate')).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('button', { name: 'Download PDF', exact: true })).toBeDisabled();
    const refused = await operatorApi.request('POST', `${DOCS}/instances/${instanceId}/generate`);
    expect(refused.status).toBe(409);
    expect(refused.body).toMatchObject({ code: 'NOT_VALIDATED' });
    expect(JSON.stringify(refused.body)).toContain('The rules changed since this document was validated; save it again to re-check.');
    const refusedDownload = await operatorApi.request('GET', `${DOCS}/instances/${instanceId}/download`);
    expect(refusedDownload.status).toBe(409);

    await sidebarLink(page, '/creation/documents').click();
    await expect(rowWith(page, instanceTitle).getByTestId('validation-state')).toHaveText('Rules changed — save again');
    await expect(page.getByRole('button', { name: `Download ${instanceTitle}` })).toBeDisabled();

    // Save again -> unlocked.
    await page.goto(`/creation/documents/${instanceId}?step=save`);
    const saveStep = page.getByTestId('save-step');
    await saveStep.getByRole('button', { name: 'Save and validate' }).click();
    await expect(saveStep.getByTestId('save-result').getByText('Validated', { exact: true })).toBeVisible();
    await expect(page.locator('#gate-reason')).toHaveCount(0);
    await expect(stepTab(page, 'Download')).not.toHaveAttribute('aria-disabled', 'true');
    const generated = await operatorApi.request('POST', `${DOCS}/instances/${instanceId}/generate`);
    expect(generated.status).toBe(200);
  });

  test('DCF-07 READ_ONLY cannot save a data template; another bank gets 404 for the data template and the mapping', async ({
    operatorApi,
    platformApi,
    platformCheckerApi,
  }) => {
    test.skip(!dataTemplateId || !blueprintId, 'needs DCF-01..03');
    const { email } = await createUserViaMakerChecker({ role: 'READ_ONLY', displayName: 'E2E data templates reader' });
    const reader = await sessionFor(email);
    const before = await operatorApi.get(`${DATA_TEMPLATES}/${dataTemplateId}`);

    expect((await reader.request('POST', DATA_TEMPLATES, { json: { name: uniqueName('Not allowed') } })).status).toBe(403);
    expect(
      (await reader.request('PUT', `${DATA_TEMPLATES}/${dataTemplateId}`, { json: { name: 'Renamed by a reader' } })).status
    ).toBe(403);
    expect(
      (
        await reader.request('PUT', `${DATA_TEMPLATES}/${dataTemplateId}/rules`, {
          json: { baseRevision: before.revision, rules: [] },
        })
      ).status
    ).toBe(403);
    expect(
      (
        await reader.request('POST', `${DATA_TEMPLATES}/from-flow-template`, {
          json: { templateId: flow.templateId, name: uniqueName('Not allowed') },
        })
      ).status
    ).toBe(403);
    expect(
      (await reader.request('PUT', `${DOCS}/blueprints/${blueprintId}/data-mapping`, { json: { mapping: {} } })).status
    ).toBe(403);
    const after = await operatorApi.get(`${DATA_TEMPLATES}/${dataTemplateId}`);
    expect(after).toMatchObject({ name: dataTemplateName, revision: before.revision });
    expect(after.rules).toHaveLength(before.rules.length);

    if (!platformApi) {
      test.info().annotations.push({ type: 'skipped-part', description: 'no platform admins: second bank not checked' });
      return;
    }
    const bankB = await onboardBank(platformApi, platformCheckerApi);
    const adminB = await sessionFor(bankB.admins[0]);
    expect((await adminB.request('GET', `${DATA_TEMPLATES}/${dataTemplateId}`)).status).toBe(404);
    expect(
      (await adminB.request('PUT', `${DATA_TEMPLATES}/${dataTemplateId}`, { json: { name: 'Taken over' } })).status
    ).toBe(404);
    expect((await adminB.request('GET', `${DOCS}/blueprints/${blueprintId}/data-mapping`)).status).toBe(404);
    expect(
      (await adminB.request('PUT', `${DOCS}/blueprints/${blueprintId}/data-mapping`, { json: { mapping: {} } })).status
    ).toBe(404);
    const listB = await adminB.get(DATA_TEMPLATES);
    expect(listB.some((t) => t.id === dataTemplateId)).toBe(false);
    expect((await operatorApi.get(`${DOCS}/blueprints/${blueprintId}/data-mapping`)).mappedCount).toBe(4);
  });
});
