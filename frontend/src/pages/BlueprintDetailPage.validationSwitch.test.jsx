import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import BlueprintDetailPage from './BlueprintDetailPage';
import { installPointerEvents, mockLayout, stubImageFetch } from '../test/pdfTestUtils';
import { expectAccessible } from '../test/a11y';
import { createInstance, getBlueprint, listBlueprintSources, listInstances, setBlueprintValidation } from '../api/doccreation';
import { getRules } from '../api/docRules';
import { getDataMapping, listDataTemplates } from '../api/dataTemplates';
import { getTemplates } from '../api/cache';

vi.mock('../api/doccreation', () => ({
  blueprintPagePath: (id, page, { variant = 'template', version } = {}) =>
    `/api/doccreation/blueprints/${id}/pages/${page}?variant=${variant}${version ? `&version=${version}` : ''}`,
  downloadBlueprintFile: vi.fn(),
  getBlueprint: vi.fn(),
  listBlueprintVersions: vi.fn(),
  saveOverlay: vi.fn(),
  setBlueprintValidation: vi.fn(),
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

// BlueprintDto.validationEnabled (data-templates contract §8): the default for new documents.
const blueprint = (overrides = {}) => ({
  id: 4,
  name: 'Loan form',
  status: 'READY',
  flow: 'BLANK',
  version: 2,
  currentVersion: 2,
  pages: [{ index: 0, ...PAGE }],
  regions: [region('full_name', 'Full name')],
  edits: [],
  warnings: [],
  fonts: [],
  validationEnabled: true,
  ...overrides,
});

const MAPPED = {
  dataTemplateId: 3,
  dataTemplateName: 'Customer',
  revision: 1,
  mappedCount: 1,
  mappableCount: 1,
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
  ],
  fields: [{ name: 'full_name', label: 'Full name', kind: 'TEXT' }],
};

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
const stepNames = () => screen.getAllByRole('tab').map((t) => t.getAttribute('aria-label'));
const SWITCH = 'Validate documents from this template';

describe('BlueprintDetailPage — "Validate documents from this template"', () => {
  beforeAll(installPointerEvents);

  beforeEach(() => {
    vi.clearAllMocks();
    mockLayout();
    getBlueprint.mockResolvedValue(blueprint());
    getRules.mockResolvedValue({ revision: 1, rules: [], inherited: [], fields: [], ruleTypes: [] });
    getDataMapping.mockResolvedValue(MAPPED);
    listDataTemplates.mockResolvedValue([{ id: 3, name: 'Customer', fieldCount: 1, ruleCount: 0, usedBy: 1 }]);
    listBlueprintSources.mockResolvedValue([]);
    listInstances.mockResolvedValue([]);
    getTemplates.mockResolvedValue([]);
    setBlueprintValidation.mockImplementation(async (id, enabled) => blueprint({ validationEnabled: enabled }));
  });

  it('the header switch saves at once, with a toast, and drops the Validation step when off', async () => {
    renderPage();
    const toggle = await screen.findByRole('switch', { name: SWITCH });
    expect(toggle).toHaveAttribute('aria-checked', 'true');
    expect(stepNames()).toEqual(['Upload', 'Validation', 'Map fields', 'Documents']);

    await userEvent.click(toggle);
    expect(setBlueprintValidation).toHaveBeenCalledWith('4', false);
    expect(await screen.findByText(/Validation is off — new documents from this template skip the validation steps/)).toBeInTheDocument();
    await waitFor(() => expect(stepNames()).toEqual(['Upload', 'Map fields', 'Documents']));
    expect(screen.getByRole('switch', { name: SWITCH })).toHaveAttribute('aria-checked', 'false');

    await userEvent.click(tab('Map fields'));
    expect(screen.getByTestId('validation-off-note')).toHaveTextContent(
      'Validation is off — fields are filled from the flow, nothing is checked.'
    );
    // No Validation step to choose the data template on: it is chosen here.
    expect(await screen.findByTestId('data-template-chooser')).toBeInTheDocument();
    expectAccessible();

    // On again: the Validation step comes back.
    await userEvent.click(screen.getByRole('switch', { name: SWITCH }));
    expect(setBlueprintValidation).toHaveBeenLastCalledWith('4', true);
    await waitFor(() => expect(stepNames()).toEqual(['Upload', 'Validation', 'Map fields', 'Documents']));
    expect(screen.queryByTestId('validation-off-note')).not.toBeInTheDocument();
  });

  it('a ?step=validation link falls back to Map fields with a note when validation is off', async () => {
    getBlueprint.mockResolvedValue(blueprint({ validationEnabled: false }));
    renderPage('/creation/blueprints/4?step=validation');
    await waitFor(() => expect(tab('Map fields')).toHaveAttribute('aria-selected', 'true'));
    expect(stepNames()).toEqual(['Upload', 'Map fields', 'Documents']);
    expect(screen.getByTestId('validation-step-off')).toHaveTextContent(
      'Validation is off for this template, so there is no Validation step — showing Map fields instead.'
    );
    expect(screen.getByTestId('validation-off-note')).toBeInTheDocument();
    expect(screen.queryByTestId('field-rules')).not.toBeInTheDocument();
    expectAccessible();

    // Turning it on shows the Validation step the link asked for.
    await userEvent.click(screen.getByRole('switch', { name: SWITCH }));
    await waitFor(() => expect(tab('Validation')).toHaveAttribute('aria-selected', 'true'));
    expect(screen.queryByTestId('validation-step-off')).not.toBeInTheDocument();
  });

  it('a draft shows the switch on the Upload step’s confirm screen', async () => {
    getBlueprint.mockResolvedValue(blueprint({ status: 'DRAFT', version: 1, currentVersion: 1 }));
    renderPage();
    await screen.findByRole('tab', { name: 'Upload' });
    const toggles = screen.getAllByRole('switch', { name: SWITCH });
    expect(toggles).toHaveLength(1);
    expect(screen.getByTestId('confirm-validation-switch')).toContainElement(toggles[0]);
    await userEvent.click(toggles[0]);
    expect(setBlueprintValidation).toHaveBeenCalledWith('4', false);
    await waitFor(() => expect(stepNames()).toEqual(['Upload', 'Map fields', 'Documents']));
    expectAccessible();
  });

  it('a failed save keeps the switch as it was and shows the error', async () => {
    setBlueprintValidation.mockRejectedValue(new Error('Only the template owner can change this'));
    renderPage();
    await userEvent.click(await screen.findByRole('switch', { name: SWITCH }));
    expect(await screen.findByText('Only the template owner can change this')).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: SWITCH })).toHaveAttribute('aria-checked', 'true');
    expect(stepNames()).toEqual(['Upload', 'Validation', 'Map fields', 'Documents']);
  });

  it('Documents: "Validate this document" starts from the template’s default and can be overridden', async () => {
    getBlueprint.mockResolvedValue(blueprint({ validationEnabled: false }));
    createInstance.mockResolvedValue({ id: 13, instanceId: 13 });
    renderPage('/creation/blueprints/4?step=documents');
    const step = await screen.findByTestId('documents-step');
    const box = within(step).getByRole('checkbox', { name: 'Validate this document' });
    expect(box).not.toBeChecked();
    expect(step).toHaveTextContent('The template’s default is off.');
    expectAccessible();

    await userEvent.type(within(step).getByLabelText(/^Title/), 'Loan — Ann');
    await userEvent.click(box);
    expect(box).toBeChecked();
    await userEvent.click(within(step).getByRole('button', { name: 'Create a document' }));
    expect(createInstance).toHaveBeenCalledWith(4, 'Loan — Ann', true);
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('?step=data'));
  });

  it('Documents: the default follows the template switch', async () => {
    createInstance.mockResolvedValue({ id: 14, instanceId: 14 });
    renderPage('/creation/blueprints/4?step=documents');
    const step = await screen.findByTestId('documents-step');
    expect(within(step).getByRole('checkbox', { name: 'Validate this document' })).toBeChecked();
    await userEvent.click(screen.getByRole('switch', { name: SWITCH }));
    await waitFor(() =>
      expect(within(step).getByRole('checkbox', { name: 'Validate this document' })).not.toBeChecked()
    );
    await userEvent.type(within(step).getByLabelText(/^Title/), 'Loan — Bo');
    await userEvent.click(within(step).getByRole('button', { name: 'Create a document' }));
    expect(createInstance).toHaveBeenCalledWith(4, 'Loan — Bo', false);
  });
});
