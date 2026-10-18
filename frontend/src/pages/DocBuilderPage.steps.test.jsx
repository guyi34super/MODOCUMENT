import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import DocBuilderPage from './DocBuilderPage';
import { stubImageFetch } from '../test/pdfTestUtils';
import { expectAccessible } from '../test/a11y';

import {
  downloadInstance,
  fillFromRecord,
  generateInstance,
  getFillPreview,
  getInstance,
  listBlueprintSources,
  saveValues,
  validateInstance,
} from '../api/doccreation';
import { searchRecords } from '../api/records';

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

vi.mock('../api/docRules', async (importOriginal) => ({
  ...(await importOriginal()),
  getRules: vi.fn(() => Promise.resolve({ revision: 2, rules: [], fields: [], ruleTypes: [] })),
  saveRules: vi.fn(),
}));

vi.mock('../api/records', async (importOriginal) => ({
  ...(await importOriginal()),
  searchRecords: vi.fn(),
  getRecord: vi.fn(),
}));

vi.mock('../api/cache', () => ({ getTemplates: vi.fn(() => Promise.resolve([])) }));
vi.mock('../api/templates', () => ({ getAllFields: vi.fn(() => Promise.resolve([])) }));

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
  props: null,
  ...overrides,
});

const NOT_CHECKED = { state: 'DRAFT', validatedAt: null, rulesRevision: 2, rulesChangedSinceValidation: false, errors: [], warnings: [], setupWarnings: [], checked: false };

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
    region('full_name', 'Full name', 'TEXT', { fy: 0.2, required: true }),
    region('dob', 'Date of birth', 'DATE', { fy: 0.3, sortOrder: 1 }),
  ],
  values: { full_name: 'Jane' },
  images: [],
  overflow: [],
  sourceRecords: [],
  canEditLayout: false,
  blueprintEdits: [],
  validation: NOT_CHECKED,
  validationEnabled: true,
  ...overrides,
});

const validated = (overrides = {}) =>
  doc({
    status: 'VALIDATED',
    validation: { ...NOT_CHECKED, state: 'VALIDATED', validatedAt: '2026-10-16T10:00:00', checked: true },
    ...overrides,
  });

const failed = () =>
  doc({
    validation: {
      ...NOT_CHECKED,
      checked: true,
      errors: [{ regionKey: 'dob', label: 'Date of birth', ruleId: 4, code: 'DATE_RANGE', message: 'Date of birth must be on or before 18 years ago.' }],
    },
  });

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.search}</output>;
}

function renderPage(search = '') {
  stubImageFetch();
  return render(
    <MemoryRouter initialEntries={[`/creation/documents/5${search}`]}>
      <ToastProvider>
        <Routes>
          <Route
            path="/creation/documents/:id"
            element={
              <>
                <DocBuilderPage />
                <LocationProbe />
              </>
            }
          />
        </Routes>
      </ToastProvider>
    </MemoryRouter>
  );
}

const tab = (name) => screen.getByRole('tab', { name });
const ready = async () => within(await screen.findByTestId('page-view')).findByLabelText('Full name');

describe('DocBuilderPage — guided steps', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getInstance.mockResolvedValue(doc());
    saveValues.mockImplementation(async (id, values) =>
      doc({ values: { ...doc().values, ...values }, status: 'DRAFT', validation: NOT_CHECKED })
    );
  });

  it('shows the four steps under the header, opens on Fix fields and locks Download until validated', async () => {
    renderPage();
    await ready();
    expect(screen.getByRole('tablist', { name: 'Document steps' })).toBeInTheDocument();
    expect(screen.getAllByRole('tab').map((t) => t.getAttribute('aria-label'))).toEqual([
      'Choose data',
      'Fix fields',
      'Save & validate',
      'Download',
    ]);
    expect(tab('Fix fields')).toHaveAttribute('aria-selected', 'true');
    const download = tab('Download');
    expect(download).toHaveAttribute('aria-disabled', 'true');
    expect(download).toHaveAttribute('title', 'Save and validate first');
    expect(download).toHaveAccessibleDescription('Save and validate first');
    await userEvent.click(download);
    expect(tab('Fix fields')).toHaveAttribute('aria-selected', 'true');
    // The header shortcuts obey the same lock.
    expect(screen.getByRole('button', { name: 'Download PDF' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Generate' })).toBeDisabled();
    expectAccessible();
  });

  it('Choose data: searches inline, previews "field ← flow field: value" ticked with ⚠ for values that would fail, applies and moves on', async () => {
    listBlueprintSources.mockResolvedValue([
      { id: 5, templateId: 3, templateName: 'Customers', role: 'PRIMARY', mapping: {}, fields: [{ name: 'full_name' }, { name: 'dob' }], suggestions: [] },
    ]);
    searchRecords.mockResolvedValue({
      items: [{ recordId: 12, templateId: 3, templateName: 'Customers', values: { full_name: 'Jane Doe', dob: '2015-01-02' } }],
      page: 0,
      size: 20,
      total: 1,
    });
    getFillPreview.mockResolvedValue({
      recordId: 12,
      templateId: 3,
      mappingSource: 'SAVED',
      linked: [],
      fields: [
        { regionKey: 'full_name', label: 'Full name', value: 'Jane Doe', currentValue: 'Jane', sourceTemplateId: 3, sourceField: 'full_name', sameName: true, wouldFail: [] },
        {
          regionKey: 'dob',
          label: 'Date of birth',
          value: '2015-01-02',
          currentValue: '',
          sourceTemplateId: 3,
          sourceField: 'dob',
          sameName: true,
          wouldFail: [{ code: 'DATE_RANGE', message: 'Date of birth must be on or before 18 years ago.' }],
        },
      ],
    });
    fillFromRecord.mockResolvedValue(doc({ values: { full_name: 'Jane Doe', dob: '2015-01-02' } }));
    renderPage('?step=data');

    const panel = await screen.findByTestId('choose-data');
    expect(tab('Choose data')).toHaveAttribute('aria-selected', 'true');
    expect(screen.queryByTestId('page-view')).not.toBeInTheDocument();
    await userEvent.click(await within(panel).findByRole('button', { name: /^Use / }));

    const values = await within(panel).findByRole('list', { name: 'Values to fill' });
    expect(within(values).getByTestId('fill-row-full_name')).toHaveTextContent('Full name ← Customers.full_name: Jane Doe');
    expect(within(values).getByTestId('fill-row-dob')).toHaveTextContent('Date of birth ← Customers.dob: 2015-01-02');
    expect(within(values).getByRole('checkbox', { name: 'Fill Full name' })).toBeChecked();
    expect(within(values).getByRole('checkbox', { name: 'Fill Date of birth' })).toBeChecked();
    expect(within(values).getByTestId('would-fail-dob')).toHaveTextContent('⚠ Would fail: Date of birth must be on or before 18 years ago.');
    expect(within(values).queryByTestId('would-fail-full_name')).not.toBeInTheDocument();
    expectAccessible();

    await userEvent.click(within(panel).getByRole('button', { name: 'Apply' }));
    expect(fillFromRecord).toHaveBeenCalledWith(5, { recordId: 12, linkedRecordIds: {}, fields: ['full_name', 'dob'], overwrite: 'ALL' });
    await waitFor(() => expect(tab('Fix fields')).toHaveAttribute('aria-selected', 'true'));
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('?step=fix'));
    expect(await screen.findByText('2 fields filled from Customers')).toBeInTheDocument();
  });

  it('Choose data can be skipped', async () => {
    listBlueprintSources.mockResolvedValue([]);
    renderPage('?step=data');
    await userEvent.click(await screen.findByRole('button', { name: 'Skip' }));
    expect(tab('Fix fields')).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByTestId('page-view')).toBeInTheDocument();
  });

  it('Save & validate: shows the errors with Go to field, back on Fix fields', async () => {
    validateInstance.mockResolvedValue(failed());
    renderPage('?step=save');
    const step = await screen.findByTestId('save-step');
    expect(within(step).getByTestId('save-result')).toHaveTextContent('Not checked yet.');
    await userEvent.click(within(step).getByRole('button', { name: 'Save and validate' }));
    expect(validateInstance).toHaveBeenCalledWith('5');

    const result = await within(step).findByTestId('save-result');
    expect(within(result).getByText('1 error to fix')).toBeInTheDocument();
    expect(within(result).getByRole('list', { name: 'Errors to fix' })).toHaveTextContent(
      'Date of birth:Date of birth must be on or before 18 years ago.'
    );
    expect(tab('Download')).toHaveAttribute('aria-disabled', 'true');
    expectAccessible();

    await userEvent.click(within(result).getByRole('button', { name: 'Go to field: Date of birth' }));
    expect(tab('Fix fields')).toHaveAttribute('aria-selected', 'true');
    const input = within(await screen.findByRole('region', { name: 'Fields' })).getByLabelText(/^Date of birth/);
    await waitFor(() => expect(input).toHaveFocus());
  });

  it('unlocks Download once validated, then downloads from the Download step', async () => {
    validateInstance.mockResolvedValue(validated());
    downloadInstance.mockResolvedValue(undefined);
    renderPage('?step=save');
    const step = await screen.findByTestId('save-step');
    await userEvent.click(within(step).getByRole('button', { name: 'Save and validate' }));
    expect(await within(step).findByText('Validated')).toBeInTheDocument();
    expect(tab('Download')).not.toHaveAttribute('aria-disabled');
    expect(tab('Save & validate')).toHaveAttribute('data-done', 'true');

    await userEvent.click(within(step).getByRole('button', { name: 'Continue to Download' }));
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('?step=download'));
    const download = screen.getByTestId('download-step');
    expect(within(download).getByRole('button', { name: 'Generate' })).toBeEnabled();
    expect(within(download).queryByRole('button', { name: 'Submit for release' })).not.toBeInTheDocument();
    await userEvent.click(within(download).getByRole('button', { name: 'Download PDF' }));
    expect(downloadInstance).toHaveBeenCalledWith('5', 'Loan — Jane.pdf');
    expectAccessible();
  });

  it('re-locks Download as soon as a value changes', async () => {
    getInstance.mockResolvedValue(validated());
    renderPage();
    const name = await ready();
    expect(tab('Download')).not.toHaveAttribute('aria-disabled');
    await userEvent.type(name, '!');
    expect(tab('Download')).toHaveAttribute('aria-disabled', 'true');
    expect(tab('Download')).toHaveAccessibleDescription('Save and validate first');
  });

  it('says "The rules changed — save again" and lands a Download link on Save & validate', async () => {
    getInstance.mockResolvedValue(validated({ validation: { ...validated().validation, rulesChangedSinceValidation: true } }));
    renderPage('?step=download');
    expect(await screen.findByTestId('save-step')).toBeInTheDocument();
    expect(tab('Save & validate')).toHaveAttribute('aria-selected', 'true');
    expect(tab('Download')).toHaveAttribute('aria-disabled', 'true');
    expect(tab('Download')).toHaveAccessibleDescription('The rules changed — save again');
    expect(screen.getByRole('button', { name: 'Download PDF' })).toHaveAttribute('title', 'The rules changed — save again');
    expect(screen.getByRole('button', { name: 'Download PDF' })).toBeDisabled();
  });

  it('shows the server’s reason when a generate is refused because the rules changed (409 NOT_VALIDATED)', async () => {
    getInstance.mockResolvedValue(validated({ status: 'GENERATED' }));
    generateInstance.mockRejectedValue(
      Object.assign(new Error('The rules changed since this document was validated; save it again to re-check.'), {
        status: 409,
        code: 'NOT_VALIDATED',
      })
    );
    renderPage('?step=download');
    const step = await screen.findByTestId('download-step');
    expect(within(step).getByRole('button', { name: 'Submit for release' })).toBeInTheDocument();

    getInstance.mockResolvedValue(validated({ status: 'GENERATED', validation: { ...validated().validation, rulesChangedSinceValidation: true } }));
    await userEvent.click(within(step).getByRole('button', { name: 'Generate' }));
    expect(
      await screen.findByText('The rules changed since this document was validated; save it again to re-check.')
    ).toBeInTheDocument();
    // Read again: the step locks and the builder shows Save & validate.
    await waitFor(() => expect(tab('Save & validate')).toHaveAttribute('aria-selected', 'true'));
    expect(tab('Download')).toHaveAccessibleDescription('The rules changed — save again');
  });
});
