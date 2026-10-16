import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import DocBuilderPage from './DocBuilderPage';
import { installPointerEvents, mockLayout, stubImageFetch } from '../test/pdfTestUtils';
import { expectAccessible } from '../test/a11y';

import { getInstance, validateInstance } from '../api/doccreation';
import { getRules } from '../api/docRules';

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
  validateInstance: vi.fn(),
  listBlueprintSources: vi.fn(),
  getFillPreview: vi.fn(),
  fillFromRecord: vi.fn(),
}));

vi.mock('../api/docRules', async (importOriginal) => ({ ...(await importOriginal()), getRules: vi.fn(), saveRules: vi.fn() }));

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
  fontName: 'LiberationSans',
  fontSize: 10,
  color: '#000000',
  align: 'LEFT',
  maxLines: kind === 'MULTILINE' ? 3 : 1,
  acroFieldName: null,
  required: false,
  sortOrder: 0,
  source: 'DETECTED',
  confidence: 0.9,
  props: null,
  ...overrides,
});

const NOT_CHECKED = { state: 'DRAFT', validatedAt: null, rulesRevision: 2, rulesChangedSinceValidation: false, errors: [], warnings: [], setupWarnings: [], checked: false };

const FIELD_CHECKS = {
  full_name: {
    rules: [
      { id: 1, ruleType: 'REQUIRED', summary: 'Required', severity: 'ERROR', origin: 'REGION_FLAG' },
      { id: 2, ruleType: 'LENGTH', summary: '1–40 characters', severity: 'ERROR', origin: 'MANUAL' },
      {
        id: 3,
        ruleType: 'REGEX',
        summary: 'Starts with a capital',
        severity: 'ERROR',
        origin: 'IMPORTED',
        sourceTemplateName: 'People',
        sourceFieldName: 'name',
      },
      { id: 4, ruleType: 'REGEX', summary: 'No digits', severity: 'WARNING', origin: 'MANUAL' },
    ],
    builtIn: ['At most 40 characters'],
    mappedFrom: [{ templateId: 9, templateName: 'Customers', fieldName: 'full_name', role: 'PRIMARY' }],
  },
  marital: {
    rules: [{ id: 5, ruleType: 'REQUIRED', summary: 'Required', severity: 'ERROR', origin: 'MANUAL' }],
    builtIn: ['One of its options'],
    mappedFrom: [],
  },
  photo: {
    rules: [{ id: 6, ruleType: 'REQUIRED', summary: 'Required', severity: 'ERROR', origin: 'REGION_FLAG' }],
    builtIn: [],
    mappedFrom: [],
  },
  notes: {
    rules: [],
    builtIn: [],
    mappedFrom: [{ templateId: 11, templateName: 'Accounts', fieldName: 'remarks', role: 'LINKED' }],
  },
};

const doc = (overrides = {}) => ({
  id: 5,
  title: 'Loan — Jane',
  status: 'DRAFT',
  blueprintId: 1,
  blueprintName: 'Loan form',
  blueprintVersion: 2,
  blueprintCurrentVersion: 2,
  pages: [
    { index: 0, ...PAGE },
    { index: 1, ...PAGE },
  ],
  regions: [
    region('full_name', 'Full name', 'TEXT', {
      fy: 0.2,
      required: true,
      props: { helpText: 'As on the ID card', maxLength: 40 },
    }),
    region('marital', 'Marital status', 'CHOICE', { fy: 0.25, sortOrder: 1 }),
    region('photo', 'Photo', 'IMAGE', { fx: 0.6, fy: 0.5, fw: 0.2, fh: 0.2, sortOrder: 2, required: true }),
    region('notes', 'Notes', 'MULTILINE', { page: 1, sortOrder: 3 }),
  ],
  props: { marital: { options: ['Single', 'Married'] } },
  values: { full_name: 'Jane', marital: 'Single' },
  images: [],
  overflow: [],
  sourceRecords: [],
  canEditLayout: false,
  blueprintEdits: [],
  validation: NOT_CHECKED,
  fieldChecks: FIELD_CHECKS,
  rulesRevision: 2,
  canEditRules: false,
  ...overrides,
});

const passed = { ...NOT_CHECKED, state: 'VALIDATED', validatedAt: '2026-10-16T10:00:00', checked: true };

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

const ready = async () => within(await screen.findByTestId('page-view')).findByLabelText('Full name');
const fieldsPanel = () => within(screen.getByRole('region', { name: 'Fields' }));
const checksPanel = () => screen.queryByRole('region', { name: 'Checks' });
const button = (name) => screen.getByRole('button', { name });

describe('DocBuilderPage — the checks are visible in the document', () => {
  beforeAll(installPointerEvents);

  beforeEach(() => {
    vi.clearAllMocks();
    mockLayout();
    getInstance.mockResolvedValue(doc());
    getRules.mockResolvedValue({ revision: 2, rules: [], fields: [], ruleTypes: [] });
  });

  it('shows the Checks panel before any Save: state, counts and the rules of each field', async () => {
    renderPage();
    await ready();

    const panel = within(checksPanel());
    expect(panel.getByTestId('checks-state')).toHaveTextContent('Not checked yet — press Save to check this document.');
    // Required: two fields flagged, plus a REQUIRED rule on the dropdown; 6 rules in all.
    expect(panel.getByTestId('required-progress')).toHaveTextContent(
      '2 of 3 required fields filled · 6 rules on this template'
    );
    expect(panel.getByRole('button', { name: 'Rules for each field' })).toHaveAttribute('aria-expanded', 'true');
    const name = within(panel.getByTestId('field-rules-full_name'));
    expect(name.getByText(/Starts with a capital/)).toHaveTextContent('Starts with a capital · from People.name');
    expect(name.getByText('Filled from Customers.full_name')).toBeInTheDocument();
    expect(within(panel.getByTestId('field-rules-notes')).getByText('Filled from Accounts.remarks (linked)')).toBeInTheDocument();
    // No links for someone who may not edit the template's rules.
    expect(panel.queryByRole('link')).not.toBeInTheDocument();
    expectAccessible();
  });

  it('goes to a field from its name in the rules list', async () => {
    renderPage();
    await ready();
    await userEvent.click(within(screen.getByTestId('field-rules-notes')).getByRole('button', { name: 'Notes' }));
    expect(screen.getByText('Page 2 of 2')).toBeInTheDocument();
    await waitFor(() => expect(fieldsPanel().getByRole('textbox', { name: /^Notes/ })).toHaveFocus());
  });

  it('puts the checks under each field in short, as part of its description, and in the tooltip on the page', async () => {
    renderPage();
    const onPage = await ready();

    const chips = within(screen.getByTestId('field-checks-full_name'));
    for (const text of ['Required', '1–40 characters', 'Starts with a capital · imported', '+1 more', 'from Customers.full_name']) {
      expect(chips.getByText(text)).toBeInTheDocument();
    }
    expect(chips.queryByText('No digits')).not.toBeInTheDocument();

    const input = fieldsPanel().getByRole('textbox', { name: /^Full name/ });
    expect(input).toHaveAttribute('aria-describedby', 'field-help-full_name field-checks-text-full_name');
    expect(input).toHaveAccessibleDescription(
      'As on the ID card Rules: Required; 1–40 characters; Starts with a capital (imported from People.name); 1 more. From Customers.full_name.'
    );
    expect(onPage).toHaveAttribute('title', 'Rules: Required; 1–40 characters; Starts with a capital; No digits');
    expect(onPage).toHaveAccessibleDescription('As on the ID card');

    // A photo field and a mapped-only field are described the same way.
    expect(document.getElementById('field-panel-photo')).toHaveAccessibleDescription('Rules: Required.');
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(fieldsPanel().getByRole('textbox', { name: /^Notes/ })).toHaveAccessibleDescription('From Accounts.remarks (linked).');
    expect(within(screen.getByTestId('page-view')).getByLabelText('Notes')).not.toHaveAttribute('title');
    expectAccessible();
  });

  it('links the header to the template and its field rules, and the panel to its rules and data sources, for the owner', async () => {
    getInstance.mockResolvedValue(doc({ canEditRules: true }));
    renderPage();
    await ready();

    expect(screen.getByRole('link', { name: 'Loan form' })).toHaveAttribute('href', '/creation/blueprints/1');
    expect(screen.getByRole('link', { name: 'Field rules (6)' })).toHaveAttribute('href', '/creation/blueprints/1?tab=rules');
    const panel = within(checksPanel());
    expect(panel.getByRole('link', { name: 'Edit rules' })).toHaveAttribute('href', '/creation/blueprints/1?tab=rules');
    expect(panel.getByRole('link', { name: 'Map from data sources' })).toHaveAttribute(
      'href',
      '/creation/blueprints/1?tab=sources'
    );
    expectAccessible();
  });

  it('names the template without links for someone who may not edit its rules', async () => {
    renderPage();
    await ready();
    expect(screen.getByText('Template: Loan form')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Loan form' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Field rules/ })).not.toBeInTheDocument();
  });

  it('marks a generated document that passed its checks as Validated', async () => {
    getInstance.mockResolvedValue(doc({ status: 'GENERATED', validation: passed }));
    renderPage();
    await ready();
    expect(screen.getByText('GENERATED')).toBeInTheDocument();
    expect(screen.getByText('Validated')).toBeInTheDocument();
    expect(within(checksPanel()).getByTestId('checks-state')).toHaveTextContent('Generated · validated');
  });

  it.each([
    ['a generated document never checked', { status: 'GENERATED', validation: NOT_CHECKED }],
    ['a generated document with errors', { status: 'GENERATED', validation: { ...passed, errors: [{ regionKey: 'notes', message: 'x' }] } }],
    ['a validated document (its status says so)', { status: 'VALIDATED', validation: passed }],
  ])('shows no Validated chip on %s', async (_, overrides) => {
    getInstance.mockResolvedValue(doc(overrides));
    renderPage();
    await ready();
    expect(screen.queryByText('Validated')).not.toBeInTheDocument();
  });

  it("says \"You can't edit this document\" when the server refuses the check (403), and keeps Save disabled", async () => {
    validateInstance.mockRejectedValue(Object.assign(new Error('Forbidden'), { status: 403 }));
    renderPage();
    await ready();

    await userEvent.click(button('Save'));
    expect(await screen.findByText("You can't edit this document")).toBeInTheDocument();
    expect(validateInstance).toHaveBeenCalledWith('5');
    expect(button('Save')).toBeDisabled();
    expect(button('Save')).toHaveAccessibleDescription("You can't edit this document");
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expectAccessible();
  });

  it('hides the Checks panel while editing the layout', async () => {
    getInstance.mockResolvedValue(doc({ canEditLayout: true }));
    renderPage();
    await ready();
    expect(checksPanel()).toBeInTheDocument();

    await userEvent.click(button('Edit layout'));
    expect(checksPanel()).not.toBeInTheDocument();
    await userEvent.click(button('Cancel'));
    expect(checksPanel()).toBeInTheDocument();
  });
});
