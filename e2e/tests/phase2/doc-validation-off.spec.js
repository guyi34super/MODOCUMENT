// Validation on/off in document creation (docs/doccreation/
// data-templates-contract.md §8, V334): a PDF template's default for new
// documents ("Validate documents from this template"), each document's own
// choice ("Validate this document", the builder's "Validation" switch), and
// the gates with validation off; plus the scroll regions of the long rule
// lists (components/ScrollBox).
//
// The template is a generated one-page "Contract form" PDF with five boxes;
// its fields and a REQUIRED rule on Name are set through the API (setup).
//
//   VOFF-01 PDF template: the switch off -> steps Upload / Map fields /
//           Documents, Map fields shows the "Validation is off" note;
//           ?step=validation lands on Map fields with its note; on again ->
//           the Validation step is back; off again for the next tests
//   VOFF-02 New document (My documents): "Validate this document" unticked
//           by default (also on the template's Documents step); the builder
//           steps Choose data / Fill fields / Download; Name (required) left
//           empty, Save only saves (no validate call); Generate + Download
//           PDF at once (the PDF carries the values); My documents: "Off",
//           Download enabled
//   VOFF-03 Another document with "Validate this document" ticked (the
//           template's Documents step): the validated flow, Download locked
//           ("Save and validate first"), generate 409 NOT_VALIDATED
//   VOFF-04 The VOFF-02 document (GENERATED): Validation switched on in the
//           builder (confirm dialog) -> DRAFT, "Save & validate" step,
//           Download locked, generate 409; fixed + Save -> VALIDATED,
//           Download unlocked; an edit locks it again, switched off ->
//           unlocked without validating
//   VOFF-05 READ_ONLY gets 403 on both validation-setting routes; another
//           bank 404
//   VOFF-06 Scrolling: the Validation page's rules table (30 rules) scrolls
//           inside its named region with the header row pinned; no
//           horizontal page scroll at 390 px; the builder's "Rules for each
//           field" list scrolls inside its region
//
// UI contract (frontend/src/pages/{BlueprintDetailPage,DocBuilderPage,
// DocInstancesPage,DataValidationPage}, components/{ScrollBox,
// doc/BuilderSteps,doc/ValidationPanel,blueprint/DocumentsStep}): template
// header switch (role=switch) "Validate documents from this template";
// Map fields note data-testid validation-off-note (+ validation-step-off for
// a ?step=validation link); checkbox "Validate this document"; builder
// header switch "Validation", dialog "Turn validation on?" with "Turn
// validation on"; steps (role=tab, tablists "Template steps" / "Document
// steps"); Checks panel heading "Checks (validation off)"; My documents
// validation-state "Off"; ScrollBox = role=region named by its label.
import fs from 'node:fs/promises';
import { test, expect, openAs } from '../../lib/fixtures.js';
import { sessionFor } from '../../lib/api.js';
import { OPERATOR, createUserViaMakerChecker, uniqueName } from '../../lib/seed.js';
import { onboardBank } from '../../lib/phase2.js';
import { buildPdf } from '../../fixtures/pdf-form.js';
import { containsText, pageTexts } from '../../lib/pdf-inspect.js';
import { getBlueprint, getBytes, saveAndValidate, uploadBlueprint } from '../../lib/doccreation.js';

const DOCS = '/api/doccreation';
const DATA_TEMPLATES = `${DOCS}/data-templates`;
const PAGE_W = 595.28;
const PAGE_H = 841.89;
const VALIDATION_OFF_NOTE = 'Validation is off — fields are filled from the flow, nothing is checked.';
const TEMPLATE_SWITCH = 'Validate documents from this template';

// The form's boxes in PDF points (x, y = bottom-left), and the fields on them.
const FIELDS = [
  { key: 'name', label: 'Name', kind: 'TEXT', text: 'Name:', box: [170, 700, 260, 20] },
  { key: 'email', label: 'E-mail', kind: 'TEXT', text: 'E-mail:', box: [170, 665, 260, 20] },
  { key: 'start_date', label: 'Start date', kind: 'DATE', text: 'Start date:', box: [170, 630, 140, 20] },
  { key: 'end_date', label: 'End date', kind: 'DATE', text: 'End date:', box: [170, 595, 140, 20] },
  { key: 'status', label: 'Status', kind: 'TEXT', text: 'Status:', box: [170, 560, 140, 20] },
];

// VOFF-06: a long form (12 boxes) and a data template of 15 fields with 2 format rules each (one table row per rule).
const WIDE_FIELDS = Array.from({ length: 12 }, (_, i) => {
  const n = String(i + 1).padStart(2, '0');
  return { key: `f${n}`, label: `Field ${n}`, kind: 'TEXT', text: `Field ${n}:`, box: [170, 760 - i * 40, 260, 20] };
});
const DATA_FIELD_COUNT = 15;

/** A one-page form: a title and a label + box per field. */
function formPdf(title, fields) {
  return buildPdf((addPage) => {
    const p = addPage(PAGE_W, PAGE_H);
    p.text(72, 800, title, { size: 16, font: 'F2' });
    for (const f of fields) {
      const [x, y, w, h] = f.box;
      p.text(72, y + 6, f.text);
      p.rect(x, y, w, h);
    }
    p.text(72, 200, 'Please check every detail before signing.');
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

/** A confirmed one-page form with `fields` (API). Returns {id, name}. */
async function confirmedForm(api, prefix, title, fields) {
  const name = uniqueName(prefix);
  const uploaded = await uploadBlueprint(api, { bytes: formPdf(title, fields), fileName: 'form.pdf', flow: 'BLANK', name });
  expect([200, 201]).toContain(uploaded.status);
  const id = uploaded.body.id;
  const draft = await getBlueprint(api, id);
  await api.put(`${DOCS}/blueprints/${id}/overlay`, {
    baseVersion: draft.currentVersion ?? draft.version,
    regions: fields.map(regionOf),
    edits: [],
    confirm: true,
  });
  expect(await getBlueprint(api, id)).toMatchObject({ status: 'READY', currentVersion: 1 });
  return { id, name };
}

/** A header button of the builder (Generate / Download PDF / Save). */
const button = (page, name) => page.getByRole('button', { name, exact: true });

/** A step tab by its exact name. */
const stepTab = (page, name) => page.getByRole('tab', { name, exact: true });

/** The names of the tabs of a stepper ("Template steps" / "Document steps"), in order. */
const stepNames = (page, list) =>
  page.getByRole('tablist', { name: list }).getByRole('tab').evaluateAll((tabs) => tabs.map((t) => t.getAttribute('aria-label')));

/** One row of a DataTable, by a text it shows (e.g. a title). */
const rowWith = (page, text) => page.getByRole('row').filter({ hasText: text });

/** Records every POST …/validate the page sends (to prove Save did not validate). */
function watchValidateCalls(page) {
  const calls = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && /\/api\/doccreation\/instances\/\d+\/validate$/.test(new URL(r.url()).pathname)) {
      calls.push(r.url());
    }
  });
  return calls;
}

/** Opens "New document" on My documents and picks the template. Returns the "Validate this document" checkbox. */
async function openNewDocument(page, templateName) {
  await openAs(page, OPERATOR(), '/creation/documents');
  const heading = page.getByRole('heading', { name: 'New document', level: 2 });
  await expect(async () => {
    if (!(await heading.isVisible())) await page.getByRole('button', { name: 'New document' }).click({ timeout: 2000 });
    await expect(heading).toBeVisible({ timeout: 2000 });
  }).toPass({ timeout: 30_000 });
  await page.getByLabel('Template').selectOption({ label: `${templateName} (1 page)` });
  return page.getByRole('checkbox', { name: 'Validate this document' });
}

/** The PUT …/validation-setting answer of the next switch change. */
const settingResponse = (page, what) =>
  page.waitForResponse((r) => r.request().method() === 'PUT' && r.url().includes(`/${what}/`) && r.url().endsWith('/validation-setting'));

test.describe.configure({ mode: 'serial' });

test.describe('validation on/off for PDF templates and documents @phase2 @validationoff', () => {
  let form; // {id, name}
  let offDocId;
  let offTitle;
  let onDocId;

  test('VOFF-01 the template switch: off -> no Validation step and the note on Map fields; on -> back', async ({
    page,
    operatorApi,
  }) => {
    // Setup (API): the contract form, confirmed, with a REQUIRED rule on Name.
    form = await confirmedForm(operatorApi, 'E2E Off form', 'Contract Form', FIELDS);
    const rules = await operatorApi.put(`${DOCS}/blueprints/${form.id}/rules`, {
      baseRevision: 0,
      rules: [{ regionKey: 'name', ruleType: 'REQUIRED', config: {} }],
    });
    expect(rules.revision).toBe(1);
    expect(await getBlueprint(operatorApi, form.id)).toMatchObject({ validationEnabled: true });

    await openAs(page, OPERATOR(), `/creation/blueprints/${form.id}`);
    await expect(page.getByRole('heading', { name: form.name })).toBeVisible();
    const toggle = page.getByRole('switch', { name: TEMPLATE_SWITCH, exact: true });
    await expect(toggle).toHaveAttribute('aria-checked', 'true');
    expect(await stepNames(page, 'Template steps')).toEqual(['Upload', 'Validation', 'Map fields', 'Documents']);

    // Off: saved at once.
    let saved = settingResponse(page, 'blueprints');
    await toggle.click();
    let response = await saved;
    expect(response.status()).toBe(200);
    expect((await response.json()).validationEnabled).toBe(false);
    await expect(page.getByText('Validation is off — new documents from this template skip the validation steps')).toBeVisible();
    await expect(toggle).toHaveAttribute('aria-checked', 'false');
    await expect.poll(() => stepNames(page, 'Template steps')).toEqual(['Upload', 'Map fields', 'Documents']);
    await expect(stepTab(page, 'Validation')).toHaveCount(0);
    expect(await getBlueprint(operatorApi, form.id)).toMatchObject({ validationEnabled: false });
    const listed = (await operatorApi.get(`${DOCS}/blueprints`)).find((b) => (b.id ?? b.blueprintId) === form.id);
    expect(listed.validationEnabled).toBe(false);

    // Map fields: the note.
    await stepTab(page, 'Map fields').click();
    await expect(stepTab(page, 'Map fields')).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByTestId('validation-off-note')).toHaveText(VALIDATION_OFF_NOTE);
    await expect(page.getByTestId('validation-step-off')).toHaveCount(0);

    // A ?step=validation link (after a reload, from the server's state) lands on Map fields with its note.
    await page.goto(`/creation/blueprints/${form.id}?step=validation`);
    await expect(page.getByRole('heading', { name: form.name })).toBeVisible();
    await expect(page.getByTestId('validation-step-off')).toHaveText(
      'Validation is off for this template, so there is no Validation step — showing Map fields instead.'
    );
    await expect(stepTab(page, 'Map fields')).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByTestId('validation-off-note')).toHaveText(VALIDATION_OFF_NOTE);
    await expect(page.getByTestId('field-rules')).toHaveCount(0);
    await expect(toggle).toHaveAttribute('aria-checked', 'false');

    // On again: the Validation step is back (and the link opens it).
    saved = settingResponse(page, 'blueprints');
    await toggle.click();
    response = await saved;
    expect((await response.json()).validationEnabled).toBe(true);
    await expect(page.getByText('Validation is on — new documents from this template are validated')).toBeVisible();
    await expect.poll(() => stepNames(page, 'Template steps')).toEqual(['Upload', 'Validation', 'Map fields', 'Documents']);
    await expect(page.getByTestId('validation-off-note')).toHaveCount(0);
    await page.goto(`/creation/blueprints/${form.id}?step=validation`);
    await expect(stepTab(page, 'Validation')).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByTestId('validation-step-off')).toHaveCount(0);

    // Off again for the next tests.
    saved = settingResponse(page, 'blueprints');
    await page.getByRole('switch', { name: TEMPLATE_SWITCH, exact: true }).click();
    expect((await (await saved).json()).validationEnabled).toBe(false);
    await expect.poll(() => stepNames(page, 'Template steps')).toEqual(['Upload', 'Map fields', 'Documents']);
    expect(await getBlueprint(operatorApi, form.id)).toMatchObject({ validationEnabled: false });
  });

  test('VOFF-02 a document without validation: Save only saves, Generate + Download at once, "Off" in My documents', async ({
    page,
    operatorApi,
  }) => {
    test.skip(!form, 'needs the template from VOFF-01');
    // The template's Documents step offers the checkbox unticked (the template's default is off).
    await openAs(page, OPERATOR(), `/creation/blueprints/${form.id}?step=documents`);
    const documents = page.getByTestId('documents-step');
    await expect(documents.getByRole('checkbox', { name: 'Validate this document' })).not.toBeChecked();
    await expect(documents.getByText('The template’s default is off.')).toBeVisible();

    // My documents: New document, the checkbox unticked by default.
    offTitle = uniqueName('E2E Unchecked contract');
    const validate = await openNewDocument(page, form.name);
    await expect(validate).not.toBeChecked();
    await page.getByPlaceholder(/Survey/).fill(offTitle);
    const created = page.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === `${DOCS}/instances`);
    await page.getByRole('button', { name: 'Create document' }).click();
    const createdResponse = await created;
    expect(createdResponse.request().postDataJSON()).toMatchObject({ validationEnabled: false });
    await expect(page).toHaveURL(/\/creation\/documents\/\d+\?step=data$/);
    offDocId = Number(new URL(page.url()).pathname.split('/').pop());
    await expect(page.getByRole('heading', { name: offTitle })).toBeVisible();
    expect(await operatorApi.get(`${DOCS}/instances/${offDocId}`)).toMatchObject({ status: 'DRAFT', validationEnabled: false });

    // Three steps; the builder's switch is off; the Checks panel only informs.
    await expect.poll(() => stepNames(page, 'Document steps')).toEqual(['Choose data', 'Fill fields', 'Download']);
    await expect(stepTab(page, 'Choose data')).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('switch', { name: 'Validation', exact: true })).toHaveAttribute('aria-checked', 'false');
    await stepTab(page, 'Fill fields').click();
    const panel = page.getByTestId('validation-panel');
    await expect(panel.getByRole('heading', { name: 'Checks (validation off)' })).toBeVisible();
    await expect(panel.getByTestId('checks-state')).toHaveText(
      'Validation is off for this document — the rules below are for information; nothing is checked before Download.'
    );
    // Nothing waits on a check.
    await expect(page.locator('#gate-reason')).toHaveCount(0);
    await expect(button(page, 'Generate')).toBeEnabled();
    await expect(button(page, 'Download PDF')).toBeEnabled();
    await expect(stepTab(page, 'Download')).not.toHaveAttribute('aria-disabled', 'true');

    // Fill some fields, leave the required Name empty; Save only saves.
    const validateCalls = watchValidateCalls(page);
    await page.locator('#field-input-status').fill('Active');
    await page.locator('#field-input-email').fill('jane.doe@example.com');
    await button(page, 'Save').click();
    await expect(page.getByText('Document saved')).toBeVisible();
    await expect(page.getByTestId('save-state')).toHaveText('Saved');
    expect(validateCalls, 'Save without validation sends no validate').toEqual([]);
    await expect(page.getByTestId('issues-panel')).toHaveCount(0);
    await expect(page.getByText('DRAFT', { exact: true })).toBeVisible();
    const stored = await operatorApi.get(`${DOCS}/instances/${offDocId}`);
    expect(stored).toMatchObject({ status: 'DRAFT', validationEnabled: false, values: { status: 'Active' } });
    expect(stored.values.name ?? '').toBe('');

    // Generate + Download PDF straight away.
    const generated = page.waitForResponse((r) => r.url().endsWith(`/instances/${offDocId}/generate`));
    await button(page, 'Generate').click();
    const generation = await generated;
    expect(generation.status()).toBe(200);
    expect((await generation.json()).pdfReady).toBe(true);
    await expect(page.getByText('GENERATED', { exact: true })).toBeVisible();
    // No "Validated" chip: it was never checked.
    await expect(page.getByTitle('This document passed its checks')).toHaveCount(0);
    const downloadEvent = page.waitForEvent('download');
    await button(page, 'Download PDF').click();
    const pdf = await fs.readFile(await (await downloadEvent).path());
    expect(pdf.subarray(0, 5).toString('latin1')).toBe('%PDF-');
    const text = pageTexts(pdf)[0];
    expect(text).toContain('Contract Form');
    expect(containsText(text, 'Active'), 'the PDF shows the status').toBe(true);
    expect(validateCalls).toEqual([]);
    expect((await operatorApi.get(`${DOCS}/instances/${offDocId}`)).status).toBe('GENERATED');

    // My documents: "Off", Download enabled.
    await page.goto('/creation/documents');
    await expect(page.getByRole('heading', { name: 'My Documents', level: 1 })).toBeVisible();
    await expect(rowWith(page, offTitle).getByTestId('validation-state')).toHaveText('Off');
    await expect(page.getByRole('button', { name: `Download ${offTitle}` })).toBeEnabled();
    const summary = (await operatorApi.get(`${DOCS}/instances?blueprintId=${form.id}`)).find((s) => s.title === offTitle);
    expect(summary).toMatchObject({ validationState: 'OFF', errorCount: 0, validationEnabled: false });
    const listDownload = page.waitForEvent('download');
    await page.getByRole('button', { name: `Download ${offTitle}` }).click();
    const listed = await fs.readFile(await (await listDownload).path());
    expect(containsText(pageTexts(listed)[0], 'Active')).toBe(true);
  });

  test('VOFF-03 "Validate this document" ticked: the validated flow, Download locked until Save & validate', async ({
    page,
    operatorApi,
  }) => {
    test.skip(!form, 'needs the template from VOFF-01');
    const title = uniqueName('E2E Checked contract');
    await openAs(page, OPERATOR(), `/creation/blueprints/${form.id}?step=documents`);
    const documents = page.getByTestId('documents-step');
    const validate = documents.getByRole('checkbox', { name: 'Validate this document' });
    await expect(validate).not.toBeChecked();
    await validate.check();
    await expect(documents.getByText('It has to pass its checks before it can be downloaded.', { exact: false })).toBeVisible();
    await documents.getByLabel('Title').fill(title);
    const created = page.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === `${DOCS}/instances`);
    await documents.getByRole('button', { name: 'Create a document' }).click();
    expect((await created).request().postDataJSON()).toMatchObject({ validationEnabled: true });
    await expect(page).toHaveURL(/\/creation\/documents\/\d+\?step=data$/);
    onDocId = Number(new URL(page.url()).pathname.split('/').pop());
    await expect(page.getByRole('heading', { name: title })).toBeVisible();
    expect(await operatorApi.get(`${DOCS}/instances/${onDocId}`)).toMatchObject({ status: 'DRAFT', validationEnabled: true });
    // The template's default is untouched.
    expect(await getBlueprint(operatorApi, form.id)).toMatchObject({ validationEnabled: false });

    await expect.poll(() => stepNames(page, 'Document steps')).toEqual(['Choose data', 'Fix fields', 'Save & validate', 'Download']);
    await expect(page.getByRole('switch', { name: 'Validation', exact: true })).toHaveAttribute('aria-checked', 'true');
    await stepTab(page, 'Fix fields').click();
    await expect(page.getByTestId('validation-panel').getByRole('heading', { name: 'Checks', exact: true })).toBeVisible();
    await expect(stepTab(page, 'Download')).toHaveAttribute('aria-disabled', 'true');
    await expect(page.locator('#gate-reason')).toHaveText('Save and validate first');
    await expect(button(page, 'Download PDF')).toBeDisabled();
    await expect(button(page, 'Generate')).toBeDisabled();
    const refused = await operatorApi.request('POST', `${DOCS}/instances/${onDocId}/generate`);
    expect(refused.status).toBe(409);
    expect(refused.body).toMatchObject({ code: 'NOT_VALIDATED' });

    // Name filled -> Save validates -> Download unlocked.
    await page.locator('#field-input-name').fill('Jane Doe');
    await expect(page.getByTestId('save-state')).toHaveText('Saved');
    await saveAndValidate(page);
    await expect(page.locator('#gate-reason')).toHaveCount(0);
    await expect(button(page, 'Download PDF')).toBeEnabled();

    await page.goto('/creation/documents');
    await expect(rowWith(page, title).getByTestId('validation-state')).toHaveText('Validated');
  });

  test('VOFF-04 Validation switched on in the builder: back to DRAFT, Save & validate needed; switched off: unlocked', async ({
    page,
    operatorApi,
  }) => {
    test.skip(!offDocId, 'needs the document from VOFF-02');
    expect(await operatorApi.get(`${DOCS}/instances/${offDocId}`)).toMatchObject({ status: 'GENERATED', validationEnabled: false });
    await openAs(page, OPERATOR(), `/creation/documents/${offDocId}?step=fix`);
    await expect(page.getByRole('heading', { name: offTitle })).toBeVisible();
    await expect(page.getByText('GENERATED', { exact: true })).toBeVisible();
    const toggle = page.getByRole('switch', { name: 'Validation', exact: true });
    await expect(toggle).toHaveAttribute('aria-checked', 'false');

    // Turning on asks first; Cancel changes nothing.
    await toggle.click();
    const dialog = page.getByRole('dialog', { name: 'Turn validation on?' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText('The document will need to pass its checks before Download.')).toBeVisible();
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toBeHidden();
    await expect(toggle).toHaveAttribute('aria-checked', 'false');
    expect((await operatorApi.get(`${DOCS}/instances/${offDocId}`)).validationEnabled).toBe(false);

    await toggle.click();
    let saved = settingResponse(page, 'instances');
    await dialog.getByRole('button', { name: 'Turn validation on' }).click();
    let response = await saved;
    expect(response.status()).toBe(200);
    expect(await response.json()).toMatchObject({ validationEnabled: true, status: 'DRAFT' });
    await expect(page.getByText('Validation is on — the document has to pass its checks before Download')).toBeVisible();
    await expect(toggle).toHaveAttribute('aria-checked', 'true');
    await expect(page.getByText('DRAFT', { exact: true })).toBeVisible();
    await expect.poll(() => stepNames(page, 'Document steps')).toEqual(['Choose data', 'Fix fields', 'Save & validate', 'Download']);
    await expect(stepTab(page, 'Download')).toHaveAttribute('aria-disabled', 'true');
    await expect(page.locator('#gate-reason')).toHaveText('Save and validate first');
    await expect(button(page, 'Download PDF')).toBeDisabled();
    await expect(button(page, 'Generate')).toBeDisabled();
    await expect(page.getByTestId('validation-panel').getByRole('heading', { name: 'Checks', exact: true })).toBeVisible();

    // The server agrees: a draft with validation, generate and download refused.
    expect(await operatorApi.get(`${DOCS}/instances/${offDocId}`)).toMatchObject({ status: 'DRAFT', validationEnabled: true });
    for (const [method, route] of [
      ['POST', 'generate'],
      ['GET', 'download'],
    ]) {
      const refused = await operatorApi.request(method, `${DOCS}/instances/${offDocId}/${route}`);
      expect(refused.status, route).toBe(409);
      expect(refused.body).toMatchObject({ code: 'NOT_VALIDATED' });
    }
    const summary = (await operatorApi.get(`${DOCS}/instances?blueprintId=${form.id}`)).find((s) => s.id === offDocId);
    expect(summary.validationState).not.toBe('OFF');

    // Save now validates: the missing Name is an error.
    const failed = await saveAndValidate(page, { expectValidated: false });
    expect(failed.status).toBe('DRAFT');
    expect(failed.validation.errors.map((e) => [e.regionKey, e.code])).toEqual([['name', 'REQUIRED']]);
    await expect(button(page, 'Download PDF')).toBeDisabled();

    // Fixed -> Save & validate -> Download unlocked.
    await page.locator('#field-input-name').fill('Jane Doe');
    await expect(page.getByTestId('save-state')).toHaveText('Saved');
    await saveAndValidate(page);
    await expect(page.locator('#gate-reason')).toHaveCount(0);
    await expect(button(page, 'Download PDF')).toBeEnabled();
    expect((await operatorApi.get(`${DOCS}/instances/${offDocId}`)).status).toBe('VALIDATED');

    // An edit locks Download again ...
    await page.locator('#field-input-status').fill('Suspended');
    await expect(page.locator('#gate-reason')).toHaveText('Save and validate first');
    await expect(button(page, 'Download PDF')).toBeDisabled();
    await expect(page.getByTestId('save-state')).toHaveText('Saved');
    expect((await operatorApi.get(`${DOCS}/instances/${offDocId}`)).status).toBe('DRAFT');

    // ... switching validation off unlocks it without validating (no dialog).
    const validateCalls = watchValidateCalls(page);
    saved = settingResponse(page, 'instances');
    await toggle.click();
    response = await saved;
    expect(await response.json()).toMatchObject({ validationEnabled: false, status: 'DRAFT' });
    await expect(page.getByText('Validation is off — nothing is checked before Download')).toBeVisible();
    await expect(toggle).toHaveAttribute('aria-checked', 'false');
    await expect.poll(() => stepNames(page, 'Document steps')).toEqual(['Choose data', 'Fill fields', 'Download']);
    await expect(page.locator('#gate-reason')).toHaveCount(0);
    await expect(button(page, 'Download PDF')).toBeEnabled();
    await expect(button(page, 'Generate')).toBeEnabled();
    expect(validateCalls).toEqual([]);
    const pdf = await getBytes(operatorApi, `${DOCS}/instances/${offDocId}/download`);
    expect(pdf.status).toBe(200);
    expect(containsText(pageTexts(pdf.bytes)[0], 'Suspended'), 'the PDF has the last edit').toBe(true);
  });

  test('VOFF-05 READ_ONLY cannot change either validation setting (403); another bank gets 404', async ({
    operatorApi,
    platformApi,
    platformCheckerApi,
  }) => {
    test.skip(!form || !offDocId, 'needs VOFF-01 and VOFF-02');
    const blueprintRoute = `${DOCS}/blueprints/${form.id}/validation-setting`;
    const instanceRoute = `${DOCS}/instances/${offDocId}/validation-setting`;
    const before = await operatorApi.get(`${DOCS}/instances/${offDocId}`);

    const { email } = await createUserViaMakerChecker({ role: 'READ_ONLY', displayName: 'E2E validation switch reader' });
    const reader = await sessionFor(email);
    expect((await reader.request('PUT', blueprintRoute, { json: { enabled: true } })).status).toBe(403);
    expect((await reader.request('PUT', instanceRoute, { json: { enabled: true } })).status).toBe(403);
    expect(await getBlueprint(operatorApi, form.id)).toMatchObject({ validationEnabled: false });
    expect(await operatorApi.get(`${DOCS}/instances/${offDocId}`)).toMatchObject({
      status: before.status,
      validationEnabled: before.validationEnabled,
    });

    if (!platformApi) {
      test.info().annotations.push({ type: 'skipped-part', description: 'no platform admins: second bank not checked' });
      return;
    }
    const bankB = await onboardBank(platformApi, platformCheckerApi);
    const adminB = await sessionFor(bankB.admins[0]);
    expect((await adminB.request('PUT', blueprintRoute, { json: { enabled: true } })).status).toBe(404);
    expect((await adminB.request('PUT', instanceRoute, { json: { enabled: true } })).status).toBe(404);
    expect(await getBlueprint(operatorApi, form.id)).toMatchObject({ validationEnabled: false });
    expect((await operatorApi.get(`${DOCS}/instances/${offDocId}`)).validationEnabled).toBe(before.validationEnabled);
  });

  test('VOFF-06 long rule lists scroll inside their regions; no horizontal page scroll at 390 px', async ({
    page,
    operatorApi,
  }) => {
    // Setup (API): a data template of 15 fields with 30 rules (LENGTH + REGEX on each: 30 rows).
    const dataName = uniqueName('E2E Many rules');
    const dt = await operatorApi.post(DATA_TEMPLATES, { name: dataName });
    const fieldNames = Array.from({ length: DATA_FIELD_COUNT }, (_, i) => `f${String(i + 1).padStart(2, '0')}`);
    const withFields = await operatorApi.put(`${DATA_TEMPLATES}/${dt.id}/fields`, {
      baseRevision: dt.revision,
      fields: fieldNames.map((name, i) => ({ name, label: `Field ${String(i + 1).padStart(2, '0')}`, kind: 'TEXT' })),
    });
    const withRules = await operatorApi.put(`${DATA_TEMPLATES}/${dt.id}/rules`, {
      baseRevision: withFields.revision,
      rules: fieldNames.flatMap((fieldName) => [
        { fieldName, ruleType: 'LENGTH', config: { Max: 40 } },
        { fieldName, ruleType: 'REGEX', config: { Pattern: '^[A-Za-z0-9 ]*$' } },
      ]),
    });
    expect(withRules.rules).toHaveLength(2 * DATA_FIELD_COUNT);

    // The Validation page: the rules table inside its named scroll region.
    await openAs(page, OPERATOR(), `/creation/data-templates?template=${dt.id}`);
    await expect(page.getByRole('heading', { name: 'Validation', level: 1 })).toBeVisible();
    const region = page.getByRole('region', { name: `Rules of ${dataName}`, exact: true });
    await expect(region).toBeVisible();
    const table = region.getByRole('table', { name: `Rules of ${dataName}` });
    await expect(table.locator('tbody tr')).toHaveCount(2 * DATA_FIELD_COUNT);
    const sizes = await region.evaluate((el) => ({ scrollHeight: el.scrollHeight, clientHeight: el.clientHeight, tabIndex: el.tabIndex }));
    expect(sizes.scrollHeight, 'the rules scroll inside the region').toBeGreaterThan(sizes.clientHeight);
    expect(sizes.tabIndex, 'the region is focusable').toBe(0);

    // Scrolled: the header row stays at the top of the region (sticky), the first rows have gone.
    const pageScrollBefore = await page.evaluate(() => window.scrollY);
    const scrolled = await region.evaluate((el) => {
      el.scrollTop = Math.floor((el.scrollHeight - el.clientHeight) / 2);
      return el.scrollTop;
    });
    expect(scrolled).toBeGreaterThan(0);
    const header = table.getByRole('columnheader', { name: 'Field', exact: true });
    await expect
      .poll(async () => {
        const [r, h] = [await region.boundingBox(), await header.boundingBox()];
        return Math.abs(h.y - r.y);
      })
      .toBeLessThan(3);
    const regionBox = await region.boundingBox();
    const firstRow = await table.locator('tbody tr').first().boundingBox();
    expect(firstRow.y, 'the first row scrolled out under the header').toBeLessThan(regionBox.y);
    expect(await page.evaluate(() => window.scrollY), 'the page itself did not scroll').toBe(pageScrollBefore);
    // The fields table scrolls in its own region too.
    await expect(page.getByRole('region', { name: `Fields of ${dataName}`, exact: true })).toBeVisible();

    // A 390 px wide screen: no horizontal page scroll.
    await page.setViewportSize({ width: 390, height: 844 });
    await page.reload();
    await expect(page.getByRole('region', { name: `Rules of ${dataName}`, exact: true })).toBeAttached();
    await expect(page.getByRole('region', { name: `Rules of ${dataName}`, exact: true }).locator('tbody tr')).toHaveCount(
      2 * DATA_FIELD_COUNT
    );
    const widths = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }));
    expect(widths.scrollWidth, `page scrollWidth ${widths.scrollWidth} <= clientWidth ${widths.clientWidth}`).toBeLessThanOrEqual(
      widths.clientWidth
    );
    await page.setViewportSize({ width: 1440, height: 900 });

    // The builder's Checks panel: "Rules for each field" scrolls inside its region.
    const wide = await confirmedForm(operatorApi, 'E2E Long form', 'Long Form', WIDE_FIELDS);
    const mapping = await operatorApi.put(`${DOCS}/blueprints/${wide.id}/data-template`, { dataTemplateId: dt.id });
    expect(mapping.mappedCount).toBe(WIDE_FIELDS.length);
    const doc = await operatorApi.post(`${DOCS}/instances`, { blueprintId: wide.id, title: uniqueName('E2E Long document') });
    await page.goto(`/creation/documents/${doc.id}?step=fix`);
    const panel = page.getByTestId('validation-panel');
    await expect(panel.getByRole('heading', { name: 'Checks', exact: true })).toBeVisible();
    await expect(panel.getByRole('button', { name: 'Rules for each field' })).toHaveAttribute('aria-expanded', 'true');
    const rulesRegion = panel.getByRole('region', { name: 'Rules for each field', exact: true });
    await expect(rulesRegion.getByTestId('field-rules-f12')).toHaveCount(1);
    const listSizes = await rulesRegion.evaluate((el) => ({ scrollHeight: el.scrollHeight, clientHeight: el.clientHeight }));
    expect(listSizes.scrollHeight, 'the field rules scroll inside the region').toBeGreaterThan(listSizes.clientHeight);
    const builderScroll = await page.evaluate(() => window.scrollY);
    const listScrolled = await rulesRegion.evaluate((el) => {
      el.scrollTop = el.scrollHeight;
      return el.scrollTop;
    });
    expect(listScrolled).toBeGreaterThan(0);
    expect(await page.evaluate(() => window.scrollY)).toBe(builderScroll);
    const lastField = panel.getByTestId('field-rules-f12');
    const [lastBox, listBox] = [await lastField.boundingBox(), await rulesRegion.boundingBox()];
    expect(lastBox.y + lastBox.height, 'the last field is in view at the bottom of the region').toBeLessThanOrEqual(
      listBox.y + listBox.height + 1
    );
  });
});
