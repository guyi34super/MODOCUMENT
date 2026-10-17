import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import BlueprintDetailPage from './BlueprintDetailPage';
import { installPointerEvents, mockLayout, stubImageFetch } from '../test/pdfTestUtils';
import { expectAccessible } from '../test/a11y';

import { createInstance, getBlueprint, listBlueprintSources, listInstances, saveOverlay } from '../api/doccreation';
import { getRules } from '../api/docRules';
import {
  createDataTemplate,
  getDataMapping,
  listDataTemplates,
  saveDataMapping,
  setBlueprintDataTemplate,
} from '../api/dataTemplates';
import { getTemplates } from '../api/cache';

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
  listInstances: vi.fn(),
  createInstance: vi.fn(),
}));

vi.mock('../api/docRules', async (importOriginal) => ({
  ...(await importOriginal()),
  getRules: vi.fn(),
  saveRules: vi.fn(),
}));

vi.mock('../api/dataTemplates', async (importOriginal) => ({
  ...(await importOriginal()),
  getDataMapping: vi.fn(),
  listDataTemplates: vi.fn(),
  setBlueprintDataTemplate: vi.fn(),
  saveDataMapping: vi.fn(),
  createDataTemplate: vi.fn(),
}));

vi.mock('../api/cache', () => ({ getTemplates: vi.fn() }));
vi.mock('../api/templates', () => ({ getAllFields: vi.fn(() => Promise.resolve([])) }));

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
  props: null,
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
  regions: [region('full_name', 'Full name'), region('dob', 'Date of birth', { kind: 'DATE', fy: 0.3, sortOrder: 1 })],
  edits: [],
  warnings: [],
  fonts: [],
  ...overrides,
});

const NO_TEMPLATE = { dataTemplateId: null, dataTemplateName: null, revision: 0, mappedCount: 0, mappableCount: 0, rows: [], fields: [] };

const MAPPED = {
  dataTemplateId: 3,
  dataTemplateName: 'Customer',
  revision: 1,
  mappedCount: 1,
  mappableCount: 2,
  rows: [
    {
      regionKey: 'full_name',
      label: 'Full name',
      kind: 'TEXT',
      page: 0,
      fx: 0.1,
      fy: 0.1,
      fw: 0.3,
      fh: 0.02,
      mappedField: 'full_name',
      suggestion: { field: 'full_name', score: 1, sameName: true },
    },
    {
      regionKey: 'dob',
      label: 'Date of birth',
      kind: 'DATE',
      page: 0,
      fx: 0.1,
      fy: 0.3,
      fw: 0.3,
      fh: 0.02,
      mappedField: null,
      suggestion: { field: 'birth_date', score: 0.85, sameName: false },
    },
  ],
  fields: [
    { name: 'full_name', label: 'Full name', kind: 'TEXT' },
    { name: 'birth_date', label: 'Birth date', kind: 'DATE' },
  ],
};

const FIELDS = [
  { key: 'full_name', label: 'Full name', kind: 'TEXT', page: 0 },
  { key: 'dob', label: 'Date of birth', kind: 'DATE', page: 0 },
];

const rulesDto = (overrides = {}) => ({
  revision: 3,
  rules: [{ id: 7, regionKey: 'dob', ruleType: 'EXPIRY_DATE', config: {}, message: null, severity: 'WARNING', origin: 'MANUAL', sortOrder: 0, orphan: false }],
  inherited: [
    {
      id: -21,
      regionKey: 'full_name',
      ruleType: 'REQUIRED',
      config: {},
      message: null,
      severity: 'ERROR',
      origin: 'DATA_TEMPLATE',
      sourceTemplateName: 'Customer',
      sortOrder: 0,
    },
  ],
  fields: FIELDS,
  ruleTypes: [],
  ...overrides,
});

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.search}</output>;
}

function renderPage(path = '/creation/blueprints/4') {
  stubImageFetch();
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ToastProvider>
        <Routes>
          <Route
            path="/creation/blueprints/:id"
            element={
              <>
                <BlueprintDetailPage />
                <LocationProbe />
              </>
            }
          />
          <Route path="/creation/documents/:id" element={<LocationProbe />} />
        </Routes>
      </ToastProvider>
    </MemoryRouter>
  );
}

const tab = (name) => screen.getByRole('tab', { name });

describe('BlueprintDetailPage — the steps of a PDF template', () => {
  beforeAll(installPointerEvents);

  beforeEach(() => {
    vi.clearAllMocks();
    mockLayout();
    getBlueprint.mockResolvedValue(blueprint());
    getRules.mockResolvedValue(rulesDto());
    getDataMapping.mockResolvedValue(MAPPED);
    listDataTemplates.mockResolvedValue([
      { id: 3, name: 'Customer', fieldCount: 2, ruleCount: 4, usedBy: 1 },
      { id: 5, name: 'Vehicle', fieldCount: 6, ruleCount: 1, usedBy: 0 },
    ]);
    listBlueprintSources.mockResolvedValue([]);
    listInstances.mockResolvedValue([]);
    getTemplates.mockResolvedValue([]);
  });

  it('shows Upload ✓, Validation, Map fields and Documents and keeps the step in the URL', async () => {
    renderPage();
    const upload = await screen.findByRole('tab', { name: 'Upload' });
    expect(screen.getAllByRole('tab').map((t) => t.getAttribute('aria-label'))).toEqual([
      'Upload',
      'Validation',
      'Map fields',
      'Documents',
    ]);
    expect(upload).toHaveAttribute('aria-selected', 'true');
    expect(upload).toHaveAttribute('data-done', 'true');
    // A data template is chosen: Validation is done; 1 of 2 mapped: Map fields is not.
    await waitFor(() => expect(tab('Validation')).toHaveAttribute('data-done', 'true'));
    expect(tab('Map fields')).not.toHaveAttribute('data-done');
    expect(screen.getByTestId('region-full_name')).toBeInTheDocument();

    await userEvent.click(tab('Map fields'));
    expect(tab('Map fields')).toHaveAttribute('aria-selected', 'true');
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('?step=mapping'));
    expect(await screen.findByTestId('field-matcher')).toBeInTheDocument();
    expectAccessible();
  });

  it.each([
    ['?step=validation', 'Validation', 'data-template-chooser'],
    ['?step=mapping', 'Map fields', 'field-matcher'],
    ['?step=documents', 'Documents', 'documents-step'],
    ['?tab=layout', 'Upload', 'region-full_name'],
  ])('opens %s on its step', async (search, name, testId) => {
    renderPage(`/creation/blueprints/4${search}`);
    expect(await screen.findByTestId(testId)).toBeInTheDocument();
    expect(tab(name)).toHaveAttribute('aria-selected', 'true');
  });

  it('Validation: chooses a data template and shows its summary and inherited rules above the PDF’s own', async () => {
    getDataMapping.mockResolvedValue(NO_TEMPLATE);
    getRules.mockResolvedValue(rulesDto({ inherited: [] }));
    setBlueprintDataTemplate.mockResolvedValue(MAPPED);
    renderPage('/creation/blueprints/4?step=validation');

    const chooser = await screen.findByTestId('data-template-chooser');
    const select = await within(chooser).findByLabelText('Data template');
    await waitFor(() => expect(within(select).getAllByRole('option')).toHaveLength(3));
    expect(select).toHaveValue('');
    expect(screen.queryByTestId('inherited-rules')).not.toBeInTheDocument();

    getRules.mockResolvedValue(rulesDto());
    await userEvent.selectOptions(select, 'Customer');
    expect(setBlueprintDataTemplate).toHaveBeenCalledWith('4', 3);
    expect(await screen.findByText('Data template chosen — 1 of 2 fields matched automatically')).toBeInTheDocument();
    const summary = screen.getByTestId('data-template-summary');
    expect(summary).toHaveTextContent('Customer2 fields · 4 rules · 1 of 2 PDF fields mapped');
    expect(within(summary).getByRole('link', { name: 'Edit the data template' })).toHaveAttribute('href', '/creation/data-templates/3');

    const inherited = await screen.findByTestId('inherited-rules');
    expect(within(inherited).getByRole('heading', { name: 'Inherited from Customer' })).toBeInTheDocument();
    expect(within(inherited).getByRole('table', { name: 'Rules of Full name (inherited)' })).toHaveTextContent('Must be filled in');
    expect(within(inherited).queryByRole('button', { name: /Delete/ })).not.toBeInTheDocument();
    expect(within(inherited).getByText('From the data template Customer')).toBeInTheDocument();
    // The PDF's own extra rules come after, editable.
    expect(screen.getByRole('heading', { name: 'This PDF’s own extra rules' })).toBeInTheDocument();
    expect(within(screen.getByTestId('rules-field-dob')).getByRole('button', { name: 'Delete Future date rule of Date of birth' })).toBeInTheDocument();
    expect(inherited.compareDocumentPosition(screen.getByTestId('rules-field-dob')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expectAccessible();

    await userEvent.click(within(summary).getByRole('button', { name: 'Next: map fields' }));
    expect(tab('Map fields')).toHaveAttribute('aria-selected', 'true');
  });

  it('Validation: creates a data template inline and chooses it', async () => {
    getDataMapping.mockResolvedValue(NO_TEMPLATE);
    createDataTemplate.mockResolvedValue({ id: 9, name: 'Loan applicant', fields: [], rules: [] });
    setBlueprintDataTemplate.mockResolvedValue({ ...NO_TEMPLATE, dataTemplateId: 9, dataTemplateName: 'Loan applicant' });
    renderPage('/creation/blueprints/4?step=validation');

    await userEvent.click(await screen.findByRole('button', { name: 'Create new' }));
    const dialog = await screen.findByRole('dialog', { name: 'New data template' });
    await userEvent.type(within(dialog).getByLabelText('Name'), 'Loan applicant');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create' }));
    expect(createDataTemplate).toHaveBeenCalledWith({ name: 'Loan applicant', description: '' });
    await waitFor(() => expect(setBlueprintDataTemplate).toHaveBeenCalledWith('4', 9));
    expect(await screen.findByTestId('data-template-summary')).toHaveTextContent('Loan applicant');
    expect(listDataTemplates).toHaveBeenCalledTimes(2);
  });

  it('Map fields: the matcher, then the data sources under “Data from the flow (advanced)”', async () => {
    listBlueprintSources.mockResolvedValue([
      {
        id: 5,
        templateId: 3,
        templateName: 'Customers',
        role: 'PRIMARY',
        managed: true,
        mapping: { full_name: 'full_name' },
        fields: [{ name: 'full_name', dataType: 'Text' }],
        suggestions: [],
      },
    ]);
    saveDataMapping.mockResolvedValue({ ...MAPPED, mappedCount: 2, rows: MAPPED.rows.map((r) => ({ ...r, mappedField: r.suggestion.field })) });
    renderPage('/creation/blueprints/4?step=mapping');

    const matcher = await screen.findByTestId('field-matcher');
    expect(within(matcher).getByTestId('mapped-count')).toHaveTextContent('1 of 2 mapped');
    const advanced = screen.getByRole('button', { name: 'Data from the flow (advanced)' });
    expect(advanced).toHaveAttribute('aria-expanded', 'false');
    expect(listBlueprintSources).not.toHaveBeenCalled();

    await userEvent.click(within(matcher).getByRole('button', { name: 'Accept all suggestions (1)' }));
    await userEvent.click(within(matcher).getByRole('button', { name: 'Save mapping' }));
    expect(saveDataMapping).toHaveBeenCalledWith('4', { full_name: 'full_name', dob: 'birth_date' });
    await waitFor(() => expect(tab('Map fields')).toHaveAttribute('data-done', 'true'));
    // The rules follow the mapping: they are read again.
    expect(getRules).toHaveBeenCalledTimes(2);

    await userEvent.click(advanced);
    const sources = await screen.findByTestId('source-3');
    expect(within(sources).getByText('kept in step with the data template')).toBeInTheDocument();
    expectAccessible();
  });

  it('Map fields without a data template sends the user to Validation', async () => {
    getDataMapping.mockResolvedValue(NO_TEMPLATE);
    renderPage('/creation/blueprints/4?step=mapping');
    await userEvent.click(await screen.findByRole('button', { name: 'Go to Validation' }));
    expect(tab('Validation')).toHaveAttribute('aria-selected', 'true');
  });

  it('confirming a draft upload moves on to Validation', async () => {
    getBlueprint.mockResolvedValue(blueprint({ status: 'DRAFT', version: 1, currentVersion: 1 }));
    saveOverlay.mockImplementation(async (id, body) => blueprint({ version: 1, currentVersion: 1, regions: body.regions }));
    renderPage();
    const upload = await screen.findByRole('tab', { name: 'Upload' });
    expect(upload).not.toHaveAttribute('data-done');
    expect(getDataMapping).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: 'Confirm template' }));
    await waitFor(() => expect(tab('Validation')).toHaveAttribute('aria-selected', 'true'));
    expect(tab('Upload')).toHaveAttribute('data-done', 'true');
    expect(await screen.findByTestId('data-template-chooser')).toBeInTheDocument();
    expect(getDataMapping).toHaveBeenCalledWith('4');
  });

  it('Documents: lists this template’s documents and creates one on the builder’s first step', async () => {
    listInstances.mockResolvedValue([
      { id: 11, instanceId: 11, title: 'Loan — Jane', blueprintId: 4, status: 'VALIDATED', validationState: 'VALIDATED', errorCount: 0 },
      { id: 12, instanceId: 12, title: 'Loan — Joe', blueprintId: 4, status: 'DRAFT', validationState: 'ERRORS', errorCount: 2 },
    ]);
    createInstance.mockResolvedValue({ id: 13, instanceId: 13 });
    renderPage('/creation/blueprints/4?step=documents');

    const table = await screen.findByRole('table', { name: 'Documents from this template' });
    expect(listInstances).toHaveBeenCalledWith({ blueprintId: '4' });
    expect(within(table).getByRole('link', { name: 'Loan — Jane' })).toHaveAttribute('href', '/creation/documents/11');
    expect(within(table).getAllByTestId('validation-state').map((n) => n.textContent)).toEqual(['Validated', '2 errors']);
    expectAccessible();

    await userEvent.type(screen.getByLabelText(/^Title/), 'Loan — Ann');
    await userEvent.click(screen.getByRole('button', { name: 'Create a document' }));
    expect(createInstance).toHaveBeenCalledWith(4, 'Loan — Ann');
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('?step=data'));
  });
});
