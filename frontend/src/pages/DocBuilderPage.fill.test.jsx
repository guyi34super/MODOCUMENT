import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import DocBuilderPage from './DocBuilderPage';
import { stubImageFetch } from '../test/pdfTestUtils';
import { expectAccessible } from '../test/a11y';

import { fillFromRecord, getFillPreview, getInstance, listBlueprintSources, saveValues } from '../api/doccreation';
import { getRecord, searchRecords } from '../api/records';
import { getTemplates } from '../api/cache';
import { getAllFields } from '../api/templates';

vi.mock('../api/doccreation', () => ({
  blueprintPagePath: (id, page, { variant, version } = {}) =>
    `/api/doccreation/blueprints/${id}/pages/${page}?variant=${variant}&version=${version}`,
  imageContentPath: (id, imageId) => `/api/doccreation/instances/${id}/images/${imageId}/content`,
  instancePagePath: (id, page) => `/api/doccreation/instances/${id}/pages/${page}`,
  deleteImage: vi.fn(),
  downloadInstance: vi.fn(),
  generateInstance: vi.fn(),
  getInstance: vi.fn(),
  placeImage: vi.fn(),
  saveOverlay: vi.fn(),
  saveValues: vi.fn(),
  submitInstance: vi.fn(),
  upgradeInstance: vi.fn(),
  uploadImages: vi.fn(),
  listBlueprintSources: vi.fn(),
  getFillPreview: vi.fn(),
  fillFromRecord: vi.fn(),
}));

// The page reads the template's field rules; none here.
vi.mock('../api/docRules', async (importOriginal) => ({
  ...(await importOriginal()),
  getRules: vi.fn(() => Promise.resolve({ revision: 0, rules: [], fields: [], ruleTypes: [] })),
  saveRules: vi.fn(),
}));

vi.mock('../api/records', async (importOriginal) => ({
  ...(await importOriginal()),
  searchRecords: vi.fn(),
  getRecord: vi.fn(),
}));

vi.mock('../api/cache', () => ({ getTemplates: vi.fn() }));
vi.mock('../api/templates', () => ({ getAllFields: vi.fn() }));

const PAGE = { width: 600, height: 800, rotation: 0 };

const region = (key, label, kind, overrides = {}) => ({
  key,
  label,
  group: 'Applicant',
  kind,
  page: 0,
  fx: 0.1,
  fy: 0.1,
  fw: 0.3,
  fh: 0.02,
  fontName: 'Helvetica',
  fontSize: 10,
  color: '#000000',
  align: 'LEFT',
  maxLines: 1,
  acroFieldName: null,
  required: false,
  sortOrder: 0,
  source: 'DETECTED',
  confidence: 0.9,
  ...overrides,
});

const doc = (overrides = {}) => ({
  id: 5,
  title: 'Loan — Jane',
  status: 'DRAFT',
  blueprintId: 1,
  blueprintName: 'Loan form',
  blueprintVersion: 2,
  blueprintCurrentVersion: 2,
  pages: [{ index: 0, ...PAGE }],
  regions: [
    region('full_name', 'Full name', 'TEXT', { fy: 0.2 }),
    region('dob', 'Date of birth', 'DATE', { fy: 0.25, sortOrder: 1 }),
    region('amount', 'Amount', 'NUMBER', { fy: 0.3, sortOrder: 2 }),
  ],
  values: { full_name: 'Jane' },
  images: [],
  overflow: [],
  sourceRecords: [],
  canEditLayout: false,
  blueprintEdits: [],
  ...overrides,
});

const CUSTOMERS = {
  id: 5,
  templateId: 3,
  templateName: 'Customers',
  role: 'PRIMARY',
  linkFieldPrimary: null,
  linkFieldThis: null,
  mapping: { full_name: 'full_name' },
  fields: [
    { name: 'full_name', dataType: 'Text' },
    { name: 'nic', dataType: 'Text' },
    { name: 'dob', dataType: 'Date' },
  ],
  suggestions: [],
};
const ACCOUNTS = {
  id: 6,
  templateId: 7,
  templateName: 'Accounts',
  role: 'LINKED',
  linkFieldPrimary: 'nic',
  linkFieldThis: 'national_id',
  mapping: { amount: 'balance' },
  fields: [{ name: 'national_id' }, { name: 'balance' }],
  suggestions: [],
};

const JANE = {
  recordId: 12,
  templateId: 3,
  templateName: 'Customers',
  jobId: 40,
  rowIndex: 2,
  values: { full_name: 'Jane Doe', nic: 'J12****67', dob: '1990-01-02' },
  createdAt: '2026-10-01T10:00:00',
};

const page = (items) => ({ items, page: 0, size: 20, total: items.length });

function renderPage() {
  stubImageFetch();
  return render(
    <MemoryRouter initialEntries={['/creation/documents/5']}>
      <ToastProvider>
        <Routes>
          <Route path="/creation/documents/:id" element={<DocBuilderPage />} />
        </Routes>
      </ToastProvider>
    </MemoryRouter>
  );
}

const onPage = () => within(screen.getByTestId('page-view'));
const ready = async () => within(await screen.findByTestId('page-view')).findByLabelText('Full name');

async function openPanel() {
  await ready();
  await userEvent.click(screen.getByRole('button', { name: 'Fill from data' }));
  return screen.findByRole('dialog', { name: 'Fill from data' });
}

/** Opens the panel, waits for Jane in the results and presses "Use". */
async function useJane() {
  const dialog = await openPanel();
  await within(dialog).findByText('Jane Doe');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Use Jane Doe · J12****67' }));
  await within(dialog).findByRole('table', { name: 'Values to fill' });
  return dialog;
}

describe('DocBuilderPage — Fill from data', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getInstance.mockResolvedValue(doc());
    saveValues.mockImplementation(async (id, values) => doc({ values: { ...doc().values, ...values } }));
    // Linked first on purpose: the panel lists the primary source first.
    listBlueprintSources.mockResolvedValue([ACCOUNTS, CUSTOMERS]);
    searchRecords.mockResolvedValue(page([JANE]));
    getTemplates.mockResolvedValue([
      { templateId: 3, name: 'Customers' },
      { templateId: 7, name: 'Accounts' },
      { templateId: 9, name: 'Leads' },
    ]);
    getAllFields.mockResolvedValue([{ fieldName: 'lead_name' }, { fieldName: 'phone' }]);
  });

  it('searches the primary source with a 300 ms debounce and an optional field filter', async () => {
    renderPage();
    const dialog = await openPanel();

    const source = within(dialog).getByLabelText('Data source');
    await waitFor(() => expect(source).toHaveValue('t:3'));
    expect(within(source).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Customers (primary)',
      'Accounts (linked)',
      'Other templates…',
    ]);
    expect(listBlueprintSources).toHaveBeenCalledWith(1);
    await waitFor(() =>
      expect(searchRecords).toHaveBeenCalledWith({ templateId: '3', q: '', field: undefined, page: 0, size: 20 })
    );

    // Typing searches once, after the pause — never per keystroke.
    await userEvent.type(within(dialog).getByLabelText('Search records'), 'Jane');
    expect(searchRecords).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(searchRecords).toHaveBeenCalledTimes(2));
    expect(searchRecords).toHaveBeenLastCalledWith({ templateId: '3', q: 'Jane', field: undefined, page: 0, size: 20 });
    expect(searchRecords.mock.calls.map(([args]) => args.q)).toEqual(['', 'Jane']);

    // The field filter lists the source's fields.
    await userEvent.selectOptions(within(dialog).getByLabelText('Search in'), 'nic');
    await waitFor(() =>
      expect(searchRecords).toHaveBeenLastCalledWith({ templateId: '3', q: 'Jane', field: 'nic', page: 0, size: 20 })
    );

    // Values are shown masked, as the server returned them.
    const table = await within(dialog).findByRole('table', { name: 'Records' });
    expect(within(table).getByText('Jane Doe')).toBeInTheDocument();
    expect(within(table).getByText('J12****67')).toBeInTheDocument();
  });

  it('searches any readable template through "Other templates…"', async () => {
    listBlueprintSources.mockResolvedValue([]);
    renderPage();
    const dialog = await openPanel();

    await waitFor(() => expect(within(dialog).getByLabelText('Data source')).toHaveValue('__other'));
    const template = within(dialog).getByLabelText('Template');
    await waitFor(() => expect(template).toBeEnabled());
    await userEvent.selectOptions(template, 'Leads');

    await waitFor(() =>
      expect(searchRecords).toHaveBeenCalledWith({ templateId: '9', q: '', field: undefined, page: 0, size: 20 })
    );
    expect(getAllFields).toHaveBeenCalledWith('9');
    expect(await within(dialog).findByRole('option', { name: 'phone' })).toBeInTheDocument();
  });

  it('previews a record with every field ticked; "Fill empty fields only" unticks the filled ones', async () => {
    getFillPreview.mockResolvedValue({
      recordId: 12,
      templateId: 3,
      mappingSource: 'SAVED',
      linked: [],
      fields: [
        { regionKey: 'full_name', label: 'Full name', value: 'Jane Doe', currentValue: 'Jane!', sourceTemplateId: 3, sourceField: 'full_name', willOverwrite: true, sameName: true },
        { regionKey: 'dob', label: 'Date of birth', value: '1990-01-02', currentValue: '', sourceTemplateId: 3, sourceField: 'dob', willOverwrite: false, sameName: false },
      ],
    });
    renderPage();
    // Pending typing is saved before the preview reads the current values.
    await userEvent.type(await ready(), '!');
    const dialog = await useJane();

    expect(saveValues).toHaveBeenCalledWith('5', { full_name: 'Jane!' });
    expect(saveValues.mock.invocationCallOrder[0]).toBeLessThan(getFillPreview.mock.invocationCallOrder[0]);
    expect(getFillPreview).toHaveBeenCalledWith(5, 12);

    const name = within(dialog).getByRole('checkbox', { name: 'Fill Full name' });
    const dob = within(dialog).getByRole('checkbox', { name: 'Fill Date of birth' });
    expect(name).toBeChecked();
    expect(dob).toBeChecked();
    const table = within(dialog).getByRole('table', { name: 'Values to fill' });
    expect(within(table).getByText('Customers.full_name')).toBeInTheDocument();
    expect(within(table).getByText('will be replaced')).toBeInTheDocument();

    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Fill empty fields only' }));
    expect(name).not.toBeChecked();
    expect(name).toBeDisabled();
    expect(dob).toBeChecked();
    expect(within(dialog).getByText('1 of 2 fields selected')).toBeInTheDocument();

    fillFromRecord.mockResolvedValue(doc({ values: { full_name: 'Jane!', dob: '1990-01-02' } }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Apply' }));
    expect(fillFromRecord).toHaveBeenCalledWith(5, {
      recordId: 12,
      linkedRecordIds: {},
      fields: ['dob'],
      overwrite: 'EMPTY_ONLY',
    });
    expect(await screen.findByText('1 field filled from Customers')).toBeInTheDocument();
  });

  it('chooses another linked match, applies the exact selection and shows where the values came from', async () => {
    getFillPreview.mockResolvedValue({
      recordId: 12,
      templateId: 3,
      mappingSource: 'SAVED',
      linked: [{ templateId: 7, templateName: 'Accounts', matchedBy: 'nic', recordId: 88, alternatives: [91] }],
      fields: [
        { regionKey: 'full_name', label: 'Full name', value: 'Jane Doe', currentValue: 'Jane', sourceTemplateId: 3, sourceField: 'full_name', willOverwrite: true, sameName: true },
        { regionKey: 'dob', label: 'Date of birth', value: '1990-01-02', currentValue: '', sourceTemplateId: 3, sourceField: 'dob', willOverwrite: false, sameName: true },
        { regionKey: 'amount', label: 'Amount', value: '500', currentValue: '', sourceTemplateId: 7, sourceField: 'balance', willOverwrite: false, sameName: false },
      ],
    });
    getRecord.mockImplementation(async (rid) =>
      rid === 88
        ? { recordId: 88, templateId: 7, values: { national_id: 'ACC-1', balance: '500' } }
        : { recordId: 91, templateId: 7, values: { national_id: 'ACC-2', balance: '750' } }
    );
    fillFromRecord.mockResolvedValue(
      doc({
        values: { full_name: 'Jane', dob: '1990-01-02', amount: '750' },
        sourceRecords: [
          { recordId: 12, templateId: 3, templateName: 'Customers', role: 'PRIMARY' },
          { recordId: 91, templateId: 7, templateName: 'Accounts', role: 'LINKED' },
        ],
      })
    );
    renderPage();
    const dialog = await useJane();

    expect(within(dialog).getByTestId('linked-7')).toHaveTextContent('Also from Accounts matched by nic');
    const chooser = within(dialog).getByLabelText('Record from Accounts (2 matches)');
    await waitFor(() =>
      expect(within(chooser).getAllByRole('option').map((o) => o.textContent)).toEqual(['ACC-1 · 500 (newest)', 'ACC-2 · 750'])
    );
    expect(getRecord).toHaveBeenCalledWith(88);
    expect(getRecord).toHaveBeenCalledWith(91);
    expect(within(dialog).getByTestId('fill-value-amount')).toHaveTextContent('500');
    await userEvent.selectOptions(chooser, '91');
    expect(within(dialog).getByTestId('fill-value-amount')).toHaveTextContent('750');
    expect(within(dialog).getByText('Accounts.balance')).toBeInTheDocument();

    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Fill Full name' }));
    expectAccessible();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Apply' }));

    expect(fillFromRecord).toHaveBeenCalledTimes(1);
    expect(fillFromRecord).toHaveBeenCalledWith(5, {
      recordId: 12,
      linkedRecordIds: { 7: 91 },
      fields: ['dob', 'amount'],
      overwrite: 'ALL',
    });
    expect(await screen.findByText('2 fields filled from Customers')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Fill from data' })).not.toBeInTheDocument());
    expect(onPage().getByLabelText('Amount')).toHaveValue(750);
    const chips = within(screen.getByRole('list', { name: 'Filled from' }));
    expect(chips.getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'Customers · record 12',
      'Accounts · record 91 (linked)',
    ]);
  });

  it('goes back from the preview to the results', async () => {
    getFillPreview.mockResolvedValue({ recordId: 12, templateId: 3, mappingSource: 'AUTO', linked: [], fields: [] });
    renderPage();
    const dialog = await openPanel();
    await userEvent.click(await within(dialog).findByRole('button', { name: 'Use Jane Doe · J12****67' }));

    expect(await within(dialog).findByText('This record has no values for this document’s fields.')).toBeInTheDocument();
    expect(within(dialog).getByText(/fields were matched by name/)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Apply' })).toBeDisabled();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Back to results' }));
    expect(await within(dialog).findByRole('table', { name: 'Records' })).toBeInTheDocument();
  });

  it('is disabled while the document is locked', async () => {
    getInstance.mockResolvedValue(
      doc({
        status: 'SUBMITTED',
        sourceRecords: [{ recordId: 12, templateId: 3, templateName: 'Customers', role: 'PRIMARY' }],
      })
    );
    renderPage();
    await ready();

    const button = screen.getByRole('button', { name: 'Fill from data' });
    expect(button).toBeDisabled();
    await userEvent.click(button);
    expect(screen.queryByRole('dialog', { name: 'Fill from data' })).not.toBeInTheDocument();
    expect(listBlueprintSources).not.toHaveBeenCalled();
    expect(screen.getByText('Customers · record 12')).toBeInTheDocument();
  });
});
