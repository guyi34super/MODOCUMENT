import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import IngestionJobPage, { canVisitStep } from './IngestionJobPage';

import { getAllFields } from '../api/templates';
import {
  chatEdit,
  getJob,
  remapJob,
  resumeJob,
  revalidateJob,
  setWizardStep,
  updateRows,
} from '../api/ingestion';

vi.mock('../api/templates', () => ({
  getAllFields: vi.fn(),
}));

vi.mock('../api/ingestion', () => ({
  getJob: vi.fn(),
  resumeJob: vi.fn().mockResolvedValue({}),
  remapJob: vi.fn().mockResolvedValue({}),
  revalidateJob: vi.fn().mockResolvedValue({}),
  cancelJob: vi.fn(),
  chatEdit: vi.fn().mockResolvedValue({}),
  updateMapping: vi.fn().mockResolvedValue({}),
  updateRows: vi.fn().mockResolvedValue({}),
  setWizardStep: vi.fn().mockResolvedValue({}),
  exportFilePath: vi.fn(),
  exportToDatabase: vi.fn(),
}));

vi.mock('../api/client', () => ({
  downloadFile: vi.fn(),
}));

const mappedJob = {
  ingestionJobId: 7,
  templateId: 1,
  fileName: 'people.csv',
  currentStep: 'MAPPED',
  status: 'COMPLETED',
  parsedTable: {
    columns: ['first_name'],
    rows: [{ first_name: 'Ada' }],
  },
  mapping: { first_name: 'FirstName' },
  mappedRows: {
    columns: ['FirstName'],
    rows: [{ FirstName: 'Ada' }],
  },
  validationResults: null,
};

const validatedJobWithErrors = {
  ...mappedJob,
  currentStep: 'VALIDATED',
  mappedRows: {
    columns: ['FirstName'],
    rows: [{ FirstName: '' }],
  },
  validationResults: [
    {
      row: 1,
      fieldName: 'FirstName',
      rawValue: '',
      errorMessage: 'Value is required.',
      ruleType: 'REQUIRED',
    },
  ],
};

const validatedJob = {
  ...mappedJob,
  currentStep: 'VALIDATED',
  validationResults: [{ row: 1, fieldName: 'FirstName', rawValue: 'Ada', errorMessage: null }],
};

function makeRows(count) {
  return Array.from({ length: count }, (_, i) => ({ FirstName: `Name ${i + 1}` }));
}

const validatedJobPaginated = {
  ...mappedJob,
  currentStep: 'VALIDATED',
  mappedRows: {
    columns: ['FirstName'],
    rows: makeRows(25),
  },
  validationResults: makeRows(25).map((row, i) => ({
    row: i + 1,
    fieldName: 'FirstName',
    rawValue: row.FirstName,
    errorMessage: null,
  })),
};

function renderPage(initialEntry = '/ingestion/7?step=mapping') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <ToastProvider>
        <Routes>
          <Route path="/ingestion/:id" element={<IngestionJobPage />} />
        </Routes>
      </ToastProvider>
    </MemoryRouter>
  );
}

describe('IngestionJobPage wizard', () => {
  beforeEach(() => {
    getAllFields.mockResolvedValue([
      { templateFieldsId: 1, templateId: 1, fieldName: 'FirstName' },
    ]);
    getJob.mockResolvedValue(mappedJob);
    resumeJob.mockClear();
    chatEdit.mockClear();
    setWizardStep.mockReset();
    setWizardStep.mockResolvedValue({});
    updateRows.mockReset();
    updateRows.mockResolvedValue({});
    revalidateJob.mockReset();
    revalidateJob.mockResolvedValue({});
    remapJob.mockReset();
    remapJob.mockResolvedValue({});
  });

  it('editing a cell marks it dirty and shows Save/Discard', async () => {
    getJob.mockResolvedValue(validatedJobWithErrors);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByText('(empty)'));
    const input = screen.getByDisplayValue('');
    await userEvent.type(input, 'Ada{Enter}');

    expect(screen.getByText('1 unsaved edit')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Discard changes' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continue to export' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Re-validate' })).toBeDisabled();
  });

  it('save sends edited rows, chains revalidate, and re-checkpoints the wizard step', async () => {
    getJob.mockResolvedValue(validatedJobWithErrors);
    revalidateJob.mockResolvedValueOnce(validatedJob);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByText('(empty)'));
    await userEvent.type(screen.getByDisplayValue(''), 'Ada{Enter}');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(updateRows).toHaveBeenCalledWith('7', [{ FirstName: 'Ada' }]);
    expect(revalidateJob).toHaveBeenCalledWith('7');
    expect(await screen.findByText('All rows valid')).toBeInTheDocument();
    expect(setWizardStep).toHaveBeenCalledWith('7', 'validation');
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument();
  });

  it('discard restores the original values and clears the dirty state', async () => {
    getJob.mockResolvedValue(validatedJobWithErrors);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByText('(empty)'));
    await userEvent.type(screen.getByDisplayValue(''), 'Ada{Enter}');
    expect(screen.getByText('Ada')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Discard changes' }));

    expect(screen.queryByText('Ada')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Re-validate' })).toBeEnabled();
    expect(updateRows).not.toHaveBeenCalled();
  });

  it('reopening without ?step resumes at the persisted wizard step', async () => {
    getJob.mockResolvedValue({ ...validatedJob, wizardStep: 'EXPORT' });
    renderPage('/ingestion/7');
    await screen.findByRole('heading', { name: 'Export' });
    expect(screen.getByRole('button', { name: 'Download file' })).toBeEnabled();
  });

  it('stale persisted step falls back to the step inferred from currentStep', async () => {
    // wizardStep VALIDATION is stale for a job knocked back to MAPPED.
    getJob.mockResolvedValue({ ...mappedJob, wizardStep: 'VALIDATION' });
    renderPage('/ingestion/7');
    await screen.findByText('Column mapping');
    expect(screen.queryByRole('heading', { name: 'Validation' })).not.toBeInTheDocument();
  });

  it('explicit ?step deep link beats the persisted wizard step', async () => {
    getJob.mockResolvedValue({ ...validatedJob, wizardStep: 'EXPORT' });
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });
  });

  it('navigating steps checkpoints the position via setWizardStep', async () => {
    getJob.mockResolvedValue(validatedJob);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });
    await userEvent.click(screen.getByRole('button', { name: 'Continue to export' }));

    expect(setWizardStep).toHaveBeenCalledWith('7', 'export');
    expect(await screen.findByRole('heading', { name: 'Export' })).toBeInTheDocument();
  });

  it('unprocessed job shows a resume CTA that resumes parse/map', async () => {
    getJob.mockResolvedValue({
      ...mappedJob,
      currentStep: 'UPLOADED',
      status: 'PENDING',
      parsedTable: null,
      mapping: null,
      mappedRows: null,
    });
    renderPage('/ingestion/7');
    await screen.findByText('Processing not finished');

    await userEvent.click(screen.getByRole('button', { name: 'Resume processing' }));
    expect(resumeJob).toHaveBeenCalledWith('7', 'MAPPED');
  });

  it('shows mapping step with the mapped-count header and per-column selects', async () => {
    renderPage();
    await screen.findByText('Column mapping');
    expect(screen.getByText('Source columns')).toBeInTheDocument();
    expect(screen.getByText('1 of 1 mapped')).toBeInTheDocument();
    expect(screen.getAllByText('first_name').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByRole('combobox', { name: 'Map first_name' })).toHaveValue('FirstName');
    expect(screen.queryByText(/Unmapped template fields/)).not.toBeInTheDocument();
    expect(screen.queryByText('Source (parsed)')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continue to validation' })).toBeInTheDocument();
  });

  it('Continue to validation calls resumeJob with VALIDATED', async () => {
    resumeJob.mockResolvedValueOnce(validatedJob);
    renderPage();
    await screen.findByText('Column mapping');
    await userEvent.click(screen.getByRole('button', { name: 'Continue to validation' }));

    expect(resumeJob).toHaveBeenCalledWith('7', 'VALIDATED');
    expect(await screen.findByRole('heading', { name: 'Validation' })).toBeInTheDocument();
  });

  it('validation step shows inline fix hints instead of error details panel', async () => {
    getJob.mockResolvedValue(validatedJobWithErrors);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });
    expect(screen.getByText('Mapped data')).toBeInTheDocument();
    expect(screen.queryByText('Error details')).not.toBeInTheDocument();
    expect(screen.getByText('Fix: Value is required.')).toBeInTheDocument();
    expect(screen.getByText('Rule: Required')).toBeInTheDocument();
    expect(screen.getByText('(empty)')).toBeInTheDocument();
  });

  it('validation table supports pagination with selectable page size up to 20', async () => {
    getJob.mockResolvedValue(validatedJobPaginated);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    const rowsPerPage = screen.getByLabelText(/Rows per page/i, { selector: 'select' });
    await userEvent.selectOptions(rowsPerPage, '20');
    expect(screen.getByText('Rows 1–20 of 25')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText('Rows 21–25 of 25')).toBeInTheDocument();
    expect(screen.getByText('Page 2 of 2')).toBeInTheDocument();
    expect(screen.getByText('21')).toBeInTheDocument();
  });

  it('validation chat drawer sends message via chatEdit', async () => {
    chatEdit.mockResolvedValueOnce({ ...mappedJob, currentStep: 'MAPPED', validationResults: null });
    getJob.mockResolvedValue(validatedJob);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByRole('button', { name: 'Open AI assistant' }));
    const dialog = screen.getByRole('dialog', { name: 'AI assistant' });
    const textarea = within(dialog).getByPlaceholderText(/fill empty FirstName/i);
    await userEvent.type(textarea, 'trim whitespace');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send' }));

    expect(chatEdit).toHaveBeenCalledWith('7', 'trim whitespace');
    expect(
      await screen.findByText(/Applied your changes. Use Re-validate in the toolbar/i)
    ).toBeInTheDocument();
  });

  it('validation step shows AI assistant toggle when validated', async () => {
    getJob.mockResolvedValue(validatedJob);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });
    expect(screen.getByRole('button', { name: 'Open AI assistant' })).toBeInTheDocument();
  });

  it('shows only the active step actions in the toolbar', async () => {
    getJob.mockResolvedValue(mappedJob);
    renderPage('/ingestion/7?step=mapping');
    await screen.findByText('Column mapping');
    expect(screen.getByRole('button', { name: 'Re-map automatically' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continue to validation' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Re-validate' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Continue to export' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel job' })).toBeInTheDocument();
  });

  it('shows only the cancel link on the export step toolbar', async () => {
    getJob.mockResolvedValue(validatedJob);
    renderPage('/ingestion/7?step=export');
    await screen.findByRole('heading', { name: 'Export' });
    expect(screen.queryByRole('button', { name: 'Re-map automatically' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Re-validate' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Continue to export' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel job' })).toBeInTheDocument();
  });

  it('offers Go to validation when returning to mapping from a later step', async () => {
    getJob.mockResolvedValue(validatedJob);
    renderPage('/ingestion/7?step=mapping');
    await screen.findByText('Column mapping');

    const goButton = screen.getByRole('button', { name: 'Go to validation' });
    expect(goButton).toBeEnabled();
    await userEvent.click(goButton);

    expect(await screen.findByRole('heading', { name: 'Validation' })).toBeInTheDocument();
    expect(resumeJob).not.toHaveBeenCalled();
  });

  it('asks for confirmation before re-mapping over unsaved mapping edits', async () => {
    getJob.mockResolvedValue(mappedJob);
    const confirmSpy = vi.spyOn(window, 'confirm');
    renderPage('/ingestion/7?step=mapping');
    await screen.findByText('Column mapping');

    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Map first_name' }), '');
    expect(screen.getByText('Unsaved mapping')).toBeInTheDocument();

    confirmSpy.mockReturnValueOnce(false);
    await userEvent.click(screen.getByRole('button', { name: 'Re-map automatically' }));
    expect(remapJob).not.toHaveBeenCalled();

    confirmSpy.mockReturnValueOnce(true);
    await userEvent.click(screen.getByRole('button', { name: 'Re-map automatically' }));
    expect(remapJob).toHaveBeenCalledWith('7');
  });

  it('Enter commits and advances to the next errored cell', async () => {
    getJob.mockResolvedValue({
      ...mappedJob,
      currentStep: 'VALIDATED',
      mappedRows: {
        columns: ['FirstName'],
        rows: [{ FirstName: '' }, { FirstName: 'Bob' }, { FirstName: '' }],
      },
      validationResults: [
        { row: 1, fieldName: 'FirstName', rawValue: '', errorMessage: 'Value is required.', ruleType: 'REQUIRED' },
        { row: 3, fieldName: 'FirstName', rawValue: '', errorMessage: 'Value is required.', ruleType: 'REQUIRED' },
      ],
    });
    revalidateJob.mockResolvedValueOnce(validatedJob);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getAllByText('(empty)')[0]);
    await userEvent.type(screen.getByDisplayValue(''), 'Ada{Enter}');

    // The editor moved to the second errored cell (row 3, still empty).
    await userEvent.type(screen.getByDisplayValue(''), 'Eve{Enter}');
    // Wrapped back to the first errored cell (now holding the draft) — close it.
    await userEvent.keyboard('{Escape}');

    expect(screen.getByText('2 unsaved edits')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(updateRows).toHaveBeenCalledWith('7', [
      { FirstName: 'Ada' },
      { FirstName: 'Bob' },
      { FirstName: 'Eve' },
    ]);
  });

  it('Escape cancels the editor without dirtying anything', async () => {
    getJob.mockResolvedValue(validatedJobWithErrors);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByText('(empty)'));
    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Re-validate' })).toBeEnabled();
  });

  it('offers a Run validation button after a chat edit clears validation', async () => {
    getJob.mockResolvedValue(validatedJob);
    chatEdit.mockResolvedValueOnce({ ...mappedJob, currentStep: 'MAPPED', validationResults: null });
    resumeJob.mockResolvedValueOnce(validatedJob);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByRole('button', { name: 'Open AI assistant' }));
    const dialog = screen.getByRole('dialog', { name: 'AI assistant' });
    await userEvent.type(within(dialog).getByPlaceholderText(/fill empty FirstName/i), 'fix it');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send' }));

    // The user stays on validation; the not-run state now offers a real CTA.
    await userEvent.click(await screen.findByRole('button', { name: 'Run validation' }));
    expect(resumeJob).toHaveBeenCalledWith('7', 'VALIDATED');
  });

  it('shows a friendly export summary instead of raw JSON', async () => {
    getJob.mockResolvedValue({
      ...validatedJob,
      currentStep: 'EXPORTED',
      exportInfo: { type: 'file', format: 'CSV', fileName: 'job-7.csv', rowCount: 25 },
    });
    renderPage('/ingestion/7?step=export');
    const summaryTitle = await screen.findByText('Export complete');
    const summary = within(summaryTitle.parentElement);

    expect(summary.getByText('job-7.csv')).toBeInTheDocument();
    expect(summary.getByText('CSV')).toBeInTheDocument();
    expect(summary.getByText('25')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Process another file/ })).toHaveAttribute(
      'href',
      '/ingestion'
    );
    expect(screen.queryByText(/\{"type"/)).not.toBeInTheDocument();
  });

  it('explains why the chat assistant is blocked while edits are unsaved', async () => {
    getJob.mockResolvedValue(validatedJobWithErrors);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByText('(empty)'));
    await userEvent.type(screen.getByDisplayValue(''), 'Ada{Enter}');

    await userEvent.click(screen.getByRole('button', { name: 'Open AI assistant' }));
    expect(
      screen.getByText('Save or discard your table edits to use the assistant.')
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled();
  });

  it('announces when Next error wraps back to the first error', async () => {
    getJob.mockResolvedValue({
      ...mappedJob,
      currentStep: 'VALIDATED',
      mappedRows: { columns: ['FirstName'], rows: makeRows(25) },
      validationResults: [
        { row: 1, fieldName: 'FirstName', rawValue: '', errorMessage: 'Value is required.', ruleType: 'REQUIRED' },
      ],
    });
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText('Rows 11–20 of 25')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Next error' }));
    expect(screen.getByText('Wrapped to first error')).toBeInTheDocument();
    expect(screen.getByText('Rows 1–10 of 25')).toBeInTheDocument();
  });

  it('export deep link on an unvalidated job lands on mapping instead', async () => {
    // The backend refuses to export (or checkpoint) a job that has not been
    // validated, so the wizard must not offer the step either.
    getJob.mockResolvedValue(mappedJob);
    renderPage('/ingestion/7?step=export');
    await screen.findByText('Column mapping');
    expect(screen.queryByRole('heading', { name: 'Export' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Export' })).toBeDisabled();
  });

  it('export step enables download when validated', async () => {
    getJob.mockResolvedValue(validatedJob);
    renderPage('/ingestion/7?step=export');
    await screen.findByRole('heading', { name: 'Export' });
    expect(screen.getByRole('button', { name: 'Download file' })).toBeEnabled();
  });
});

describe('IngestionJobPage accessibility and failure states', () => {
  beforeEach(() => {
    getAllFields.mockResolvedValue([
      { templateFieldsId: 1, templateId: 1, fieldName: 'FirstName' },
    ]);
    setWizardStep.mockReset();
    setWizardStep.mockResolvedValue({});
  });

  it('names the cell and its problem, and opens the editor on Space', async () => {
    getJob.mockResolvedValue(validatedJobWithErrors);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    const cell = screen.getByRole('button', {
      name: 'FirstName, row 1: empty. Value is required. Edit',
    });
    cell.focus();
    await userEvent.keyboard(' ');

    expect(screen.getByDisplayValue('')).toBeInTheDocument();
  });

  it('returns focus to the cell after Escape instead of dropping it on the body', async () => {
    getJob.mockResolvedValue(validatedJobWithErrors);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByText('(empty)'));
    await userEvent.keyboard('{Escape}');

    expect(document.activeElement).toHaveAttribute('role', 'button');
    expect(document.activeElement.getAttribute('aria-label')).toContain('FirstName, row 1');
  });

  it('offers a retry when the job cannot be loaded', async () => {
    getJob.mockRejectedValueOnce(new Error('Ingestion job 7 not found.'));
    renderPage('/ingestion/7?step=mapping');

    expect(await screen.findByText('This job could not be loaded')).toBeInTheDocument();
    // The page keeps its shell so the user can get back out.
    expect(screen.getByRole('link', { name: 'Back to uploads' })).toBeInTheDocument();

    getJob.mockResolvedValue(mappedJob);
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('Column mapping')).toBeInTheDocument();
  });

  it('says there is nothing to validate when no column was mapped', async () => {
    getJob.mockResolvedValue({
      ...validatedJob,
      mapping: {},
      mappedRows: { columns: [], rows: [] },
      validationResults: [],
    });
    renderPage('/ingestion/7?step=validation');

    expect(await screen.findByText('No mapped data to check')).toBeInTheDocument();
    expect(screen.queryByText('All rows valid')).not.toBeInTheDocument();
  });
});

describe('wizard step reachability', () => {
  // Must mirror IngestionJobUseCase.setWizardStep: MAPPING always, the other
  // two only once the job has validated. A pill the backend then refuses to
  // checkpoint is how the resume point silently stopped being saved.
  const STEPS = ['mapping', 'validation', 'export'];
  const BACKEND_ALLOWS = {
    UPLOADED: ['mapping'],
    PARSED: ['mapping'],
    MAPPED: ['mapping'],
    VALIDATED: ['mapping', 'validation', 'export'],
    EXPORTED: ['mapping', 'validation', 'export'],
  };

  it('matches the backend rule for every step and stage', () => {
    Object.entries(BACKEND_ALLOWS).forEach(([currentStep, allowed]) => {
      STEPS.forEach((step) => {
        expect(canVisitStep(step, { currentStep, mappedRows: { columns: [], rows: [] } })).toBe(
          allowed.includes(step)
        );
      });
    });
  });

  it('offers only mapping before a job is loaded', () => {
    expect(canVisitStep('mapping', null)).toBe(true);
    expect(canVisitStep('validation', null)).toBe(false);
    expect(canVisitStep('export', null)).toBe(false);
  });
});

describe('IngestionJobPage without an explicit ?step', () => {
  beforeEach(() => {
    getAllFields.mockResolvedValue([
      { templateFieldsId: 1, templateId: 1, fieldName: 'FirstName' },
    ]);
    setWizardStep.mockReset();
    setWizardStep.mockResolvedValue({});
    updateRows.mockReset();
    updateRows.mockResolvedValue({});
    revalidateJob.mockReset();
  });

  it('keeps the validation pane after saving edits, even if the job reports MAPPED', async () => {
    getJob.mockResolvedValue(validatedJobWithErrors);
    // The server clamps the job back to MAPPED while saving rows; the pane the
    // user is working in must not follow it.
    revalidateJob.mockResolvedValue({ ...validatedJobWithErrors, currentStep: 'MAPPED' });
    renderPage('/ingestion/7');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByText('(empty)'));
    await userEvent.type(screen.getByDisplayValue(''), 'Ada{Enter}');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(updateRows).toHaveBeenCalledWith('7', [{ FirstName: 'Ada' }]);
    expect(screen.getByRole('heading', { name: 'Validation' })).toBeInTheDocument();
    expect(screen.queryByText('Column mapping')).not.toBeInTheDocument();
  });

  it('resolves the inferred step into the URL so later updates cannot move it', async () => {
    getJob.mockResolvedValue(validatedJob);
    renderPage('/ingestion/7');
    await screen.findByRole('heading', { name: 'Validation' });
    expect(setWizardStep).not.toHaveBeenCalled();
  });
});
