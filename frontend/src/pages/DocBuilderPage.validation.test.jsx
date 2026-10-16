import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import DocBuilderPage from './DocBuilderPage';
import { stubImageFetch } from '../test/pdfTestUtils';
import { expectAccessible } from '../test/a11y';

import { downloadInstance, generateInstance, getInstance, saveValues, submitInstance, validateInstance } from '../api/doccreation';
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
      props: { placeholder: 'e.g. Jane Doe', helpText: 'As on the ID card', maxLength: 40 },
    }),
    region('marital', 'Marital status', 'CHOICE', { fy: 0.25, sortOrder: 1 }),
    region('photo', 'Photo', 'IMAGE', { fx: 0.6, fy: 0.5, fw: 0.2, fh: 0.2, sortOrder: 2, required: true }),
    region('notes', 'Notes', 'MULTILINE', { page: 1, sortOrder: 3 }),
  ],
  // The CHOICE options come in the document's props map.
  props: { marital: { options: ['Single', 'Married'], placeholder: 'Choose…' } },
  values: { full_name: 'Jane', marital: 'Single' },
  images: [],
  overflow: [],
  sourceRecords: [],
  canEditLayout: false,
  blueprintEdits: [],
  validation: NOT_CHECKED,
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
      errors: [
        { regionKey: 'notes', label: 'Notes', ruleId: 4, code: 'LENGTH', message: 'Notes must be at least 10 characters.' },
        { regionKey: 'photo', label: 'Photo', ruleId: 5, code: 'REQUIRED', message: 'Photo is required.' },
      ],
      warnings: [{ regionKey: 'marital', label: 'Marital status', ruleId: 6, code: 'ALLOWED_VALUES', message: 'Marital status looks unusual.' }],
    },
  });

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

const pageView = () => within(screen.getByTestId('page-view'));
const ready = async () => within(await screen.findByTestId('page-view')).findByLabelText('Full name');
const panel = () => within(screen.getByRole('region', { name: 'Fields' }));
const button = (name) => screen.getByRole('button', { name });

describe('DocBuilderPage — Save and validate', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getInstance.mockResolvedValue(doc());
    getRules.mockResolvedValue({ revision: 2, rules: [], fields: [], ruleTypes: [] });
    // As the server: a real change makes the document a draft.
    saveValues.mockImplementation(async (id, values) =>
      doc({ values: { ...doc().values, ...values }, status: 'DRAFT', validation: NOT_CHECKED })
    );
  });

  it('keeps a draft from generating, downloading and submitting, and says why', async () => {
    renderPage();
    await ready();

    expect(screen.getByText('DRAFT')).toBeInTheDocument();
    for (const name of ['Generate', 'Download PDF']) {
      expect(button(name)).toBeDisabled();
      expect(button(name)).toHaveAccessibleDescription('Save and validate first');
      expect(button(name)).toHaveAttribute('title', 'Save and validate first');
    }
    expect(screen.getByText('Save and validate first')).toBeVisible();
    expect(button('Save')).toBeEnabled();
    expectAccessible();
  });

  it('saves the pending values, validates, and unlocks Generate and Download once validated', async () => {
    validateInstance.mockResolvedValue(validated({ values: { ...doc().values, full_name: 'Jane Doe' } }));
    downloadInstance.mockResolvedValue(undefined);
    renderPage();
    const name = await ready();

    await userEvent.type(name, ' Doe');
    await userEvent.click(button('Save'));
    expect(saveValues).toHaveBeenCalledWith('5', { full_name: 'Jane Doe' });
    expect(validateInstance).toHaveBeenCalledWith('5');
    expect(saveValues.mock.invocationCallOrder[0]).toBeLessThan(validateInstance.mock.invocationCallOrder[0]);
    expect(await screen.findByText('Saved and validated')).toBeInTheDocument();
    expect(screen.getByText('VALIDATED')).toBeInTheDocument();
    expect(button('Generate')).toBeEnabled();
    expect(screen.queryByText('Save and validate first')).not.toBeInTheDocument();
    await userEvent.click(button('Download PDF'));
    expect(downloadInstance).toHaveBeenCalledWith('5', 'Loan — Jane.pdf');
  });

  it('stops when the pending values could not be saved', async () => {
    saveValues.mockRejectedValue(new Error('Network down'));
    renderPage();
    await userEvent.type(await ready(), 'x');
    await userEvent.click(button('Save'));

    expect(await screen.findByRole('alert')).toHaveTextContent('could not be saved, so the document was not validated');
    expect(validateInstance).not.toHaveBeenCalled();
  });

  it('shows the errors on the fields and in the Issues panel, with Go to field and Next issue', async () => {
    validateInstance.mockResolvedValue(failed());
    renderPage();
    await ready();

    await userEvent.click(button('Save'));
    expect(await screen.findByText('Saved as a draft — 2 issues to fix before generating')).toBeInTheDocument();
    const issues = screen.getByTestId('issues-panel');
    expect(within(issues).getByRole('heading', { name: 'Issues (2 errors, 1 warning)' })).toBeInTheDocument();
    expect(within(issues).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'ErrorNotes must be at least 10 characters.Go to field',
      'ErrorPhoto is required.Go to field',
      'WarningMarital status looks unusual.Go to field',
    ]);

    // Inline: the photo box is outlined on the page, the dropdown carries its warning (not invalid).
    expect(screen.getByTestId('field-photo')).toHaveAttribute('data-invalid', 'true');
    expect(screen.getByTestId('invalid-photo')).toBeInTheDocument();
    const marital = panel().getByRole('combobox', { name: /^Marital status/ });
    expect(marital).not.toHaveAttribute('aria-invalid');
    expect(marital).toHaveAccessibleDescription('Warning: Marital status looks unusual.');

    // Go to field: the page-2 field shows and its input gets the focus.
    await userEvent.click(within(issues).getByRole('button', { name: 'Go to field: Notes' }));
    expect(screen.getByText('Page 2 of 2')).toBeInTheDocument();
    const notes = panel().getByRole('textbox', { name: /^Notes/ });
    await waitFor(() => expect(notes).toHaveFocus());
    expect(notes).toHaveAttribute('aria-invalid', 'true');
    expect(notes).toHaveAccessibleDescription('Notes must be at least 10 characters.');
    expect(pageView().getByLabelText('Notes')).toHaveAttribute('aria-invalid', 'true');
    expectAccessible();

    // Next issue walks the list from the top.
    await userEvent.click(within(issues).getByRole('button', { name: 'Next issue' }));
    await waitFor(() => expect(notes).toHaveFocus());
    await userEvent.click(within(issues).getByRole('button', { name: 'Next issue' }));
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();
    await waitFor(() => expect(document.getElementById('field-panel-photo')).toHaveFocus());
  });

  it('turns a validated document back into a draft as soon as a value changes', async () => {
    getInstance.mockResolvedValue(validated());
    renderPage();
    const name = await ready();
    expect(screen.getByText('VALIDATED')).toBeInTheDocument();
    expect(button('Generate')).toBeEnabled();

    await userEvent.type(name, '!');
    expect(screen.getByText('DRAFT')).toBeInTheDocument();
    expect(button('Generate')).toBeDisabled();
    expect(button('Download PDF')).toBeDisabled();
    await waitFor(() => expect(saveValues).toHaveBeenCalled(), { timeout: 2000 });
    expect(screen.getByText('DRAFT')).toBeInTheDocument();
  });

  it('disables Submit for release after an edit of a generated document', async () => {
    getInstance.mockResolvedValue(validated({ status: 'GENERATED' }));
    submitInstance.mockResolvedValue({});
    renderPage();
    const name = await ready();
    expect(button('Submit for release')).toBeEnabled();
    await userEvent.type(name, '!');
    expect(button('Submit for release')).toBeDisabled();
    expect(button('Submit for release')).toHaveAccessibleDescription('Save and validate first');
  });

  it('says "Save and validate first" when the server refuses a generate (409 NOT_VALIDATED)', async () => {
    getInstance.mockResolvedValue(validated());
    generateInstance.mockRejectedValue(Object.assign(new Error('Not validated'), { status: 409, code: 'NOT_VALIDATED' }));
    renderPage();
    await ready();

    getInstance.mockResolvedValue(doc());
    await userEvent.click(button('Generate'));
    expect(await screen.findByText('Save and validate first', { selector: '[role="status"] *, [role="alert"] *, li *, li' })).toBeInTheDocument();
    // The document is read again: the server says it is a draft.
    await waitFor(() => expect(getInstance).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(button('Generate')).toBeDisabled());
  });

  it('notes rules that changed since the document was validated', async () => {
    getInstance.mockResolvedValue(validated({ validation: { ...validated().validation, rulesChangedSinceValidation: true } }));
    renderPage();
    await ready();
    expect(screen.getByRole('note')).toHaveTextContent('Rules changed since this document was validated — save again to re-check.');
  });

  it('uses the field properties: placeholder, help text, max length and a dropdown with an empty choice', async () => {
    renderPage();
    const name = await ready();
    expect(name).toHaveAttribute('placeholder', 'e.g. Jane Doe');
    expect(name).toHaveAttribute('maxLength', '40');
    const panelName = panel().getByRole('textbox', { name: /^Full name/ });
    expect(panelName).toHaveAccessibleDescription('As on the ID card');
    expect(name).toHaveAccessibleDescription('As on the ID card');

    const select = pageView().getByRole('combobox', { name: 'Marital status' });
    expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['Choose…', 'Single', 'Married']);
    expect(select).toHaveValue('Single');
    await userEvent.selectOptions(panel().getByRole('combobox', { name: /^Marital status/ }), 'Married');
    expect(select).toHaveValue('Married');
    await waitFor(() => expect(saveValues).toHaveBeenCalledWith('5', { marital: 'Married' }), { timeout: 2000 });
  });

  it('counts the filled required fields and hints softly while typing; the server result wins after Save', async () => {
    getRules.mockResolvedValue({
      revision: 2,
      rules: [{ id: 9, regionKey: 'notes', ruleType: 'REGEX', config: { Pattern: '^[A-Z]' }, message: 'Start with a capital.', severity: 'ERROR' }],
      fields: [],
      ruleTypes: [],
    });
    renderPage();
    const name = await ready();
    expect(screen.getByTestId('required-progress')).toHaveTextContent('1 of 2 required fields filled');

    await userEvent.clear(name);
    expect(screen.getByTestId('required-progress')).toHaveTextContent('0 of 2 required fields filled');
    const panelName = panel().getByRole('textbox', { name: /^Full name/ });
    expect(panelName).toHaveAccessibleDescription('As on the ID card Full name is required.');
    // A hint, not an error.
    expect(panelName).not.toHaveAttribute('aria-invalid');

    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await userEvent.type(panel().getByRole('textbox', { name: /^Notes/ }), 'lower case');
    await waitFor(() => expect(screen.getByTestId('field-messages-notes')).toHaveTextContent('Start with a capital.'));

    validateInstance.mockResolvedValue(validated({ values: { full_name: 'Jane', notes: 'lower case' } }));
    await userEvent.click(button('Save'));
    expect(await screen.findByText('Saved and validated')).toBeInTheDocument();
    // The server found nothing wrong: its result replaces the hints.
    expect(screen.queryByTestId('field-messages-notes')).not.toBeInTheDocument();
  });
});
