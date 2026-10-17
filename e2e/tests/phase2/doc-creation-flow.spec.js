// The document creation flow in the user's order (docs/doccreation/
// data-templates-contract.md, ws34): the sidebar "Document Creation" steps
// 1 "Upload document", 2 "Validation", 3 "Map fields", 4 "Fix documents";
// a reusable data template (fields + rules) built from Document Flow
// templates on the one Validation page (laid out like Document Flow → Set
// Field Rules); the PDF template's steps Upload / Validation / Map fields /
// Documents; the builder's steps Choose data / Fix fields / Save & validate /
// Download, with Download locked until the document is validated with the
// current rules.
//
// Setup through the API only: the flow template "Staff" (full_name,
// email, start_date, status) with flow rules (REQUIRED on full_name, the
// e-mail preset REGEX on email) and one validated ingestion record whose
// full name is too long for the LENGTH rule the user adds by hand.
//
//   DCF-01 Upload document: sidebar step 1, upload the "Staff onboarding"
//          PDF, confirm -> lands on the template's Validation step
//   DCF-02 Validation: "Build from Document Flow templates" -> the flow
//          template ticked -> its fields (all ticked, with their rules) ->
//          preview -> "Save as a new template"; the Fields card says "From
//          Staff.<field>", the rules table lists the imported rules with their
//          Source; a LENGTH max 20 rule is added with the "Add field rules"
//          card (saved at once); chosen on the PDF template's Validation step,
//          where the rules show read-only under "Inherited from <data template>"
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
//   DCF-06 a rule of the data template changes (Update in the rules table)
//          -> "The rules changed — save again", generate 409 NOT_VALIDATED
//          (rules-changed message); Save again -> unlocked
//   DCF-07 READ_ONLY cannot save a data template nor build / add from flow
//          templates (403); another bank gets 404 for the data template, the
//          mapping, adding to it, and building from this bank's flow template
//   DCF-08 ONE data template from TWO flow templates that both have "email":
//          some fields of each ticked, the preview renames the second
//          "email" to email_<slug of its flow template>; saved as a new
//          template; the Fields card says "From <template>.<field>" per
//          field; a Required rule and a Format pattern (RegexBuilder) rule
//          added, one updated, one deleted; "Test a sample row" fails then
//          passes; chosen on a PDF's Validation step; Document Flow → Set
//          Field Rules (/validation) does not list it
//   DCF-09 "Add to <current>" from the build dialog adds a flow template's
//          fields (and rules) to the DCF-02 template: revision + 1, and the
//          validated document of the PDF using it shows "Rules changed — save
//          again" (Download locked, generate 409) until it is saved again
//
// UI contract (frontend/src/pages/{DataValidationPage,DataMappingListPage,
// BlueprintDetailPage,DocBuilderPage,DocInstancesPage}, components/
// {mapping/FieldMatcher,doc/BuilderSteps,blueprint/DataTemplateChooser,
// dataTemplates/BuildFromFlowDialog,rules/RulesTestPanel,RegexBuilder}):
// steps are role=tab ("Upload", "Validation", "Map fields", "Documents";
// "Choose data", "Fix fields", "Save & validate", "Download", aria-disabled
// when locked, the reason in #gate-reason); the Validation page has the
// "Template" select, "New template", "Build from Document Flow templates",
// the Fields card (rows data-field-<name>), the "Add field rules" /
// "Update field rules" card (Field / Required or Optional / Format rule
// type, "Save rule" / "Update rule"), the rules table "Rules of <name>"
// (Field / Required / Rule / Settings / Source / Update / Delete, buttons
// "Update|Delete <Rule label> rule of <field label>") and "Test a sample
// row" (rules-test); the build dialog has build-templates, build-source-<id>,
// build-field-<id>-<name>, build-preview with preview-row-<name>, build-save;
// chooser data-template-chooser / data-template-summary; inherited rules
// inherited-rules / inherited-field-<key>; matcher field-matcher,
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
import { getBlueprint, uploadBlueprint } from '../../lib/doccreation.js';

const DOCS = '/api/doccreation';
const DATA_TEMPLATES = `${DOCS}/data-templates`;
const FIELDS = ['full_name', 'email', 'start_date', 'status'];
// The flow UI's e-mail preset: copied as an EMAIL rule (FlowRuleConverter.FLOW_EMAIL_PATTERN).
const EMAIL_PATTERN = '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$';
const MAX_NAME = 20;
// RegexBuilder's "Whole number" and "Phone number" presets (components/RegexBuilder.jsx).
const WHOLE_NUMBER = '^\\d+$';
const PHONE_PATTERN = '^\\+?[0-9][0-9 ()-]{5,18}[0-9]$';

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

/**
 * The control of a <Field label="…"> (a <label> wrapping a caption <span> and
 * the control). By the caption alone: the label's text also holds the
 * select's option texts, so getByLabel would match loosely.
 */
const labelled = (scope, caption) =>
  scope.locator(`xpath=.//label[span[normalize-space(.)=${JSON.stringify(caption)}]]`).locator('select, input, textarea').first();

/** The slug the server gives a flow template's name in a renamed field (OverlayMapper.slug). */
const slug = (text) =>
  text
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 56)
    .replace(/_+$/g, '');

/** The Validation page's parts. */
function validationPage(page) {
  const ruleCard = page.locator('form').filter({ has: page.getByRole('button', { name: /^(Save|Update) rule$/ }) });
  return {
    templateSelect: labelled(page, 'Template'),
    ruleCard,
    ruleField: labelled(ruleCard, 'Field'),
    requirement: labelled(ruleCard, 'Required or Optional'),
    ruleType: labelled(ruleCard, 'Format rule type'),
    rulesTable: (name) => page.getByRole('table', { name: `Rules of ${name}` }),
    testPanel: page.getByTestId('rules-test'),
  };
}

/**
 * "Build from Document Flow templates": ticks the flow templates (in this
 * order) and moves to the fields step. Returns the dialog.
 */
async function openBuildDialog(page, flowNames) {
  await page.getByRole('button', { name: 'Build from Document Flow templates' }).click();
  const dialog = page.getByRole('dialog', { name: 'Build from Document Flow templates' });
  const templates = dialog.getByTestId('build-templates');
  for (const name of flowNames) {
    await labelled(templates, 'Search flow templates').fill(name);
    await templates.getByRole('checkbox', { name, exact: true }).check();
  }
  await expect(templates.getByText(`Flow templates (${flowNames.length} ticked)`)).toBeVisible();
  await dialog.getByRole('button', { name: 'Next: choose fields' }).click();
  await expect(dialog.getByTestId('build-fields')).toBeVisible();
  return dialog;
}

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

  test('DCF-02 Validation: build a data template from a flow template, add a rule, choose it on the PDF template', async ({
    page,
    operatorApi,
  }) => {
    test.skip(!blueprintId, 'needs DCF-01');
    dataTemplateName = uniqueName('Staff data');
    await openAs(page, OPERATOR(), '/');
    await sidebarLink(page, '/creation/data-templates').click();
    await expect(page.getByRole('heading', { name: 'Validation', level: 1 })).toBeVisible();
    const v = validationPage(page);
    await expect(page.getByText('Choose a template', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'New template' })).toBeVisible();

    // Build from Document Flow templates: the flow template ticked; every field ticked, with the rules each brings.
    const dialog = await openBuildDialog(page, [flow.name]);
    const source = dialog.getByTestId(`build-source-${flow.templateId}`);
    await expect(source.getByText(`${flow.name} (4 of 4 fields)`)).toBeVisible();
    for (const name of FIELDS) {
      await expect(source.getByTestId(`build-field-${flow.templateId}-${name}`).getByRole('checkbox')).toBeChecked();
    }
    await expect(source.getByTestId(`build-field-${flow.templateId}-full_name`).getByText('Required', { exact: true })).toBeVisible();
    await expect(source.getByTestId(`build-field-${flow.templateId}-email`).getByText('E-mail address', { exact: true })).toBeVisible();
    await dialog.getByRole('button', { name: 'Next: preview' }).click();
    const preview = dialog.getByTestId('build-preview');
    await expect(preview.getByText('4 fields from 1 flow template.')).toBeVisible();
    for (const name of FIELDS) {
      const row = preview.getByTestId(`preview-row-${name}`);
      await expect(row).toContainText(`${flow.name}.${name}`);
      await expect(row.getByText('renamed', { exact: true })).toHaveCount(0);
    }
    await expect(preview.getByTestId('preview-row-full_name')).toContainText('Required');
    await expect(preview.getByTestId('preview-row-email')).toContainText('E-mail address');
    await dialog.getByRole('button', { name: 'Next: save' }).click();
    const nameInput = labelled(dialog.getByTestId('build-save'), 'Name of the new template');
    await expect(nameInput).toHaveValue(flow.name);
    await nameInput.fill(dataTemplateName);
    await dialog.getByRole('button', { name: 'Save as a new template' }).click();
    await expect(page.getByText(`Saved ${dataTemplateName} with 4 fields`)).toBeVisible();
    await expect(dialog).toHaveCount(0);
    await expect(page).toHaveURL(/\/creation\/data-templates\?template=\d+$/);
    dataTemplateId = Number(new URL(page.url()).searchParams.get('template'));
    await expect(v.templateSelect).toHaveValue(String(dataTemplateId));
    await expect(v.templateSelect.locator('option:checked')).toHaveText(dataTemplateName);
    await expect(page.getByTestId('used-by')).toHaveText('Used by 0 PDF templates.');

    // The Fields card: each field says where it came from.
    for (const name of FIELDS) {
      await expect(page.getByTestId(`data-field-${name}`).getByText(`From ${flow.name}.${name}`, { exact: true })).toBeVisible();
    }
    // The rules table: the imported rules with their Source.
    const table = v.rulesTable(dataTemplateName);
    await expect(table.getByRole('columnheader')).toHaveText(['Field', 'Required', 'Rule', 'Settings', 'Source', 'Update', 'Delete']);
    await expect(rowWith(table, 'Must be filled in').getByRole('cell')).toHaveText([
      'full_name',
      'Yes',
      'Must be filled in',
      'Error',
      `From ${flow.name}.full_name`,
      'Update',
      'Delete',
    ]);
    await expect(rowWith(table, 'An e-mail address').getByRole('cell')).toHaveText([
      'email',
      'No',
      'An e-mail address',
      'Error',
      `From ${flow.name}.email`,
      'Update',
      'Delete',
    ]);

    // "Add field rules": a LENGTH max 20 rule on full_name, saved at once (it stays Required).
    await expect(page.getByRole('heading', { name: 'Add field rules' })).toBeVisible();
    await v.ruleField.selectOption('full_name');
    await expect(v.requirement).toHaveValue('required');
    await v.ruleType.selectOption('LENGTH');
    await labelled(v.ruleCard, 'Maximum length').fill(String(MAX_NAME));
    const put = page.waitForResponse((r) => r.request().method() === 'PUT' && r.url().endsWith(`/data-templates/${dataTemplateId}/rules`));
    await v.ruleCard.getByRole('button', { name: 'Save rule' }).click();
    expect((await put).status()).toBe(200);
    await expect(page.getByText('Rules saved for full_name')).toBeVisible();
    await expect(v.ruleField).toHaveValue('');
    await expect(rowWith(table, `Length at most ${MAX_NAME} characters`).getByRole('cell')).toHaveText([
      'full_name',
      'Yes',
      `Length at most ${MAX_NAME} characters`,
      'Error',
      'Manual',
      'Update',
      'Delete',
    ]);

    const saved = await operatorApi.get(`${DATA_TEMPLATES}/${dataTemplateId}`);
    expect(saved).toMatchObject({ name: dataTemplateName, sourceTemplateId: flow.templateId, revision: 1 });
    expect(saved.fields.map((f) => [f.name, f.sourceFieldName, f.sourceTemplateId])).toEqual(
      FIELDS.map((f) => [f, f, flow.templateId])
    );
    expect(saved.rules.map((r) => [r.fieldName, r.ruleType, r.origin])).toEqual(
      expect.arrayContaining([
        ['full_name', 'REQUIRED', 'IMPORTED'],
        ['email', 'EMAIL', 'IMPORTED'],
        ['full_name', 'LENGTH', 'MANUAL'],
      ])
    );
    expect(saved.rules).toHaveLength(3);
    const summary = (await operatorApi.get(DATA_TEMPLATES)).find((t) => t.id === dataTemplateId);
    expect(summary).toMatchObject({ fieldCount: 4, ruleCount: 3, sourceTemplateId: flow.templateId, usedBy: 0 });

    // Choose it on the PDF template's Validation step.
    await sidebarLink(page, '/creation/blueprints').click();
    await page.getByRole('link', { name: blueprintName, exact: true }).click();
    await expect(page.getByRole('heading', { name: blueprintName })).toBeVisible();
    await stepTab(page, 'Validation').click();
    const chooser = page.getByTestId('data-template-chooser');
    await expect(chooser.getByRole('button', { name: 'Build from Document Flow templates' })).toBeVisible();
    await chooser.getByRole('combobox', { name: 'Data template' }).selectOption({ label: dataTemplateName });
    await expect(page.getByText('Data template chosen — 4 of 4 fields matched automatically')).toBeVisible();
    await expect(page.getByTestId('data-template-summary')).toContainText('4 of 4 PDF fields mapped');
    await expect(page.getByTestId('data-template-summary').getByRole('link', { name: 'Edit the data template' })).toHaveAttribute(
      'href',
      `/creation/data-templates?template=${dataTemplateId}`
    );
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

    // The Validation page now names the PDF template that uses it.
    await page.getByTestId('data-template-summary').getByRole('link', { name: 'Edit the data template' }).click();
    await expect(page).toHaveURL(new RegExp(`/creation/data-templates\\?template=${dataTemplateId}$`));
    await expect(page.getByTestId('used-by')).toContainText(`Used by 1 PDF template: ${blueprintName}.`);
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

    const before = await operatorApi.get(`${DATA_TEMPLATES}/${dataTemplateId}`);

    // The data template's LENGTH rule changes from 20 to 25 (the old link opens the Validation page with it chosen).
    await openAs(page, OPERATOR(), `/creation/data-templates/${dataTemplateId}`);
    await expect(page).toHaveURL(new RegExp(`/creation/data-templates\\?template=${dataTemplateId}$`));
    const v = validationPage(page);
    await expect(v.templateSelect).toHaveValue(String(dataTemplateId));
    await expect(page.getByTestId('used-by').getByRole('link', { name: blueprintName })).toBeVisible();
    const table = v.rulesTable(dataTemplateName);
    await table.getByRole('button', { name: 'Update Length rule of full_name' }).click();
    await expect(page.getByRole('heading', { name: 'Update field rules' })).toBeVisible();
    await expect(v.ruleField).toHaveValue('full_name');
    await expect(v.requirement).toHaveValue('required');
    await expect(v.ruleType).toHaveValue('LENGTH');
    const maxLength = labelled(v.ruleCard, 'Maximum length');
    await expect(maxLength).toHaveValue(String(MAX_NAME));
    await maxLength.fill('25');
    await v.ruleCard.getByRole('button', { name: 'Update rule' }).click();
    await expect(page.getByText('Rules updated for full_name')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Add field rules' })).toBeVisible();
    await expect(rowWith(table, 'Length at most 25 characters')).toHaveCount(1);
    await expect(rowWith(table, `Length at most ${MAX_NAME} characters`)).toHaveCount(0);
    const after = await operatorApi.get(`${DATA_TEMPLATES}/${dataTemplateId}`);
    expect(after.revision).toBe(before.revision + 1);
    const length = after.rules.find((r) => r.ruleType === 'LENGTH');
    expect(length.fieldName).toBe('full_name');
    expect(String(length.config.Max)).toBe('25');
    expect(after.rules).toHaveLength(before.rules.length);

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

  test('DCF-07 READ_ONLY cannot save, build or add to a data template; another bank gets 404 for it, the mapping and the flow template', async ({
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
      (
        await reader.request('POST', `${DATA_TEMPLATES}/from-flow-templates`, {
          json: { name: uniqueName('Not allowed'), sources: [{ templateId: flow.templateId }] },
        })
      ).status
    ).toBe(403);
    expect(
      (
        await reader.request('POST', `${DATA_TEMPLATES}/${dataTemplateId}/add-from-flow-templates`, {
          json: { baseRevision: before.revision, sources: [{ templateId: flow.templateId, fields: ['email'] }] },
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
    expect(
      (
        await adminB.request('POST', `${DATA_TEMPLATES}/${dataTemplateId}/add-from-flow-templates`, {
          json: { baseRevision: before.revision, sources: [{ templateId: flow.templateId }] },
        })
      ).status
    ).toBe(404);
    // Bank A's flow template cannot be built from in bank B; nothing is created.
    const listBefore = await adminB.get(DATA_TEMPLATES);
    expect(
      (
        await adminB.request('POST', `${DATA_TEMPLATES}/from-flow-templates`, {
          json: { name: uniqueName('Taken over'), sources: [{ templateId: flow.templateId }] },
        })
      ).status
    ).toBe(404);
    const listB = await adminB.get(DATA_TEMPLATES);
    expect(listB).toHaveLength(listBefore.length);
    expect(listB.some((t) => t.id === dataTemplateId)).toBe(false);
    expect(await operatorApi.get(`${DATA_TEMPLATES}/${dataTemplateId}`)).toMatchObject({ revision: before.revision });
    expect((await operatorApi.get(`${DOCS}/blueprints/${blueprintId}/data-mapping`)).mappedCount).toBe(4);
  });

  test('DCF-08 one data template from two flow templates: renamed duplicate, field sources, rules added / updated / deleted, sample row, PDF, not in Set Field Rules', async ({
    page,
    operatorApi,
  }) => {
    // Setup (API): two flow templates that both have "email", each with flow rules.
    const accounts = await createTemplate(operatorApi, { name: uniqueName('Accounts'), fields: ['email', 'account_no', 'branch'] });
    await operatorApi.put('/ValidationRules/Upsert', {
      templateId: accounts.templateId,
      fieldName: 'email',
      ruleType: 'REGEX',
      config: { Pattern: EMAIL_PATTERN },
    });
    const contacts = await createTemplate(operatorApi, { name: uniqueName('Contacts'), fields: ['email', 'phone', 'full_name'] });
    await operatorApi.put('/ValidationRules/Upsert', {
      templateId: contacts.templateId,
      fieldName: 'email',
      ruleType: 'REGEX',
      config: { Pattern: EMAIL_PATTERN },
    });
    await operatorApi.put('/ValidationRules/Upsert', {
      templateId: contacts.templateId,
      fieldName: 'phone',
      ruleType: 'REQUIRED',
      config: {},
    });
    const accountsRules = await operatorApi.get(`/ValidationRules/GetByTemplate/${accounts.templateId}`);
    const contactsRules = await operatorApi.get(`/ValidationRules/GetByTemplate/${contacts.templateId}`);
    const renamed = `email_${slug(contacts.name)}`;
    const builtName = uniqueName('Customer data');

    await openAs(page, OPERATOR(), '/creation/data-templates');
    await expect(page.getByRole('heading', { name: 'Validation', level: 1 })).toBeVisible();
    const v = validationPage(page);

    // Both flow templates ticked; some fields of each.
    const dialog = await openBuildDialog(page, [accounts.name, contacts.name]);
    const fromAccounts = dialog.getByTestId(`build-source-${accounts.templateId}`);
    const fromContacts = dialog.getByTestId(`build-source-${contacts.templateId}`);
    await expect(fromAccounts.getByText(`${accounts.name} (3 of 3 fields)`)).toBeVisible();
    await expect(fromContacts.getByText(`${contacts.name} (3 of 3 fields)`)).toBeVisible();
    await expect(fromContacts.getByTestId(`build-field-${contacts.templateId}-phone`).getByText('Required', { exact: true })).toBeVisible();
    await fromAccounts.getByTestId(`build-field-${accounts.templateId}-branch`).getByRole('checkbox').uncheck();
    await fromContacts.getByTestId(`build-field-${contacts.templateId}-full_name`).getByRole('checkbox').uncheck();
    await expect(fromAccounts.getByText(`${accounts.name} (2 of 3 fields)`)).toBeVisible();
    await expect(fromContacts.getByText(`${contacts.name} (2 of 3 fields)`)).toBeVisible();

    // The preview: the second "email" is renamed with the slug of its flow template's name.
    await dialog.getByRole('button', { name: 'Next: preview' }).click();
    const preview = dialog.getByTestId('build-preview');
    await expect(preview.getByText('4 fields from 2 flow templates.')).toBeVisible();
    const previewRows = preview.getByRole('table', { name: 'Fields of the new template' }).locator('tbody tr');
    await expect(previewRows).toHaveCount(4);
    const expected = [
      ['email', `${accounts.name}.email`, false],
      ['account_no', `${accounts.name}.account_no`, false],
      [renamed, `${contacts.name}.email`, true],
      ['phone', `${contacts.name}.phone`, false],
    ];
    for (const [i, [name, from, isRenamed]] of expected.entries()) {
      const row = previewRows.nth(i);
      await expect(row).toHaveAttribute('data-testid', `preview-row-${name}`);
      await expect(row.getByRole('cell').nth(1)).toHaveText(from);
      await expect(row.getByText('renamed', { exact: true })).toHaveCount(isRenamed ? 1 : 0);
    }
    await expect(preview.getByTestId(`preview-row-${renamed}`)).toContainText('E-mail address');
    await expect(preview.getByTestId('preview-row-phone')).toContainText('Required');
    await expect(preview.getByTestId('preview-row-branch')).toHaveCount(0);
    await expect(preview.getByTestId('preview-row-full_name')).toHaveCount(0);

    // Save as a new template.
    await dialog.getByRole('button', { name: 'Next: save' }).click();
    const nameInput = labelled(dialog.getByTestId('build-save'), 'Name of the new template');
    await expect(nameInput).toHaveValue(`${accounts.name} + ${contacts.name}`);
    await nameInput.fill(builtName);
    const posted = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith('/data-templates/from-flow-templates'));
    await dialog.getByRole('button', { name: 'Save as a new template' }).click();
    const response = await posted;
    expect(response.status()).toBe(201);
    expect(JSON.parse(response.request().postData())).toEqual({
      name: builtName,
      sources: [
        { templateId: accounts.templateId, fields: ['email', 'account_no'] },
        { templateId: contacts.templateId, fields: ['email', 'phone'] },
      ],
    });
    await expect(page.getByText(`Saved ${builtName} with 4 fields`)).toBeVisible();
    await expect(page).toHaveURL(/\/creation\/data-templates\?template=\d+$/);
    const builtId = Number(new URL(page.url()).searchParams.get('template'));
    await expect(v.templateSelect.locator('option:checked')).toHaveText(builtName);

    // The Fields card: "From <template>.<field>" per field.
    for (const [name, from] of expected) {
      await expect(page.getByTestId(`data-field-${name}`).getByText(`From ${from}`, { exact: true })).toBeVisible();
    }
    await expect(page.getByTestId(`data-field-${renamed}`).getByLabel(`Label of ${renamed}`)).toHaveValue(`email (${contacts.name})`);

    const built = await operatorApi.get(`${DATA_TEMPLATES}/${builtId}`);
    expect(built).toMatchObject({ name: builtName, revision: 0, sourceTemplateId: accounts.templateId });
    expect(built.fields.map((f) => [f.name, f.label, f.sourceTemplateId, f.sourceTemplateName, f.sourceFieldName])).toEqual([
      ['email', 'email', accounts.templateId, accounts.name, 'email'],
      ['account_no', 'account_no', accounts.templateId, accounts.name, 'account_no'],
      [renamed, `email (${contacts.name})`, contacts.templateId, contacts.name, 'email'],
      ['phone', 'phone', contacts.templateId, contacts.name, 'phone'],
    ]);
    expect(built.rules.map((r) => [r.fieldName, r.ruleType, r.origin, r.sourceTemplateId, r.sourceFieldName])).toEqual(
      expect.arrayContaining([
        ['email', 'EMAIL', 'IMPORTED', accounts.templateId, 'email'],
        [renamed, 'EMAIL', 'IMPORTED', contacts.templateId, 'email'],
        ['phone', 'REQUIRED', 'IMPORTED', contacts.templateId, 'phone'],
      ])
    );
    expect(built.rules).toHaveLength(3);

    // The rules table: each imported rule with its Source.
    const table = v.rulesTable(builtName);
    await expect(rowWith(table, `From ${accounts.name}.email`).getByRole('cell').nth(2)).toHaveText('An e-mail address');
    await expect(rowWith(table, `From ${contacts.name}.email`).getByRole('cell').nth(2)).toHaveText('An e-mail address');
    await expect(rowWith(table, `From ${contacts.name}.phone`).getByRole('cell')).toHaveText([
      'phone',
      'Yes',
      'Must be filled in',
      'Error',
      `From ${contacts.name}.phone`,
      'Update',
      'Delete',
    ]);

    // A Required rule on account_no (no format rule).
    await v.ruleField.selectOption('account_no');
    await expect(v.requirement).toHaveValue('optional');
    await expect(v.ruleCard.getByText('Mark the field Required or pick a format rule — there is nothing to save yet.')).toBeVisible();
    await expect(v.ruleCard.getByRole('button', { name: 'Save rule' })).toBeDisabled();
    await v.requirement.selectOption('required');
    await v.ruleCard.getByRole('button', { name: 'Save rule' }).click();
    await expect(page.getByText('Rules saved for account_no')).toBeVisible();
    await expect(rowWith(table, 'account_no').getByRole('cell')).toHaveText([
      'account_no',
      'Yes',
      'Must be filled in',
      'Error',
      'Manual',
      'Update',
      'Delete',
    ]);

    // A Format pattern rule on phone, built with the RegexBuilder ("Whole number").
    await v.ruleField.selectOption('phone');
    await expect(v.requirement).toHaveValue('required');
    await v.ruleType.selectOption('REGEX');
    await expect(v.ruleType.locator('option:checked')).toHaveText('Format pattern — match text rules');
    await expect(v.ruleCard.getByRole('tablist', { name: 'Pattern mode' })).toBeVisible();
    await labelled(v.ruleCard, 'What should this field look like?').selectOption({ label: 'Whole number' });
    await expect(v.ruleCard.getByText('Generated pattern:')).toContainText(WHOLE_NUMBER);
    await v.ruleCard.getByRole('button', { name: 'Save rule' }).click();
    await expect(page.getByText('Rules saved for phone')).toBeVisible();
    await expect(rowWith(table, `Matches ${WHOLE_NUMBER}`).getByRole('cell')).toHaveText([
      'phone',
      'Yes',
      `Matches ${WHOLE_NUMBER}`,
      'Error',
      'Manual',
      'Update',
      'Delete',
    ]);

    // Update the pattern: "Phone number".
    await table.getByRole('button', { name: 'Update Pattern rule of phone' }).click();
    await expect(page.getByRole('heading', { name: 'Update field rules' })).toBeVisible();
    await expect(v.ruleField).toHaveValue('phone');
    await expect(v.ruleType).toHaveValue('REGEX');
    await labelled(v.ruleCard, 'What should this field look like?').selectOption({ label: 'Phone number' });
    await v.ruleCard.getByRole('button', { name: 'Update rule' }).click();
    await expect(page.getByText('Rules updated for phone')).toBeVisible();
    await expect(rowWith(table, `Matches ${PHONE_PATTERN}`)).toHaveCount(1);
    await expect(rowWith(table, `Matches ${WHOLE_NUMBER}`)).toHaveCount(0);

    // Delete the Required rule of account_no.
    await table.getByRole('button', { name: 'Delete Required rule of account_no' }).click();
    const confirm = page.getByRole('dialog', { name: 'Delete this rule?' });
    await expect(confirm).toContainText('Delete “Must be filled in” on account_no?');
    await confirm.getByRole('button', { name: 'Delete rule' }).click();
    await expect(page.getByText('Rule on account_no deleted')).toBeVisible();
    await expect(rowWith(table, 'account_no')).toHaveCount(0);

    const ruled = await operatorApi.get(`${DATA_TEMPLATES}/${builtId}`);
    expect(ruled.revision).toBe(4); // four rule saves: add, add, update, delete
    expect(ruled.rules.map((r) => [r.fieldName, r.ruleType, r.origin, r.config?.Pattern ?? null])).toEqual(
      expect.arrayContaining([
        ['email', 'EMAIL', 'IMPORTED', null],
        [renamed, 'EMAIL', 'IMPORTED', null],
        ['phone', 'REQUIRED', 'IMPORTED', null],
        ['phone', 'REGEX', 'MANUAL', PHONE_PATTERN],
      ])
    );
    expect(ruled.rules).toHaveLength(4);

    // Test a sample row: errors first, then it passes. The renamed e-mail field's label names its flow template.
    await expect(page.getByRole('heading', { name: 'Test a sample row' })).toBeVisible();
    const sample = (label) => v.testPanel.getByLabel(`Sample value for ${label}`, { exact: true });
    const contactEmail = sample(`email (${contacts.name})`);
    await expect(sample('email')).toHaveCount(1);
    await expect(contactEmail).toHaveCount(1);
    await sample('email').fill('not-an-email');
    await sample('phone').fill('');
    await v.testPanel.getByRole('button', { name: 'Run the rules' }).click();
    await expect(v.testPanel.getByRole('status')).toHaveText('2 errors, 0 warnings');
    await expect(sample('email')).toHaveAttribute('aria-invalid', 'true');
    await expect(sample('phone')).toHaveAttribute('aria-invalid', 'true');
    await expect(sample('account_no')).not.toHaveAttribute('aria-invalid', 'true'); // its Required rule was deleted
    await sample('email').fill('jane@acme.com');
    await contactEmail.fill('jane.contact@acme.com');
    await sample('phone').fill('+1 416 555-0199'); // passes the updated pattern, not "Whole number"
    await v.testPanel.getByRole('button', { name: 'Run the rules' }).click();
    await expect(v.testPanel.getByRole('status')).toHaveText('No errors or warnings');
    await expect(v.testPanel.locator('[aria-invalid="true"]')).toHaveCount(0);

    // Chosen on a PDF's Validation step (setup of the PDF through the API).
    const pdfName = uniqueName('E2E Customer form');
    const uploaded = await uploadBlueprint(operatorApi, { bytes: onboardingForm(), fileName: 'customer-form.pdf', flow: 'BLANK', name: pdfName });
    expect([200, 201]).toContain(uploaded.status);
    const pdfId = uploaded.body.id;
    const draft = await getBlueprint(operatorApi, pdfId);
    await operatorApi.put(`${DOCS}/blueprints/${pdfId}/overlay`, {
      baseVersion: draft.currentVersion ?? draft.version,
      regions: draft.regions.filter((r) => !r.suggested).map(({ sampleValue, removedText, ...r }) => r), // eslint-disable-line no-unused-vars
      edits: draft.edits.map(({ sampleValue, removedText, ...e }) => e), // eslint-disable-line no-unused-vars
      confirm: true,
    });
    await page.goto(`/creation/blueprints/${pdfId}?step=validation`);
    await expect(page.getByRole('heading', { name: pdfName })).toBeVisible();
    const chooser = page.getByTestId('data-template-chooser');
    await chooser.getByRole('combobox', { name: 'Data template' }).selectOption({ label: builtName });
    await expect(page.getByText(/^Data template chosen — \d+ of \d+ fields matched automatically$/)).toBeVisible();
    await expect(page.getByTestId('data-template-summary')).toContainText(builtName);
    await expect(page.getByTestId('data-template-summary')).toContainText('4 fields · 4 rules');
    const mapping = await operatorApi.get(`${DOCS}/blueprints/${pdfId}/data-mapping`);
    expect(mapping).toMatchObject({ dataTemplateId: builtId, dataTemplateName: builtName });
    expect(mapping.rows.find((r) => r.regionKey === 'email').mappedField).toBe('email');
    expect(mapping.fields.map((f) => f.name)).toEqual(['email', 'account_no', renamed, 'phone']);
    expect((await operatorApi.get(`${DATA_TEMPLATES}/${builtId}`)).usedBy).toEqual([{ blueprintId: pdfId, name: pdfName }]);

    // Document Flow → Set Field Rules lists the flow templates, never the data template.
    await page.goto('/validation');
    await expect(page.getByRole('heading', { name: 'Set Field Rules', level: 1 })).toBeVisible();
    const flowSelect = labelled(page, 'Template');
    await expect(flowSelect.locator('option', { hasText: accounts.name })).toHaveCount(1);
    await expect(flowSelect.locator('option', { hasText: contacts.name })).toHaveCount(1);
    await expect(flowSelect.locator('option', { hasText: builtName })).toHaveCount(0);
    if (dataTemplateName) await expect(flowSelect.locator('option', { hasText: dataTemplateName })).toHaveCount(0);
    // The flow templates' own rules are untouched by the copy and the edits.
    expect(await operatorApi.get(`/ValidationRules/GetByTemplate/${contacts.templateId}`)).toEqual(contactsRules);
    expect(await operatorApi.get(`/ValidationRules/GetByTemplate/${accounts.templateId}`)).toEqual(accountsRules);
  });

  test('DCF-09 "Add to <current>" adds a flow template\'s fields to the data template: revision + 1, the validated document must be saved again', async ({
    page,
    operatorApi,
  }) => {
    test.skip(!instanceId || !dataTemplateId, 'needs DCF-01..06');
    // Setup (API): one more flow template; its "email" clashes with the data template's.
    const payroll = await createTemplate(operatorApi, { name: uniqueName('Payroll'), fields: ['email', 'iban'] });
    await operatorApi.put('/ValidationRules/Upsert', {
      templateId: payroll.templateId,
      fieldName: 'iban',
      ruleType: 'REQUIRED',
      config: {},
    });
    const renamed = `email_${slug(payroll.name)}`;
    const before = await operatorApi.get(`${DATA_TEMPLATES}/${dataTemplateId}`);
    expect(['VALIDATED', 'GENERATED']).toContain((await operatorApi.get(`${DOCS}/instances/${instanceId}`)).status);
    expect((await operatorApi.get(`${DOCS}/instances?blueprintId=${blueprintId}`)).find((s) => s.title === instanceTitle)).toMatchObject({
      validationState: 'VALIDATED',
    });

    await openAs(page, OPERATOR(), `/creation/data-templates?template=${dataTemplateId}`);
    const v = validationPage(page);
    await expect(v.templateSelect).toHaveValue(String(dataTemplateId));
    await expect(page.getByTestId('data-field-full_name')).toBeVisible();

    const dialog = await openBuildDialog(page, [payroll.name]);
    await expect(dialog.getByTestId(`build-source-${payroll.templateId}`).getByText(`${payroll.name} (2 of 2 fields)`)).toBeVisible();
    await dialog.getByRole('button', { name: 'Next: preview' }).click();
    const preview = dialog.getByTestId('build-preview');
    // A new template by default: nothing clashes there.
    await expect(preview.getByRole('radio', { name: 'A new template' })).toBeChecked();
    await expect(preview.getByTestId('preview-row-email').getByText('renamed', { exact: true })).toHaveCount(0);
    // Added to the current one, its "email" is taken: renamed.
    await preview.getByRole('radio', { name: `Add to ${dataTemplateName}` }).check();
    await expect(preview.getByRole('table', { name: `Fields to add to ${dataTemplateName}` })).toBeVisible();
    await expect(preview.getByTestId(`preview-row-${renamed}`).getByText('renamed', { exact: true })).toBeVisible();
    await expect(preview.getByTestId(`preview-row-${renamed}`)).toContainText(`${payroll.name}.email`);
    await expect(preview.getByTestId('preview-row-iban')).toContainText('Required');
    await expect(preview.getByTestId('preview-row-iban').getByText('renamed', { exact: true })).toHaveCount(0);
    await dialog.getByRole('button', { name: 'Next: save' }).click();
    await expect(dialog.getByTestId('build-save')).toHaveText(`Add 2 fields and their rules to ${dataTemplateName}.`);
    const posted = page.waitForResponse(
      (r) => r.request().method() === 'POST' && r.url().endsWith(`/data-templates/${dataTemplateId}/add-from-flow-templates`)
    );
    await dialog.getByRole('button', { name: `Add to ${dataTemplateName}` }).click();
    const response = await posted;
    expect(response.status()).toBe(200);
    expect(JSON.parse(response.request().postData())).toEqual({
      baseRevision: before.revision,
      sources: [{ templateId: payroll.templateId, fields: ['email', 'iban'] }],
    });
    await expect(page.getByText(`Added 2 fields to ${dataTemplateName}`)).toBeVisible();
    await expect(v.templateSelect).toHaveValue(String(dataTemplateId));

    // The new fields after the existing ones, each with its source; the old ones unchanged.
    await expect(page.getByTestId(`data-field-${renamed}`).getByText(`From ${payroll.name}.email`, { exact: true })).toBeVisible();
    await expect(page.getByTestId('data-field-iban').getByText(`From ${payroll.name}.iban`, { exact: true })).toBeVisible();
    await expect(page.getByTestId('data-field-email').getByText(`From ${flow.name}.email`, { exact: true })).toBeVisible();
    await expect(rowWith(v.rulesTable(dataTemplateName), `From ${payroll.name}.iban`).getByRole('cell')).toHaveText([
      'iban',
      'Yes',
      'Must be filled in',
      'Error',
      `From ${payroll.name}.iban`,
      'Update',
      'Delete',
    ]);

    const after = await operatorApi.get(`${DATA_TEMPLATES}/${dataTemplateId}`);
    expect(after.revision).toBe(before.revision + 1);
    expect(after.sourceTemplateId, 'the main flow template stays').toBe(flow.templateId);
    expect(after.fields.map((f) => f.name)).toEqual([...before.fields.map((f) => f.name), renamed, 'iban']);
    expect(after.fields.slice(-2).map((f) => [f.sourceTemplateId, f.sourceFieldName])).toEqual([
      [payroll.templateId, 'email'],
      [payroll.templateId, 'iban'],
    ]);
    expect(after.rules).toHaveLength(before.rules.length + 1);
    expect(after.rules.find((r) => r.fieldName === 'iban')).toMatchObject({
      ruleType: 'REQUIRED',
      origin: 'IMPORTED',
      sourceTemplateId: payroll.templateId,
    });

    // The PDF using it: its validated document must be saved again.
    await page.goto(`/creation/documents/${instanceId}?step=download`);
    await expect(page.getByRole('heading', { name: instanceTitle })).toBeVisible();
    await expect(page.locator('#gate-reason')).toHaveText('The rules changed — save again');
    await expect(stepTab(page, 'Download')).toHaveAttribute('aria-disabled', 'true');
    await expect(page.getByRole('button', { name: 'Download PDF', exact: true })).toBeDisabled();
    const refused = await operatorApi.request('POST', `${DOCS}/instances/${instanceId}/generate`);
    expect(refused.status).toBe(409);
    expect(refused.body).toMatchObject({ code: 'NOT_VALIDATED' });
    await sidebarLink(page, '/creation/documents').click();
    await expect(rowWith(page, instanceTitle).getByTestId('validation-state')).toHaveText('Rules changed — save again');
    await expect(page.getByRole('button', { name: `Download ${instanceTitle}` })).toBeDisabled();

    // Saved again: the added fields are not mapped to the PDF, so the document validates.
    await page.goto(`/creation/documents/${instanceId}?step=save`);
    const saveStep = page.getByTestId('save-step');
    await saveStep.getByRole('button', { name: 'Save and validate' }).click();
    await expect(saveStep.getByTestId('save-result').getByText('Validated', { exact: true })).toBeVisible();
    await expect(stepTab(page, 'Download')).not.toHaveAttribute('aria-disabled', 'true');
    expect((await operatorApi.get(`${DOCS}/blueprints/${blueprintId}/data-mapping`)).mappedCount).toBe(4);
  });
});
