// Flow records in document creation (docs/doccreation/flow-records-contract.md):
// validated rows of the data flow are a searchable record index; a PDF
// template keeps saved mappings to flow templates (one PRIMARY, LINKED ones
// joined by a key field); the builder fills a document from a record and
// can move/add fields and save the layout to the template.
//
//   REC-01 two flow templates (Customers, Accounts; shared with the bank by
//          the admin), CSVs uploaded by the operator and run to VALIDATED:
//          only the valid rows become records (the invalid row is never
//          searchable)
//   REC-02 a generated PDF form (Full name, NIC, Date of birth, Account
//          number, Branch, Photo) becomes a confirmed template
//   REC-03 UI, template -> "Data sources": Customers as Primary (same-name
//          fields mapped by itself), Accounts as Linked by nic <-> national_id
//          (account_number / branch mapped), saved
//   REC-04 UI, builder -> "Data from the flow": search "Jane" -> Use ->
//          preview with values of both templates -> untick Branch -> Apply ->
//          values on the page, Branch empty, "Filled from" chips; a second
//          record with "Fill empty fields only" keeps the existing values
//   REC-05 UI, builder -> "Edit layout": move Full name, draw "Reference",
//          save to the template (new version, document moved, values kept)
//          -> generate -> the PDF carries the values (REPORT-masked NIC and
//          account number) and Full name is drawn in its NEW box
//   REC-06 READ_ONLY sees masked NIC/phone and cannot fill (403); another
//          bank cannot see the records (404)
//   REC-07 deleting the Customers ingestion job removes its records
//
// UI contract (frontend/src/components/records/*, pages/BlueprintDetailPage,
// pages/DocBuilderPage): tabs "Layout" / "Data sources" (role=tab); the tab
// is data-testid data-sources, one card per source source-<templateId>, its
// rows mapping-row-<regionKey> (select "Template field for <label>"). The
// "Add data source" dialog: "Flow template" select, radios Primary/Linked,
// "Key field in <primary>" / "Key field in the new source". Builder: card
// "Data from the flow" with "Fill from data"; dialog "Fill from data":
// search box "Name, ID number…", buttons "Use <label>", data-testid
// fill-preview, fill-value-<key>, linked-<templateId>, checkboxes
// "Fill <label>", "Fill empty fields only"; chips list "Filled from"
// (data-testid source-records). Layout: "Edit layout", toolbar "Layout
// tools" (Select / Add field), boxes layout-region-<key>, "Save layout to
// template".
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test, expect, openAs } from '../../lib/fixtures.js';
import { createTemplate, sessionFor, uploadForIngestion, waitForJob } from '../../lib/api.js';
import { OPERATOR, createUserViaMakerChecker, uniqueName } from '../../lib/seed.js';
import { onboardBank } from '../../lib/phase2.js';
import { customerForm } from '../../fixtures/pdf-form.js';
import { decodePng, inkInBox } from '../../fixtures/png.js';
import { containsText, pageTexts } from '../../lib/pdf-inspect.js';
import { confirmAsProposed, getBlueprint, getBytes, templatePage, uploadBlueprint } from '../../lib/doccreation.js';

const RECORDS = '/api/doccreation/records';
const DOCS = '/api/doccreation';

const JANE = {
  full_name: 'Jane Doe',
  nic: 'J1204901234567',
  date_of_birth: '1990-04-12',
  phone: '+230 5712 3456',
};
const JOHN = {
  full_name: 'John Roe',
  nic: 'R0511851234567',
  date_of_birth: '1985-11-05',
  phone: '+230 5799 8811',
};
// Fails validation (the NIC is REQUIRED): must never become a record.
const INVALID = { full_name: 'Jane Invalid', nic: '', date_of_birth: '1979-01-01', phone: '+230 5000 0000' };
// Jane's account is keyed by her NIC written differently (case, a hyphen): keys are normalised.
const JANE_ACCOUNT = { national_id: 'j-1204901234567', account_number: '000123456789', branch: 'Port Louis' };
const JOHN_ACCOUNT = { national_id: 'R0511851234567', account_number: '000987654321', branch: 'Curepipe' };
const REFERENCE = 'REF-2026-0042';

/** A CSV file (header + rows) in a fresh temp dir. */
function writeCsv(name, columns, rows) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'modoc-e2e-records-'));
  const file = path.join(dir, name);
  fs.writeFileSync(file, [columns.join(','), ...rows.map((r) => columns.map((c) => r[c] ?? '').join(','))].join('\n') + '\n');
  return file;
}

async function searchRecords(api, templateId, q = '', extra = '') {
  const query = `templateId=${templateId}${q ? `&q=${encodeURIComponent(q)}` : ''}${extra}`;
  return api.get(`${RECORDS}?${query}`);
}

/** Uploads a CSV through ingestion and runs it to VALIDATED. */
async function ingestToValidated(api, templateId, file) {
  const created = await uploadForIngestion(api, { filePath: file, templateId, runUntil: 'VALIDATED', wait: true });
  return waitForJob(api, created.ingestionJobId, (j) => j.currentStep === 'VALIDATED');
}

/** Drags an element's centre by (dx, dy) CSS pixels. */
async function dragBy(page, locator, dx, dy) {
  await locator.evaluate((el) => el.scrollIntoView({ block: 'center' }));
  const box = await locator.boundingBox();
  const x = box.x + box.width / 2;
  const y = box.y + box.height / 2;
  expect(y + dy < page.viewportSize().height, 'the drag ends on screen').toBe(true);
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x + dx / 2, y + dy / 2, { steps: 4 });
  await page.mouse.move(x + dx, y + dy, { steps: 4 });
  await page.mouse.up();
}

const boxOf = (r) => ({ fx: r.fx, fy: r.fy, fw: r.fw, fh: r.fh });

test.describe.configure({ mode: 'serial' });

test.describe('flow records in document creation @phase2 @records', () => {
  let customers; // {templateId, name}
  let accounts;
  let customersJob;
  let accountsJob;
  let janeId;
  let johnId;
  let janeAccountId;
  let johnAccountId;
  let blueprintId;
  let blueprintName;
  let proposedRegions; // version 1
  let instanceId;

  test('REC-01 validated rows of two flow templates become searchable records; an invalid row does not', async ({
    adminApi,
    operatorApi,
  }) => {
    // Shared with the bank (admin only), so a read-only colleague may search them (REC-06).
    customers = await createTemplate(adminApi, {
      name: uniqueName('Customers'),
      visibility: 'BANK',
      fields: [
        { fieldName: 'full_name', piiKind: 'NAME' },
        { fieldName: 'nic', piiKind: 'NIC', rules: [{ ruleType: 'REQUIRED', config: {} }] },
        { fieldName: 'date_of_birth', piiKind: 'NONE' },
        { fieldName: 'phone', piiKind: 'PHONE' },
      ],
    });
    accounts = await createTemplate(adminApi, {
      name: uniqueName('Accounts'),
      visibility: 'BANK',
      fields: [
        { fieldName: 'national_id', piiKind: 'NIC' },
        { fieldName: 'account_number', piiKind: 'ACCOUNT_NUMBER' },
        { fieldName: 'branch', piiKind: 'NONE' },
      ],
    });
    expect(customers.visibility).toBe('BANK');

    const customerColumns = ['full_name', 'nic', 'date_of_birth', 'phone'];
    customersJob = await ingestToValidated(
      operatorApi,
      customers.templateId,
      writeCsv('customers.csv', customerColumns, [JANE, JOHN, INVALID])
    );
    expect(customersJob.validationErrorCount).toBe(1);
    accountsJob = await ingestToValidated(
      operatorApi,
      accounts.templateId,
      writeCsv('accounts.csv', ['national_id', 'account_number', 'branch'], [JANE_ACCOUNT, JOHN_ACCOUNT])
    );
    expect(accountsJob.validationErrorCount ?? 0).toBe(0);

    // Indexed after the commit: wait for the two valid rows.
    await expect
      .poll(async () => (await searchRecords(operatorApi, customers.templateId)).total, { timeout: 30_000 })
      .toBe(2);
    await expect
      .poll(async () => (await searchRecords(operatorApi, accounts.templateId)).total, { timeout: 30_000 })
      .toBe(2);

    const jane = await searchRecords(operatorApi, customers.templateId, 'jane');
    expect(jane.total, 'only the valid Jane is found').toBe(1);
    // The owner's working screen is unmasked (MaskingPolicy SCREEN_WORKING).
    expect(jane.items[0]).toMatchObject({
      templateId: customers.templateId,
      templateName: customers.name,
      jobId: customersJob.ingestionJobId,
      rowIndex: 1,
      values: JANE,
    });
    janeId = jane.items[0].recordId;
    expect((await searchRecords(operatorApi, customers.templateId, 'Invalid')).total).toBe(0);
    expect((await searchRecords(operatorApi, customers.templateId, '1979-01-01')).total).toBe(0);
    // Every word must match; a field search matches that field's normalised value only.
    expect((await searchRecords(operatorApi, customers.templateId, 'jane roe')).total).toBe(0);
    const byNic = await searchRecords(operatorApi, customers.templateId, 'r0511851234567', '&field=nic');
    expect(byNic.items.map((r) => r.values.full_name)).toEqual([JOHN.full_name]);
    johnId = byNic.items[0].recordId;
    expect((await searchRecords(operatorApi, customers.templateId, 'John', '&field=nic')).total).toBe(0);

    const accountItems = (await searchRecords(operatorApi, accounts.templateId)).items;
    janeAccountId = accountItems.find((r) => r.values.branch === JANE_ACCOUNT.branch).recordId;
    johnAccountId = accountItems.find((r) => r.values.branch === JOHN_ACCOUNT.branch).recordId;
    expect(await operatorApi.get(`${RECORDS}/${janeId}`)).toMatchObject({ recordId: janeId, values: JANE });
  });

  test('REC-02 a generated PDF form becomes a confirmed template with the expected fields', async ({ operatorApi }) => {
    blueprintName = uniqueName('E2E Customer account form');
    const uploaded = await uploadBlueprint(operatorApi, {
      bytes: customerForm(),
      fileName: 'customer-account-form.pdf',
      flow: 'BLANK',
      name: blueprintName,
    });
    expect([200, 201]).toContain(uploaded.status);
    blueprintId = uploaded.body.id;
    const draft = await getBlueprint(operatorApi, blueprintId);
    expect(draft).toMatchObject({ status: 'DRAFT', flow: 'BLANK' });
    const byKey = Object.fromEntries(draft.regions.map((r) => [r.key, r]));
    expect(byKey).toMatchObject({
      full_name: { kind: 'TEXT', page: 0 },
      nic: { kind: 'TEXT', page: 0 },
      date_of_birth: { kind: 'DATE', page: 0 },
      account_number: { kind: 'TEXT', page: 0 },
      branch: { kind: 'TEXT', page: 0 },
      photo: { kind: 'IMAGE', page: 0 },
    });
    await confirmAsProposed(operatorApi, draft);
    const ready = await getBlueprint(operatorApi, blueprintId);
    expect(ready).toMatchObject({ status: 'READY', version: 1, currentVersion: 1 });
    proposedRegions = ready.regions;
  });

  test('REC-03 Data sources tab: Customers as Primary (same names mapped), Accounts linked by nic <-> national_id', async ({
    page,
    operatorApi,
  }) => {
    test.skip(!blueprintId || !customers, 'needs REC-01 and REC-02');
    await openAs(page, OPERATOR(), `/creation/blueprints/${blueprintId}`);
    await expect(page.getByRole('heading', { name: blueprintName })).toBeVisible();
    await page.getByRole('tab', { name: 'Data sources' }).click();
    const tab = page.getByTestId('data-sources');
    await expect(tab.getByText('No data sources yet.')).toBeVisible();

    // Primary: Customers. Same-name fields connect by themselves.
    await tab.getByRole('button', { name: 'Add data source' }).click();
    let dialog = page.getByRole('dialog', { name: 'Add data source' });
    await dialog.getByLabel('Flow template').selectOption({ label: customers.name });
    await expect(dialog.getByRole('radio', { name: 'Primary' })).toBeChecked();
    await expect(dialog.getByRole('radio', { name: 'Linked' })).toBeDisabled();
    await dialog.getByRole('button', { name: 'Add source' }).click();
    await expect(page.getByText(`Added ${customers.name} as the primary source — 3 fields mapped automatically`)).toBeVisible();
    const primaryCard = page.getByTestId(`source-${customers.templateId}`);
    await expect(primaryCard.getByText('Primary', { exact: true })).toBeVisible();
    for (const key of ['full_name', 'nic', 'date_of_birth']) {
      const row = primaryCard.getByTestId(`mapping-row-${key}`);
      await expect(row.getByRole('combobox')).toHaveValue(key);
      await expect(row.getByText('same name')).toBeVisible();
    }
    for (const key of ['account_number', 'branch']) {
      await expect(primaryCard.getByTestId(`mapping-row-${key}`).getByRole('combobox')).toHaveValue('');
    }
    // Photos never take a value from the flow.
    await expect(primaryCard.getByTestId('mapping-row-photo')).toHaveCount(0);

    // Linked: Accounts, joined on Customers.nic = Accounts.national_id.
    await tab.getByRole('button', { name: 'Add data source' }).click();
    dialog = page.getByRole('dialog', { name: 'Add data source' });
    await expect(dialog.getByLabel('Flow template').locator('option', { hasText: customers.name })).toHaveCount(0);
    await dialog.getByLabel('Flow template').selectOption({ label: accounts.name });
    await expect(dialog.getByRole('radio', { name: 'Linked' })).toBeChecked();
    await expect(dialog.getByRole('button', { name: 'Add source' })).toBeDisabled();
    await dialog.getByLabel(`Key field in ${customers.name}`).selectOption('nic');
    await dialog.getByLabel('Key field in the new source').selectOption('national_id');
    await dialog.getByRole('button', { name: 'Add source' }).click();
    await expect(page.getByText(`Added ${accounts.name} as the linked source`)).toBeVisible();
    const linkedCard = page.getByTestId(`source-${accounts.templateId}`);
    await expect(linkedCard.getByText('Linked', { exact: true })).toBeVisible();
    // Suggestions not applied yet (if any) are accepted; then account_number / branch are mapped.
    const acceptAll = linkedCard.getByRole('button', { name: /^Accept all suggestions/ });
    if (await acceptAll.isVisible()) await acceptAll.click();
    for (const key of ['account_number', 'branch']) {
      await expect(linkedCard.getByTestId(`mapping-row-${key}`).getByRole('combobox')).toHaveValue(key);
    }
    // The person's name and birth date come from the primary source, not from Accounts.
    for (const key of ['full_name', 'date_of_birth']) {
      await expect(linkedCard.getByTestId(`mapping-row-${key}`).getByRole('combobox')).toHaveValue('');
    }
    await expect(linkedCard.getByLabel(`Key field in ${customers.name}`)).toHaveValue('nic');
    await expect(linkedCard.getByLabel(`Key field in ${accounts.name}`)).toHaveValue('national_id');

    // Save: map the linked NIC region explicitly to the key field, then save the mapping.
    await linkedCard.getByLabel('Template field for NIC').selectOption('national_id');
    await linkedCard.getByRole('button', { name: 'Save mapping' }).click();
    await expect(page.getByText(`Mapping saved for ${accounts.name}`)).toBeVisible();
    await expect(linkedCard.getByRole('button', { name: 'Save mapping' })).toBeDisabled();

    // What the server keeps (also after a reload).
    const sources = await operatorApi.get(`${DOCS}/blueprints/${blueprintId}/sources`);
    const primary = sources.find((s) => s.role === 'PRIMARY');
    const linked = sources.find((s) => s.role === 'LINKED');
    expect(primary).toMatchObject({ templateId: customers.templateId, linkFieldPrimary: null, linkFieldThis: null });
    expect(primary.mapping).toEqual({ full_name: 'full_name', nic: 'nic', date_of_birth: 'date_of_birth' });
    expect(linked).toMatchObject({ templateId: accounts.templateId, linkFieldPrimary: 'nic', linkFieldThis: 'national_id' });
    expect(linked.mapping).toMatchObject({ account_number: 'account_number', branch: 'branch', nic: 'national_id' });
    await page.reload();
    await page.getByRole('tab', { name: 'Data sources' }).click();
    await expect(
      page.getByTestId(`source-${accounts.templateId}`).getByTestId('mapping-row-nic').getByRole('combobox')
    ).toHaveValue('national_id');
    // A second primary is refused (409).
    const second = await operatorApi.request('POST', `${DOCS}/blueprints/${blueprintId}/sources`, {
      json: { templateId: accounts.templateId, role: 'PRIMARY' },
    });
    expect(second.status).toBe(409);
  });

  test('REC-04 a document is filled from a record and its linked record; fill-empty-only keeps values', async ({
    page,
    operatorApi,
  }) => {
    test.skip(!blueprintId || !janeId, 'needs REC-01..03');
    const title = uniqueName('E2E Jane account');
    await openAs(page, OPERATOR(), '/creation/documents');
    const heading = page.getByRole('heading', { name: 'New document', level: 2 });
    await expect(async () => {
      if (!(await heading.isVisible())) await page.getByRole('button', { name: 'New document' }).click({ timeout: 2000 });
      await expect(heading).toBeVisible({ timeout: 2000 });
    }).toPass({ timeout: 30_000 });
    await page.getByLabel('Template').selectOption({ label: `${blueprintName} (1 page)` });
    await page.getByPlaceholder(/Survey/).fill(title);
    await page.getByRole('button', { name: 'Create document' }).click();
    await expect(page).toHaveURL(/\/creation\/documents\/\d+$/);
    instanceId = Number(new URL(page.url()).pathname.split('/').pop());
    await expect(page.getByRole('heading', { name: title })).toBeVisible();

    // "Data from the flow" -> search "Jane" -> Use.
    await page.getByRole('button', { name: 'Fill from data' }).click();
    const dialog = page.getByRole('dialog', { name: 'Fill from data' });
    await expect(dialog.getByLabel('Data source')).toHaveValue(`t:${customers.templateId}`);
    await dialog.getByPlaceholder('Name, ID number…').fill('Jane');
    const useButtons = dialog.getByRole('button', { name: /^Use / });
    await expect(useButtons).toHaveCount(1); // "Jane Invalid" was never indexed
    await expect(dialog.getByRole('button', { name: `Use ${JANE.full_name} · ${JANE.nic}` })).toBeVisible();
    await useButtons.first().click();

    const preview = dialog.getByTestId('fill-preview');
    await expect(preview).toBeVisible();
    const expected = {
      full_name: JANE.full_name,
      nic: JANE.nic,
      date_of_birth: JANE.date_of_birth,
      account_number: JANE_ACCOUNT.account_number,
      branch: JANE_ACCOUNT.branch,
    };
    for (const [key, value] of Object.entries(expected)) {
      await expect(preview.getByTestId(`fill-value-${key}`)).toHaveText(value);
    }
    await expect(preview.getByTestId(`linked-${accounts.templateId}`)).toContainText(
      `Also from ${accounts.name} matched by national_id`
    );
    await expect(preview.getByText('5 of 5 fields selected')).toBeVisible();
    await preview.getByRole('checkbox', { name: 'Fill Branch' }).uncheck();
    await expect(preview.getByText('4 of 5 fields selected')).toBeVisible();
    await dialog.getByRole('button', { name: 'Apply' }).click();
    await expect(page.getByText(`4 fields filled from ${customers.name}`)).toBeVisible();
    await expect(dialog).toBeHidden();

    // The values are on the page; Branch stays empty.
    const onPage = (key) => page.getByTestId(`field-${key}`).locator('input');
    await expect(onPage('full_name')).toHaveValue(JANE.full_name);
    await expect(onPage('nic')).toHaveValue(JANE.nic);
    await expect(onPage('date_of_birth')).toHaveValue(JANE.date_of_birth);
    await expect(onPage('account_number')).toHaveValue(JANE_ACCOUNT.account_number);
    await expect(onPage('branch')).toHaveValue('');
    const chips = page.getByTestId('source-records');
    await expect(chips.getByText(`${customers.name} · record ${janeId}`, { exact: true })).toBeVisible();
    await expect(chips.getByText(`${accounts.name} · record ${janeAccountId} (linked)`, { exact: true })).toBeVisible();
    let doc = await operatorApi.get(`${DOCS}/instances/${instanceId}`);
    expect(doc.values).toMatchObject({
      full_name: JANE.full_name,
      nic: JANE.nic,
      date_of_birth: JANE.date_of_birth,
      account_number: JANE_ACCOUNT.account_number,
    });
    expect(doc.values.branch ?? '').toBe('');
    expect(doc.sourceRecords).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ recordId: janeId, templateId: customers.templateId, role: 'PRIMARY' }),
        expect.objectContaining({ recordId: janeAccountId, templateId: accounts.templateId, role: 'LINKED' }),
      ])
    );

    // A second record, empty fields only: the existing values stay, the empty Branch is filled.
    await page.getByRole('button', { name: 'Fill from data' }).click();
    await dialog.getByPlaceholder('Name, ID number…').fill('John');
    await dialog.getByRole('button', { name: `Use ${JOHN.full_name} · ${JOHN.nic}` }).click();
    await expect(preview.getByTestId('fill-value-full_name')).toHaveText(JOHN.full_name);
    await preview.getByRole('checkbox', { name: 'Fill empty fields only' }).check();
    const fullNameBox = preview.getByRole('checkbox', { name: 'Fill Full name' });
    await expect(fullNameBox).not.toBeChecked();
    await expect(fullNameBox).toBeDisabled();
    await expect(preview.getByRole('checkbox', { name: 'Fill Branch' })).toBeChecked();
    await expect(preview.getByText('1 of 5 fields selected')).toBeVisible();
    await dialog.getByRole('button', { name: 'Apply' }).click();
    await expect(page.getByText(`1 field filled from ${customers.name}`)).toBeVisible();
    await expect(onPage('full_name')).toHaveValue(JANE.full_name);
    await expect(onPage('nic')).toHaveValue(JANE.nic);
    await expect(onPage('account_number')).toHaveValue(JANE_ACCOUNT.account_number);
    await expect(onPage('branch')).toHaveValue(JOHN_ACCOUNT.branch);
    await expect(chips.getByText(`${accounts.name} · record ${johnAccountId} (linked)`, { exact: true })).toBeVisible();
    doc = await operatorApi.get(`${DOCS}/instances/${instanceId}`);
    expect(doc.values).toMatchObject({ full_name: JANE.full_name, branch: JOHN_ACCOUNT.branch });
  });

  test('REC-05 layout from the builder: move a field, add one, save to the template; the PDF draws at the new place', async ({
    page,
    operatorApi,
  }) => {
    test.skip(!instanceId, 'needs the document from REC-04');
    await openAs(page, OPERATOR(), `/creation/documents/${instanceId}`);
    const before = await operatorApi.get(`${DOCS}/instances/${instanceId}`);
    expect(before).toMatchObject({ canEditLayout: true, blueprintVersion: 1, blueprintCurrentVersion: 1 });
    const oldName = proposedRegions.find((r) => r.key === 'full_name');

    await page.getByRole('button', { name: 'Edit layout' }).click();
    const nameBox = page.getByTestId('layout-region-full_name');
    await expect(nameBox).toBeVisible();
    const view = page.getByTestId('page-view');
    // Where the box is on the page, as a fraction of the page height (independent of scrolling).
    const fyOnPage = async () => {
      const [b, v] = [await nameBox.boundingBox(), await view.boundingBox()];
      return (b.y - v.y) / v.height;
    };
    const startFy = await fyOnPage();
    expect(Math.abs(startFy - oldName.fy)).toBeLessThan(0.02);
    // Down by 25 % of the page: into the empty band under the Branch box.
    await dragBy(page, nameBox, 0, (await view.boundingBox()).height * 0.25);
    await expect.poll(fyOnPage).toBeGreaterThan(startFy + 0.2);
    await expect(page.getByText(/Unsaved layout changes/)).toBeVisible();

    // A new field, drawn further down in the empty band, named "Reference".
    await page.getByRole('toolbar', { name: 'Layout tools' }).getByRole('button', { name: 'Add field' }).click();
    const drawLayer = page.getByTestId('draw-layer');
    await drawLayer.evaluate((el) => el.scrollIntoView({ block: 'center' }));
    const layer = await drawLayer.boundingBox();
    const at = (fx, fy) => [layer.x + fx * layer.width, layer.y + fy * layer.height];
    const [x0, y0] = at(0.12, 0.6);
    const [x1, y1] = at(0.5, 0.625);
    await page.mouse.move(x0, y0);
    await page.mouse.down();
    await page.mouse.move((x0 + x1) / 2, (y0 + y1) / 2, { steps: 4 });
    await page.mouse.move(x1, y1, { steps: 4 });
    await page.mouse.up();
    await page.getByLabel('Label', { exact: true }).fill('Reference');
    await expect(page.getByLabel(/^Key/)).toHaveValue('reference');
    await expect(page.getByTestId('layout-region-reference')).toBeVisible();

    await page.getByRole('button', { name: 'Save layout to template' }).click();
    await expect(page.getByText('Layout saved to the template as version 2')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Edit layout' })).toBeVisible();

    // The template has a new version with the moved and the new field; the document moved to it, values kept.
    const v2 = await getBlueprint(operatorApi, blueprintId);
    expect(v2).toMatchObject({ currentVersion: 2 });
    const newName = v2.regions.find((r) => r.key === 'full_name');
    expect(newName.fy).toBeGreaterThan(oldName.fy + 0.2);
    expect(Math.abs(newName.fx - oldName.fx)).toBeLessThan(0.01);
    const reference = v2.regions.find((r) => r.key === 'reference');
    expect(reference).toMatchObject({ kind: 'TEXT', page: 0, source: 'MANUAL' });
    let doc = await operatorApi.get(`${DOCS}/instances/${instanceId}`);
    expect(doc).toMatchObject({ blueprintVersion: 2, blueprintCurrentVersion: 2 });
    expect(doc.values).toMatchObject({ full_name: JANE.full_name, nic: JANE.nic, branch: JOHN_ACCOUNT.branch });
    // The sources' mappings still apply (region keys unchanged).
    expect((await operatorApi.get(`${DOCS}/blueprints/${blueprintId}/sources`)).length).toBe(2);

    // Type the new field on the page.
    await page.getByTestId('field-reference').locator('input').fill(REFERENCE);
    await expect(page.getByTestId('save-state')).toHaveText('Saved');
    doc = await operatorApi.get(`${DOCS}/instances/${instanceId}`);
    expect(doc.values.reference).toBe(REFERENCE);

    // Generate and download.
    const generated = page.waitForResponse((r) => r.url().endsWith(`/instances/${instanceId}/generate`));
    await page.getByRole('button', { name: 'Generate' }).click();
    expect((await (await generated).json()).pdfReady).toBe(true);
    const downloadEvent = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Download PDF' }).click();
    const pdf = await fs.promises.readFile(await (await downloadEvent).path());
    const text = pageTexts(pdf)[0];
    for (const value of [JANE.full_name, JANE.date_of_birth, JOHN_ACCOUNT.branch, REFERENCE]) {
      expect(containsText(text, value), `the PDF shows "${value}"`).toBe(true);
    }
    // REPORT masking applies again when the PDF is generated: no raw NIC, account number kept to its last 4.
    expect(containsText(text, JANE.nic)).toBe(false);
    expect(containsText(text, JANE_ACCOUNT.account_number)).toBe(false);
    expect(containsText(text, `****${JANE_ACCOUNT.account_number.slice(-4)}`)).toBe(true);
    expect(text).toContain('Customer Account Form');

    // Position: the name is drawn in the NEW box (blank on the template), the OLD box shows only the form.
    const blank = await templatePage(operatorApi, blueprintId, 0);
    const preview = await getBytes(operatorApi, `${DOCS}/instances/${instanceId}/pages/0`);
    expect(preview.status).toBe(200);
    const filledPage = decodePng(preview.bytes);
    expect(inkInBox(blank, boxOf(newName)), 'the new box is in the empty band').toBe(0);
    expect(inkInBox(filledPage, boxOf(newName)), 'the name is drawn in the new box').toBeGreaterThan(20);
    expect(inkInBox(blank, boxOf(reference))).toBe(0);
    expect(inkInBox(filledPage, boxOf(reference)), 'the reference is drawn in its box').toBeGreaterThan(20);
    expect(inkInBox(filledPage, boxOf(oldName)), 'nothing is drawn in the old box any more').toBe(
      inkInBox(blank, boxOf(oldName))
    );
  });

  test('REC-06 a read-only user sees masked NIC/phone and cannot fill; another bank sees nothing', async ({
    operatorApi,
    platformApi,
    platformCheckerApi,
  }) => {
    test.skip(!janeId || !instanceId, 'needs REC-01 and REC-04');
    const { email } = await createUserViaMakerChecker({ role: 'READ_ONLY', displayName: 'E2E records reader' });
    const reader = await sessionFor(email);

    const found = await searchRecords(reader, customers.templateId, 'Jane');
    expect(found.total).toBe(1);
    const values = found.items[0].values;
    expect(values.nic).toBe('********');
    expect(values.phone).toBe(`****${JANE.phone.slice(-2)}`);
    expect(values.phone).not.toBe(JANE.phone);
    expect(values.full_name).not.toBe(JANE.full_name); // NAME masks too: first letter kept
    expect(values.full_name.startsWith('J')).toBe(true);
    expect(values.date_of_birth).toBe(JANE.date_of_birth); // NONE stays raw
    expect((await reader.get(`${RECORDS}/${janeId}`)).values.nic).toBe('********');
    const linked = await searchRecords(reader, accounts.templateId);
    expect(linked.items.every((r) => r.values.account_number.startsWith('****'))).toBe(true);

    // Filling needs doc:write: refused.
    const fill = await reader.request('POST', `${DOCS}/instances/${instanceId}/fill-from-record`, {
      json: { recordId: janeId, overwrite: 'ALL' },
    });
    expect(fill.status).toBe(403);
    const fillPreview = await reader.request('GET', `${DOCS}/instances/${instanceId}/fill-preview?recordId=${janeId}`);
    expect(fillPreview.status).toBe(403);
    // ... and the owner's document is untouched.
    expect((await operatorApi.get(`${DOCS}/instances/${instanceId}`)).values.full_name).toBe(JANE.full_name);

    // Another bank: neither the template's records nor a record by id.
    if (!platformApi) {
      test.info().annotations.push({ type: 'skipped-part', description: 'no platform admins: second bank not checked' });
      return;
    }
    const bankB = await onboardBank(platformApi, platformCheckerApi);
    const adminB = await sessionFor(bankB.admins[0]);
    const search = await adminB.request('GET', `${RECORDS}?templateId=${customers.templateId}&q=Jane`);
    expect([404, 403], `search status ${search.status}`).toContain(search.status);
    if (search.status === 200) expect(search.body.total).toBe(0);
    expect((await adminB.request('GET', `${RECORDS}/${janeId}`)).status).toBe(404);
    expect((await adminB.request('GET', `${RECORDS}/${janeAccountId}`)).status).toBe(404);
  });

  test('REC-07 deleting the Customers ingestion job removes its records from search', async ({ operatorApi }) => {
    test.skip(!customersJob, 'needs REC-01');
    await operatorApi.del(`/api/ingestion/jobs/${customersJob.ingestionJobId}`);
    await expect
      .poll(async () => (await searchRecords(operatorApi, customers.templateId)).total, { timeout: 30_000 })
      .toBe(0);
    expect((await searchRecords(operatorApi, customers.templateId, 'Jane')).total).toBe(0);
    expect((await operatorApi.request('GET', `${RECORDS}/${janeId}`)).status).toBe(404);
    expect((await operatorApi.request('GET', `${RECORDS}/${johnId}`)).status).toBe(404);
    if (instanceId) {
      const preview = await operatorApi.request(
        'GET',
        `${DOCS}/instances/${instanceId}/fill-preview?recordId=${janeId}`
      );
      expect(preview.status).toBe(404);
    }
    // The Accounts job is not touched.
    expect((await searchRecords(operatorApi, accounts.templateId)).total).toBe(2);
  });
});
