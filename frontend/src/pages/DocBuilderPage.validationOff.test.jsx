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
  generateInstance,
  getInstance,
  saveValues,
  setInstanceValidation,
  submitInstance,
  validateInstance,
} from '../api/doccreation';

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
  setInstanceValidation: vi.fn(),
  submitInstance: vi.fn(),
  upgradeInstance: vi.fn(),
  uploadImages: vi.fn(),
  validateInstance: vi.fn(),
  listBlueprintSources: vi.fn(() => Promise.resolve([])),
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

const FIELD_CHECKS = {
  dob: {
    rules: [{ ruleId: 4, ruleType: 'DATE_RANGE', summary: 'On or before 18 years ago', severity: 'ERROR', origin: 'MANUAL' }],
    builtIn: [],
    source: null,
  },
};

// Data-templates contract §8: a document with validation off (validationState OFF, no gates on it).
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
  fieldChecks: FIELD_CHECKS,
  validationEnabled: false,
  validationState: 'OFF',
  errorCount: 0,
  ...overrides,
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
const stepNames = () => screen.getAllByRole('tab').map((t) => t.getAttribute('aria-label'));
const validationSwitch = () => screen.getByRole('switch', { name: 'Validation' });
const ready = async () => within(await screen.findByTestId('page-view')).findByLabelText('Full name');

describe('DocBuilderPage — validation off', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getInstance.mockResolvedValue(doc());
    saveValues.mockImplementation(async (id, values) => doc({ values: { ...doc().values, ...values } }));
  });

  it('has three steps — Choose data, Fill fields, Download — with Download open and the shortcuts enabled', async () => {
    renderPage();
    await ready();
    expect(stepNames()).toEqual(['Choose data', 'Fill fields', 'Download']);
    expect(tab('Fill fields')).toHaveAttribute('aria-selected', 'true');
    expect(tab('Download')).not.toHaveAttribute('aria-disabled');
    expect(validationSwitch()).toHaveAttribute('aria-checked', 'false');
    expect(validationSwitch()).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Download PDF' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Generate' })).toBeEnabled();
    expect(screen.queryByText('Save and validate first')).not.toBeInTheDocument();
    // Submit still needs a generated document.
    expect(screen.queryByRole('button', { name: 'Submit for release' })).not.toBeInTheDocument();
    expectAccessible();
  });

  it('a ?step=save link falls back to Fill fields', async () => {
    renderPage('?step=save');
    await ready();
    expect(tab('Fill fields')).toHaveAttribute('aria-selected', 'true');
    expect(screen.queryByTestId('save-step')).not.toBeInTheDocument();
  });

  it('Save only saves: flushes the autosave and never calls validate', async () => {
    renderPage();
    const name = await ready();
    await userEvent.type(name, 'x');
    const save = screen.getByRole('button', { name: 'Save' });
    await userEvent.click(save);
    await waitFor(() => expect(saveValues).toHaveBeenCalledWith('5', { full_name: 'Janex' }));
    expect(await screen.findByText('Document saved')).toBeInTheDocument();
    expect(validateInstance).not.toHaveBeenCalled();
    expect(save).toHaveTextContent('Save');
    // No live hints or errors under the fields: nothing is checked.
    expect(screen.queryByTestId('field-messages-full_name')).not.toBeInTheDocument();
  });

  it('Generate and Download work without a validation; Submit shows once generated', async () => {
    generateInstance.mockResolvedValue({ pdfReady: true, addedPages: 0, continuedKeys: [], exact: true });
    downloadInstance.mockResolvedValue(undefined);
    renderPage('?step=download');
    const step = await screen.findByTestId('download-step');
    expect(step).toHaveTextContent('Validation is off for this document');
    getInstance.mockResolvedValue(doc({ status: 'GENERATED' }));
    await userEvent.click(within(step).getByRole('button', { name: 'Generate' }));
    expect(generateInstance).toHaveBeenCalledWith('5');
    await userEvent.click(within(step).getByRole('button', { name: 'Download PDF' }));
    expect(downloadInstance).toHaveBeenCalledWith('5', 'Loan — Jane.pdf');
    expect(validateInstance).not.toHaveBeenCalled();

    submitInstance.mockResolvedValue({});
    const submit = await within(step).findByRole('button', { name: 'Submit for release' });
    expect(submit).toBeEnabled();
    expect(tab('Download')).toHaveAttribute('data-done', 'true');
    expectAccessible();
  });

  it('the Checks panel is "Checks (validation off)" and lists the rules as information only', async () => {
    getInstance.mockResolvedValue(
      doc({
        validation: {
          ...NOT_CHECKED,
          checked: true,
          rulesChangedSinceValidation: true,
          errors: [{ regionKey: 'dob', label: 'Date of birth', message: 'Date of birth must be on or before 18 years ago.' }],
        },
      })
    );
    renderPage();
    await ready();
    const panel = screen.getByRole('region', { name: 'Checks (validation off)' });
    expect(within(panel).getByTestId('checks-state')).toHaveTextContent('Validation is off for this document');
    expect(within(panel).getByTestId('field-rules-dob')).toHaveTextContent('Date of birth');
    expect(within(panel).queryByTestId('issues-panel')).not.toBeInTheDocument();
    expect(panel).not.toHaveTextContent(/save again/i);
    expect(panel).not.toHaveTextContent(/press Save/i);
    // The stale server error is not shown as a blocking error on the field.
    expect(screen.getByTestId('field-dob')).not.toHaveAttribute('data-invalid');
    expectAccessible();
  });

  it('turning validation off applies at once', async () => {
    getInstance.mockResolvedValue(doc({ validationEnabled: true, validationState: 'NOT_CHECKED' }));
    setInstanceValidation.mockResolvedValue(doc());
    renderPage();
    await ready();
    expect(stepNames()).toEqual(['Choose data', 'Fix fields', 'Save & validate', 'Download']);
    expect(validationSwitch()).toHaveAttribute('aria-checked', 'true');
    await userEvent.click(validationSwitch());
    expect(setInstanceValidation).toHaveBeenCalledWith('5', false);
    await waitFor(() => expect(stepNames()).toEqual(['Choose data', 'Fill fields', 'Download']));
    expect(screen.getByRole('button', { name: 'Download PDF' })).toBeEnabled();
  });

  it('turning validation on asks first, then brings the steps back and locks Download', async () => {
    setInstanceValidation.mockResolvedValue(
      doc({ validationEnabled: true, validationState: 'NOT_CHECKED', status: 'DRAFT' })
    );
    renderPage('?step=download');
    await screen.findByTestId('download-step');

    await userEvent.click(validationSwitch());
    const dialog = await screen.findByRole('dialog', { name: 'Turn validation on?' });
    expect(dialog).toHaveTextContent('The document will need to pass its checks before Download.');
    expectAccessible();
    // Cancel keeps it off.
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(setInstanceValidation).not.toHaveBeenCalled();
    expect(validationSwitch()).toHaveAttribute('aria-checked', 'false');

    await userEvent.click(validationSwitch());
    await userEvent.click(
      within(await screen.findByRole('dialog', { name: 'Turn validation on?' })).getByRole('button', {
        name: 'Turn validation on',
      })
    );
    expect(setInstanceValidation).toHaveBeenCalledWith('5', true);
    await waitFor(() => expect(stepNames()).toEqual(['Choose data', 'Fix fields', 'Save & validate', 'Download']));
    expect(validationSwitch()).toHaveAttribute('aria-checked', 'true');
    expect(tab('Download')).toHaveAttribute('aria-disabled', 'true');
    // The Download link now lands on Save & validate.
    expect(tab('Save & validate')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('button', { name: 'Download PDF' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Generate' })).toBeDisabled();
    expect(screen.getByTestId('save-step')).toBeInTheDocument();
  });

  it('the switch is disabled once the document is submitted', async () => {
    getInstance.mockResolvedValue(doc({ status: 'SUBMITTED' }));
    renderPage();
    await screen.findByTestId('page-view');
    expect(validationSwitch()).toBeDisabled();
  });
});
