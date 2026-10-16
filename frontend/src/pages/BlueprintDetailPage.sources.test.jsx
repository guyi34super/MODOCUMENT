import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import BlueprintDetailPage from './BlueprintDetailPage';
import { stubImageFetch } from '../test/pdfTestUtils';
import { expectAccessible } from '../test/a11y';

import {
  addBlueprintSource,
  deleteBlueprintSource,
  getBlueprint,
  listBlueprintSources,
  saveBlueprintSource,
} from '../api/doccreation';
import { getTemplates } from '../api/cache';
import { getAllFields } from '../api/templates';

vi.mock('../api/doccreation', () => ({
  blueprintPagePath: (id, page, { variant = 'template', version } = {}) =>
    `/api/doccreation/blueprints/${id}/pages/${page}?variant=${variant}${version ? `&version=${version}` : ''}`,
  downloadBlueprintFile: vi.fn(),
  getBlueprint: vi.fn(),
  listBlueprintVersions: vi.fn(),
  saveOverlay: vi.fn(),
  uploadEditImage: vi.fn(),
  listBlueprintSources: vi.fn(),
  addBlueprintSource: vi.fn(),
  saveBlueprintSource: vi.fn(),
  deleteBlueprintSource: vi.fn(),
}));

// The page reads the template's field rules; none here.
vi.mock('../api/docRules', async (importOriginal) => ({
  ...(await importOriginal()),
  getRules: vi.fn(() => Promise.resolve({ revision: 0, rules: [], fields: [], ruleTypes: [] })),
  saveRules: vi.fn(),
}));

vi.mock('../api/cache', () => ({ getTemplates: vi.fn() }));
vi.mock('../api/templates', () => ({ getAllFields: vi.fn() }));

const PAGE = { width: 600, height: 800, rotation: 0 };

const region = (key, label, overrides = {}) => ({
  key,
  label,
  group: null,
  kind: 'TEXT',
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
  sampleValue: null,
  ...overrides,
});

const blueprint = (overrides = {}) => ({
  id: 4,
  name: 'Loan form',
  status: 'READY',
  flow: 'BLANK',
  version: 2,
  currentVersion: 2,
  pages: [{ index: 0, ...PAGE }],
  regions: [
    region('full_name', 'Full name'),
    region('id_number', 'ID number', { fy: 0.2, sortOrder: 1 }),
    region('date_of_birth', 'Date of birth', { kind: 'DATE', fy: 0.3, sortOrder: 2 }),
    region('photo', 'Photo', { kind: 'IMAGE', fx: 0.7, fh: 0.15, maxLines: 0, sortOrder: 3 }),
    region('signature', 'Signature', { kind: 'SIGNATURE', fy: 0.8, fh: 0.05, maxLines: 0, sortOrder: 4 }),
  ],
  edits: [],
  warnings: [],
  detectedBy: 'CODE',
  documentCount: 0,
  ...overrides,
});

const customers = (overrides = {}) => ({
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
  suggestions: [
    { regionKey: 'date_of_birth', fieldName: 'dob', score: 0.92, sameName: false },
    { regionKey: 'id_number', fieldName: 'nic', score: 0.81, sameName: false },
  ],
  ...overrides,
});

const accounts = (overrides = {}) => ({
  id: 6,
  templateId: 7,
  templateName: 'Accounts',
  role: 'LINKED',
  linkFieldPrimary: 'nic',
  linkFieldThis: 'national_id',
  mapping: {},
  fields: [
    { name: 'national_id', dataType: 'Text' },
    { name: 'balance', dataType: 'Number' },
  ],
  suggestions: [],
  ...overrides,
});

function renderPage(path = '/creation/blueprints/4') {
  stubImageFetch();
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ToastProvider>
        <Routes>
          <Route path="/creation/blueprints/:id" element={<BlueprintDetailPage />} />
        </Routes>
      </ToastProvider>
    </MemoryRouter>
  );
}

async function openTab() {
  await userEvent.click(await screen.findByRole('tab', { name: 'Data sources' }));
  return screen.findByTestId('data-sources');
}

describe('BlueprintDetailPage — Data sources tab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getBlueprint.mockResolvedValue(blueprint());
    listBlueprintSources.mockResolvedValue([]);
    getTemplates.mockResolvedValue([
      { templateId: 3, name: 'Customers' },
      { templateId: 7, name: 'Accounts' },
    ]);
    getAllFields.mockResolvedValue([{ fieldName: 'national_id' }, { fieldName: 'balance' }]);
  });

  it('is a tab of a confirmed template; a draft shows it but asks to confirm first', async () => {
    getBlueprint.mockResolvedValue(blueprint({ status: 'DRAFT', version: 1, currentVersion: 1 }));
    const { unmount } = renderPage();
    await screen.findByRole('button', { name: 'Confirm template' });
    await userEvent.click(screen.getByRole('tab', { name: 'Data sources' }));
    expect(screen.queryByTestId('data-sources')).not.toBeInTheDocument();
    expect(screen.getByTestId('confirm-first')).toHaveTextContent(
      'Confirm the template first to add data sources and field rules.'
    );
    expect(listBlueprintSources).not.toHaveBeenCalled();
    unmount();

    getBlueprint.mockResolvedValue(blueprint());
    renderPage();
    expect(await screen.findByRole('tab', { name: 'Layout' })).toHaveAttribute('aria-selected', 'true');
    expect(listBlueprintSources).not.toHaveBeenCalled();
    await openTab();
    expect(listBlueprintSources).toHaveBeenCalledWith('4');
    expect(screen.getByRole('tab', { name: 'Data sources' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText('No data sources yet.')).toBeInTheDocument();
  });

  it('adds the primary source and shows its automatic mapping', async () => {
    addBlueprintSource.mockResolvedValue(customers({ suggestions: [] }));
    renderPage();
    await openTab();

    await userEvent.click(screen.getByRole('button', { name: 'Add data source' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add data source' });
    expect(within(dialog).getByRole('radio', { name: 'Primary' })).toBeChecked();
    expect(within(dialog).getByRole('radio', { name: 'Linked' })).toBeDisabled();
    const template = within(dialog).getByLabelText('Flow template');
    await waitFor(() => expect(template).toBeEnabled());
    await userEvent.selectOptions(template, 'Customers');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add source' }));

    expect(addBlueprintSource).toHaveBeenCalledWith('4', { templateId: 3, role: 'PRIMARY' });
    expect(
      await screen.findByText('Added Customers as the primary source — 1 field mapped automatically')
    ).toBeInTheDocument();
    const card = within(screen.getByTestId('source-3'));
    expect(card.getByRole('heading', { name: 'Customers' })).toBeInTheDocument();
    expect(card.getByLabelText('Template field for Full name')).toHaveValue('full_name');
    expect(within(card.getByTestId('mapping-row-full_name')).getByText('same name')).toBeInTheDocument();
    // Photo and signature boxes never take flow values.
    expect(card.queryByLabelText('Template field for Photo')).not.toBeInTheDocument();
    expect(card.queryByLabelText('Template field for Signature')).not.toBeInTheDocument();
  });

  it('adds a linked source only once both key fields are chosen', async () => {
    listBlueprintSources.mockResolvedValue([customers()]);
    addBlueprintSource.mockResolvedValue(accounts());
    renderPage();
    await openTab();

    await userEvent.click(screen.getByRole('button', { name: 'Add data source' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add data source' });
    expect(within(dialog).getByRole('radio', { name: 'Primary' })).toBeDisabled();
    expect(within(dialog).getByRole('radio', { name: 'Linked' })).toBeChecked();
    const template = within(dialog).getByLabelText('Flow template');
    await waitFor(() => expect(template).toBeEnabled());
    // The primary's own template is not offered again.
    expect(within(template).queryByRole('option', { name: 'Customers' })).not.toBeInTheDocument();
    await userEvent.selectOptions(template, 'Accounts');
    expect(getAllFields).toHaveBeenCalledWith('7');

    const add = within(dialog).getByRole('button', { name: 'Add source' });
    expect(add).toBeDisabled();
    await userEvent.selectOptions(within(dialog).getByLabelText('Key field in Customers'), 'nic');
    expect(add).toBeDisabled();
    const thisKey = within(dialog).getByLabelText('Key field in the new source');
    await within(thisKey).findByRole('option', { name: 'national_id' });
    await userEvent.selectOptions(thisKey, 'national_id');
    expect(add).toBeEnabled();
    expectAccessible();
    await userEvent.click(add);

    expect(addBlueprintSource).toHaveBeenCalledWith('4', {
      templateId: 7,
      role: 'LINKED',
      linkFieldPrimary: 'nic',
      linkFieldThis: 'national_id',
    });
    const card = within(await screen.findByTestId('source-7'));
    expect(card.getByText('Linked')).toBeInTheDocument();
    expect(card.getByLabelText('Key field in Customers')).toHaveValue('nic');
    expect(card.getByLabelText('Key field in Accounts')).toHaveValue('national_id');
  });

  it('changes the mapping, accepts every suggestion in one click and saves it', async () => {
    listBlueprintSources.mockResolvedValue([customers()]);
    saveBlueprintSource.mockImplementation(async (id, sourceId, body) => customers({ mapping: body.mapping }));
    renderPage();
    await openTab();
    const card = within(screen.getByTestId('source-3'));

    expect(within(card.getByTestId('mapping-row-date_of_birth')).getByText('suggested 0.92: dob')).toBeInTheDocument();
    expect(card.getByLabelText('Template field for Date of birth')).toHaveValue('');
    const save = card.getByRole('button', { name: 'Save mapping' });
    expect(save).toBeDisabled();

    await userEvent.click(card.getByRole('button', { name: 'Accept all suggestions (2)' }));
    expect(card.getByLabelText('Template field for Date of birth')).toHaveValue('dob');
    expect(card.getByLabelText('Template field for ID number')).toHaveValue('nic');
    expect(within(card.getByTestId('mapping-row-date_of_birth')).getByText('suggested 0.92')).toBeInTheDocument();
    expect(card.queryByRole('button', { name: /Accept all suggestions/ })).not.toBeInTheDocument();

    await userEvent.selectOptions(card.getByLabelText('Template field for Full name'), '');
    expect(card.getByLabelText('Template field for Full name')).toHaveValue('');
    await userEvent.click(save);

    expect(saveBlueprintSource).toHaveBeenCalledWith('4', 5, {
      mapping: { date_of_birth: 'dob', id_number: 'nic' },
    });
    expect(await screen.findByText('Mapping saved for Customers')).toBeInTheDocument();
    expect(save).toBeDisabled();
  });

  it('explains why the primary source cannot be removed while linked sources exist (409)', async () => {
    listBlueprintSources.mockResolvedValue([accounts(), customers()]);
    deleteBlueprintSource.mockRejectedValue(Object.assign(new Error('Conflict'), { status: 409 }));
    renderPage();
    await openTab();

    // The primary source is listed first.
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(
      expect.arrayContaining(['Customers', 'Accounts'])
    );
    const headings = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
    expect(headings.indexOf('Customers')).toBeLessThan(headings.indexOf('Accounts'));

    await userEvent.click(within(screen.getByTestId('source-3')).getByRole('button', { name: 'Delete source' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete data source' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete source' }));

    expect(deleteBlueprintSource).toHaveBeenCalledWith('4', 5);
    expect(
      await screen.findByText(
        'Customers is the primary source and linked sources depend on it. Delete the linked sources first.'
      )
    ).toBeInTheDocument();
    expect(screen.getByTestId('source-3')).toBeInTheDocument();
  });

  it('shows the sources read-only on an older version', async () => {
    listBlueprintSources.mockResolvedValue([customers()]);
    getBlueprint.mockResolvedValue(blueprint({ version: 1, currentVersion: 2 }));
    renderPage('/creation/blueprints/4?version=1');
    await openTab();

    expect(screen.getByText('Read-only')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add data source' })).not.toBeInTheDocument();
    const card = within(screen.getByTestId('source-3'));
    expect(card.getByLabelText('Template field for Full name')).toBeDisabled();
    expect(card.queryByRole('button', { name: 'Save mapping' })).not.toBeInTheDocument();
    expect(card.queryByRole('button', { name: /Accept/ })).not.toBeInTheDocument();
  });
});
