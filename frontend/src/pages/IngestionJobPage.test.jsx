import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import IngestionJobPage, { canVisitStep, stepBlockReason } from './IngestionJobPage';

import { getAllFields } from '../api/templates';
import { downloadFile } from '../api/client';
import {
  chatEdit,
  exportToDatabase,
  getJob,
  remapJob,
  resumeJob,
  revalidateJob,
  setWizardStep,
  updateRows,
  waitForJob,
} from '../api/ingestion';

vi.mock('../api/templates', () => ({
  getAllFields: vi.fn(),
}));

vi.mock('../api/ingestion', async (importOriginal) => ({
  // The real request builder: the tests assert the body it produces.
  databaseExportRequest: (await importOriginal()).databaseExportRequest,
  getJob: vi.fn(),
  waitForJob: vi.fn(),
  needsIngestionWait: vi.fn((job, until = 'MAPPED') => {
    if (!job) return true;
    if (job.status === 'PENDING' || job.status === 'RUNNING') return true;
    const order = { UPLOADED: 0, PARSED: 1, MAPPED: 2, VALIDATED: 3, EXPORTED: 4 };
    return (order[job.currentStep] ?? 0) < (order[until] ?? order.MAPPED);
  }),
  resumeJob: vi.fn().mockResolvedValue({}),
  remapJob: vi.fn().mockResolvedValue({}),
  revalidateJob: vi.fn().mockResolvedValue({}),
  cancelJob: vi.fn(),
  chatEdit: vi.fn().mockResolvedValue({}),
  updateMapping: vi.fn().mockResolvedValue({}),
  updateRows: vi.fn().mockResolvedValue({}),
  setWizardStep: vi.fn().mockResolvedValue({}),
  exportFilePath: vi.fn((id, format) => `/api/ingestion/jobs/${id}/export/file?format=${format}`),
  exportToDatabase: vi.fn(),
  EXPORT_CAP_EXCEEDED: 'EXPORT_CAP_EXCEEDED',
  exportFileWithReason: vi.fn((id, format, reason) => ({
    path: `/api/ingestion/jobs/${id}/export/file`,
    options: { method: 'POST', body: { format, reason } },
  })),
}));

vi.mock('../api/client', () => ({
  downloadFile: vi.fn(),
}));

// The server answers which panes a job has earned (unmapped template fields and
// failing rows are what close them), so fixtures carry that answer like a real
// payload does.
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
  validationErrorCount: null,
  unmappedFields: [],
  // Validation has not run yet.
  reachableSteps: ['MAPPING'],
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
  validationErrorCount: 1,
  // A failing row keeps Export shut.
  reachableSteps: ['MAPPING', 'VALIDATION'],
};

const validatedJob = {
  ...mappedJob,
  currentStep: 'VALIDATED',
  validationResults: [{ row: 1, fieldName: 'FirstName', rawValue: 'Ada', errorMessage: null }],
  validationErrorCount: 0,
  reachableSteps: ['MAPPING', 'VALIDATION', 'EXPORT'],
};

/** A job whose mapping left a template field without a source column. */
const partiallyMappedJob = {
  ...mappedJob,
  unmappedFields: ['Email'],
  reachableSteps: ['MAPPING'],
};

function makeRows(count) {
  return Array.from({ length: count }, (_, i) => ({ FirstName: `Name ${i + 1}` }));
}

const validatedJobPaginated = {
  ...mappedJob,
  currentStep: 'VALIDATED',
  validationErrorCount: 0,
  reachableSteps: ['MAPPING', 'VALIDATION', 'EXPORT'],
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

function renderPage(initialEntry = '/ingestion/7?step=mapping', { state } = {}) {
  const q = initialEntry.indexOf('?');
  const pathname = q === -1 ? initialEntry : initialEntry.slice(0, q);
  const search = q === -1 ? '' : initialEntry.slice(q);
  return render(
    <MemoryRouter initialEntries={[{ pathname, search, state }]}>
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
    waitForJob.mockImplementation((id) => getJob(id));
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
    waitForJob.mockClear();
  });

  it('waits until MAPPED on initial load', async () => {
    waitForJob.mockResolvedValue(mappedJob);
    renderPage('/ingestion/7?step=mapping');
    await screen.findByRole('heading', { name: 'Column mapping' });
    expect(waitForJob).toHaveBeenCalledWith('7', 'MAPPED');
  });

  it('uses prefetched job from navigation state without calling waitForJob', async () => {
    renderPage('/ingestion/7?step=validation', { state: { job: validatedJob } });
    await screen.findByRole('heading', { name: 'Validation' });
    expect(waitForJob).not.toHaveBeenCalled();
  });

  it('editing a cell marks it dirty and shows Save/Discard', async () => {
    getJob.mockResolvedValue(validatedJobWithErrors);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByText('(empty)'));
    const input = within(screen.getByRole('table')).getByDisplayValue('');
    await userEvent.type(input, 'Ada{Enter}');

    expect(screen.getByText('1 unsaved edit')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Discard changes' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continue to export' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Re-validate' })).toBeDisabled();
  });

  it('opens an errored cell empty so the bad value is retyped clean', async () => {
    // A non-empty value that fails validation: the editor must not pre-fill it.
    getJob.mockResolvedValue({
      ...validatedJobWithErrors,
      mappedRows: { columns: ['Email'], rows: [{ Email: 'bad-value' }] },
      validationResults: [
        {
          row: 1,
          fieldName: 'Email',
          rawValue: 'bad-value',
          errorMessage: 'Email format is invalid.',
          ruleType: 'REGEX',
        },
      ],
    });
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByText('bad-value'));
    const input = within(screen.getByRole('table')).getByRole('textbox');
    expect(input).toHaveValue('');
    // The error text is still on screen while the input is empty.
    expect(screen.getAllByText(/Email format is invalid\./).length).toBeGreaterThanOrEqual(1);
  });

  it('opens a valid cell with its existing value for a small tweak', async () => {
    // Row 1 fails (Email), row 2 is valid — editing the valid cell keeps it.
    getJob.mockResolvedValue({
      ...validatedJobWithErrors,
      mappedRows: { columns: ['Email'], rows: [{ Email: 'bad' }, { Email: 'ok@x.com' }] },
      validationResults: [
        {
          row: 1,
          fieldName: 'Email',
          rawValue: 'bad',
          errorMessage: 'Email format is invalid.',
          ruleType: 'REGEX',
        },
      ],
    });
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByText('ok@x.com'));
    expect(within(screen.getByRole('table')).getByRole('textbox')).toHaveValue('ok@x.com');
  });

  it('save sends edited rows, chains revalidate, and re-checkpoints the wizard step', async () => {
    getJob.mockResolvedValue(validatedJobWithErrors);
    revalidateJob.mockResolvedValueOnce(validatedJob);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByText('(empty)'));
    await userEvent.type(within(screen.getByRole('table')).getByDisplayValue(''), 'Ada{Enter}');
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
    await userEvent.type(within(screen.getByRole('table')).getByDisplayValue(''), 'Ada{Enter}');
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
      status: 'FAILED',
      errorMessage: 'LLM timed out',
      parsedTable: null,
      mapping: null,
      mappedRows: null,
    });
    renderPage('/ingestion/7');
    await screen.findByText('Processing not finished');
    expect(screen.queryByText('Parsing file and extracting columns')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Resume processing' }));
    expect(resumeJob).toHaveBeenCalledWith('7', 'MAPPED');
  });

  it('running job shows processing in the mapping callout instead of resume', async () => {
    getJob.mockResolvedValue({
      ...mappedJob,
      currentStep: 'UPLOADED',
      status: 'RUNNING',
      parsedTable: null,
      mapping: null,
      mappedRows: null,
    });
    renderPage('/ingestion/7');
    await screen.findByText(/Parsing file and extracting columns/);
    expect(screen.queryByText('Processing not finished')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Resume processing' })).not.toBeInTheDocument();
    expect(screen.queryByText('Pipeline running — this page updates automatically.')).not.toBeInTheDocument();
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
    const textarea = within(dialog).getByPlaceholderText(/fix this cell/i);
    await userEvent.type(textarea, 'trim whitespace');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send' }));

    expect(chatEdit).toHaveBeenCalledWith('7', {
      instruction: 'trim whitespace',
      row: null,
      column: null,
    });
    expect(
      await screen.findByText(/Applied your changes. Use Re-validate in the toolbar/i)
    ).toBeInTheDocument();
  });

  it('validation chat sends stepper row and column context with scoped edits', async () => {
    chatEdit.mockResolvedValueOnce({ ...mappedJob, currentStep: 'MAPPED', validationResults: null });
    getJob.mockResolvedValue(validatedJobWithErrors);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByRole('button', { name: 'Open AI assistant' }));
    const dialog = screen.getByRole('dialog', { name: 'AI assistant' });
    expect(within(dialog).getByText('Context: Row 1 · FirstName')).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Fix this cell' }));

    expect(chatEdit).toHaveBeenCalledWith('7', {
      instruction: 'Fix this cell',
      row: 1,
      column: 'FirstName',
    });
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

    const table = screen.getByRole('table');
    await userEvent.click(screen.getAllByText('(empty)')[0]);
    await userEvent.type(within(table).getByDisplayValue(''), 'Ada{Enter}');

    // The editor moved to the second errored cell (row 3, still empty).
    await userEvent.type(within(table).getByDisplayValue(''), 'Eve{Enter}');
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
    await userEvent.type(within(dialog).getByPlaceholderText(/fix this cell/i), 'fix it');
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
    await userEvent.type(within(screen.getByRole('table')).getByDisplayValue(''), 'Ada{Enter}');

    await userEvent.click(screen.getByRole('button', { name: 'Open AI assistant' }));
    expect(
      screen.getByText('Save or discard your table edits to use the assistant.')
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled();
  });

  it('shows the current error in the stepper card and Next error opens its cell', async () => {
    getJob.mockResolvedValue(validatedJobWithErrors);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    // The stepper card surfaces the current error up front (one at a time).
    expect(screen.getByText('Error 1 of 1')).toBeInTheDocument();
    expect(screen.getByText('Row 1 · FirstName')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Next error' }));
    expect(screen.getByDisplayValue('')).toBeInTheDocument();
  });

  it('Next error steps to the following error and changes page', async () => {
    getJob.mockResolvedValue({
      ...mappedJob,
      currentStep: 'VALIDATED',
      mappedRows: {
        columns: ['FirstName'],
        rows: makeRows(20),
      },
      validationResults: [
        { row: 1, fieldName: 'FirstName', rawValue: '', errorMessage: 'Value is required.', ruleType: 'REQUIRED' },
        { row: 15, fieldName: 'FirstName', rawValue: '', errorMessage: 'Value is required.', ruleType: 'REQUIRED' },
      ],
      validationErrorCount: 2,
      reachableSteps: ['MAPPING', 'VALIDATION'],
    });
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    expect(screen.getByText('1 / 2')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Next error' }));

    expect(screen.getByDisplayValue('')).toBeInTheDocument();
    expect(screen.getByText('Rows 11–20 of 20')).toBeInTheDocument();
    expect(screen.getByText('2 / 2')).toBeInTheDocument();
    expect(screen.getByText('Error 2 of 2')).toBeInTheDocument();
  });

  it('Previous error wraps to the last error with counter update', async () => {
    getJob.mockResolvedValue({
      ...mappedJob,
      currentStep: 'VALIDATED',
      mappedRows: {
        columns: ['FirstName'],
        rows: [{ FirstName: '' }, { FirstName: '' }],
      },
      validationResults: [
        { row: 1, fieldName: 'FirstName', rawValue: '', errorMessage: 'Value is required.', ruleType: 'REQUIRED' },
        { row: 2, fieldName: 'FirstName', rawValue: '', errorMessage: 'Value is required.', ruleType: 'REQUIRED' },
      ],
      validationErrorCount: 2,
      reachableSteps: ['MAPPING', 'VALIDATION'],
    });
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    expect(screen.getByText('1 / 2')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Previous error' }));
    expect(screen.getByText('Wrapped to last error')).toBeInTheDocument();
    expect(screen.getByText('2 / 2')).toBeInTheDocument();
  });

  it('highlights the active error cell and tints errored rows', async () => {
    getJob.mockResolvedValue(validatedJobWithErrors);
    const { container } = renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByRole('button', { name: 'Next error' }));

    expect(container.querySelector('[data-active-error="true"]')).toBeInTheDocument();
    expect(container.querySelector('tr.bg-danger-bg\\/25')).toBeInTheDocument();
  });

  it('Edit this cell focuses the card value editor', async () => {
    getJob.mockResolvedValue(validatedJobWithErrors);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByRole('button', { name: 'Edit this cell' }));
    expect(screen.getByLabelText('Corrected value')).toHaveFocus();
  });

  it('edits the value directly in the stepper card', async () => {
    getJob.mockResolvedValue(validatedJobWithErrors);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    const cardInput = screen.getByLabelText('Corrected value');
    await userEvent.type(cardInput, 'Ada');

    expect(cardInput).toHaveValue('Ada');
    expect(screen.getByText('1 unsaved edit')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeInTheDocument();
  });

  it('filters the table to rows with errors only', async () => {
    getJob.mockResolvedValue({
      ...mappedJob,
      currentStep: 'VALIDATED',
      mappedRows: {
        columns: ['FirstName'],
        rows: [{ FirstName: '' }, { FirstName: 'Bob' }, { FirstName: 'Eve' }],
      },
      validationResults: [
        { row: 1, fieldName: 'FirstName', rawValue: '', errorMessage: 'Value is required.', ruleType: 'REQUIRED' },
        { row: 2, fieldName: 'FirstName', rawValue: 'Bob', errorMessage: null },
        { row: 3, fieldName: 'FirstName', rawValue: '', errorMessage: 'Value is required.', ruleType: 'REQUIRED' },
      ],
      validationErrorCount: 2,
      reachableSteps: ['MAPPING', 'VALIDATION'],
    });
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    await userEvent.click(screen.getByLabelText('Show rows with errors only'));

    expect(screen.getByText('Rows 1–2 of 2 (3 total)')).toBeInTheDocument();
    expect(screen.queryByText('Bob')).not.toBeInTheDocument();
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

    const tableToolbar = screen.getByText('Rows per page').closest('div');
    await userEvent.click(within(tableToolbar).getByRole('button', { name: 'Next' }));
    expect(screen.getByText('Rows 11–20 of 25')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Next error' }));
    expect(screen.getByText('Wrapped to first error')).toBeInTheDocument();
    expect(screen.getByText('Rows 1–10 of 25')).toBeInTheDocument();
    expect(screen.getByDisplayValue('')).toBeInTheDocument();
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
    waitForJob.mockImplementation((id) => getJob(id));
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

    expect(within(screen.getByRole('table')).getByDisplayValue('')).toBeInTheDocument();
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

describe('unfinished steps block the next one', () => {
  beforeEach(() => {
    getAllFields.mockResolvedValue([
      { templateFieldsId: 1, templateId: 1, fieldName: 'FirstName' },
      { templateFieldsId: 2, templateId: 1, fieldName: 'Email' },
    ]);
    waitForJob.mockImplementation((id) => getJob(id));
    setWizardStep.mockReset();
    setWizardStep.mockResolvedValue({});
    resumeJob.mockReset();
  });

  it('will not continue to validation while a template field is unmapped', async () => {
    getJob.mockResolvedValue(partiallyMappedJob);
    renderPage('/ingestion/7?step=mapping');
    await screen.findByText('Column mapping');

    expect(screen.getByText('1 field still to map')).toBeInTheDocument();
    expect(screen.getByText('Still to map (1):')).toBeInTheDocument();
    const button = screen.getByRole('button', { name: 'Continue to validation' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', expect.stringContaining('Email'));
    expect(resumeJob).not.toHaveBeenCalled();
  });

  it('confirms the mapping is complete when every field has a column', async () => {
    // The panel's checklist reflects the mapping on screen, so the template's
    // fields have to match what this fixture maps.
    getAllFields.mockResolvedValue([
      { templateFieldsId: 1, templateId: 1, fieldName: 'FirstName' },
    ]);
    getJob.mockResolvedValue(mappedJob);
    renderPage('/ingestion/7?step=mapping');
    await screen.findByText('Column mapping');

    expect(screen.getByText('✓ Every template field has a source column.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continue to validation' })).toBeEnabled();
  });

  it('will not continue to export while rows fail validation', async () => {
    getJob.mockResolvedValue(validatedJobWithErrors);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    expect(screen.getByText('1 error to fix')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continue to export' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Export' })).toBeDisabled();
    expect(screen.getByText(/Export stays locked until every row passes/)).toBeInTheDocument();
  });

  it('redirects an export deep link on a failing job back to validation', async () => {
    getJob.mockResolvedValue(validatedJobWithErrors);
    renderPage('/ingestion/7?step=export');

    expect(await screen.findByRole('heading', { name: 'Validation' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Download file' })).not.toBeInTheDocument();
    expect(setWizardStep).not.toHaveBeenCalledWith('7', 'export');
  });

  it('locks the export pane and points at the step that owns the work', async () => {
    // Reachable payload for the pane, then a job whose rows regressed: the
    // pane is open (the user is standing in it) but export itself is shut.
    getJob.mockResolvedValue({
      ...validatedJobWithErrors,
      wizardStep: 'EXPORT',
      reachableSteps: ['MAPPING', 'VALIDATION', 'EXPORT'],
    });
    const { unmount } = renderPage('/ingestion/7?step=export');
    await screen.findByRole('heading', { name: 'Export' });
    unmount();

    getJob.mockResolvedValue({ ...validatedJobWithErrors, wizardStep: 'EXPORT' });
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });
    expect(screen.getByRole('button', { name: 'Continue to export' })).toBeDisabled();
  });

  it('opens export only once nothing is left to fix', async () => {
    getJob.mockResolvedValue(validatedJob);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });

    expect(screen.queryByText(/error to fix/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continue to export' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Export' })).toBeEnabled();
  });
});

describe('wizard step reachability', () => {
  const STEPS = ['mapping', 'validation', 'export'];

  // The rule itself lives in IngestionJobUseCase.reachableSteps (its matrix is
  // pinned in IngestionJobUseCaseTest); this page must not second-guess the
  // answer it is given. A local copy drifting from the server's is what once
  // made the Export pill offer a step the API then refused.
  it('follows the server answer exactly, for every combination', () => {
    [
      ['MAPPING'],
      ['MAPPING', 'VALIDATION'],
      ['MAPPING', 'VALIDATION', 'EXPORT'],
    ].forEach((reachableSteps) => {
      STEPS.forEach((step) => {
        expect(canVisitStep(step, { currentStep: 'VALIDATED', reachableSteps })).toBe(
          reachableSteps.includes(step.toUpperCase())
        );
      });
    });
  });

  it('never opens more than mapping when the payload says nothing', () => {
    expect(canVisitStep('mapping', null)).toBe(true);
    expect(canVisitStep('validation', null)).toBe(false);
    expect(canVisitStep('export', null)).toBe(false);
    // A payload without the field (e.g. a stale cached response) is treated as
    // "mapping only" rather than assumed permissive.
    expect(canVisitStep('export', { currentStep: 'EXPORTED' })).toBe(false);
  });

  it('explains a closed step in the words the user needs', () => {
    expect(stepBlockReason('validation', partiallyMappedJob)).toContain('Email');
    expect(stepBlockReason('export', validatedJobWithErrors)).toContain(
      '1 row still fails validation'
    );
    expect(stepBlockReason('export', mappedJob)).toBe('Run validation on the mapped rows first.');
    // Nothing to say about a step that is open.
    expect(stepBlockReason('export', validatedJob)).toBe('');
  });
});

describe('IngestionJobPage without an explicit ?step', () => {
  beforeEach(() => {
    getAllFields.mockResolvedValue([
      { templateFieldsId: 1, templateId: 1, fieldName: 'FirstName' },
    ]);
    waitForJob.mockImplementation((id) => getJob(id));
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
    await userEvent.type(within(screen.getByRole('table')).getByDisplayValue(''), 'Ada{Enter}');
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

/** ws18 (gate D2): a 403 EXPORT_CAP_EXCEEDED as the API client throws it. */
function capExceeded() {
  const error = new Error('This export has 10000 rows, above your role\'s limit of 5000.');
  error.status = 403;
  error.code = 'EXPORT_CAP_EXCEEDED';
  error.body = { code: 'EXPORT_CAP_EXCEEDED', cap: 5000, rows: 10000, reasonMaxLength: 500 };
  return error;
}

describe('export row caps (ws18, gate D2)', () => {
  beforeEach(() => {
    getAllFields.mockResolvedValue([
      { templateFieldsId: 1, templateId: 1, fieldName: 'FirstName' },
    ]);
    waitForJob.mockImplementation((id) => getJob(id));
    getJob.mockResolvedValue(validatedJob);
    setWizardStep.mockReset();
    setWizardStep.mockResolvedValue({});
    downloadFile.mockReset();
    exportToDatabase.mockReset();
  });

  it('prompts for a reason after a refused download and retries with it in a POST body', async () => {
    downloadFile.mockRejectedValueOnce(capExceeded()).mockResolvedValueOnce(undefined);
    renderPage('/ingestion/7?step=export');
    await screen.findByRole('heading', { name: 'Export' });

    await userEvent.click(screen.getByRole('button', { name: 'Download file' }));

    expect(await screen.findByText('This export needs a reason')).toBeInTheDocument();
    expect(screen.getByText(/above your role.s limit of 5,?000 rows/)).toBeInTheDocument();
    expect(downloadFile).toHaveBeenCalledWith(
      '/api/ingestion/jobs/7/export/file?format=csv',
      'job-7.csv'
    );
    const submit = screen.getByRole('button', { name: 'Export with reason' });
    expect(submit).toBeDisabled();

    await userEvent.type(screen.getByLabelText(/Reason for this export/), 'Regulator return Q3');
    await userEvent.click(submit);

    await vi.waitFor(() => expect(downloadFile).toHaveBeenCalledTimes(2));
    expect(downloadFile).toHaveBeenLastCalledWith(
      '/api/ingestion/jobs/7/export/file',
      'job-7.csv',
      { method: 'POST', body: { format: 'csv', reason: 'Regulator return Q3' } }
    );
    await vi.waitFor(() =>
      expect(screen.queryByText('This export needs a reason')).not.toBeInTheDocument()
    );
  });

  it('caps the database export the same way and resends the form with the reason', async () => {
    exportToDatabase
      .mockRejectedValueOnce(capExceeded())
      .mockResolvedValueOnce({ ...validatedJob, currentStep: 'EXPORTED' });
    renderPage('/ingestion/7?step=export');
    await screen.findByRole('heading', { name: 'Export' });

    await userEvent.click(screen.getByRole('button', { name: 'Export to database…' }));
    // v1.2.0: vendor/host fields; no raw JDBC URL any more.
    expect(screen.queryByLabelText('JDBC URL')).not.toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText('Database vendor'), 'POSTGRESQL');
    // Choosing the vendor fills in its default port.
    expect(screen.getByLabelText('Port')).toHaveValue('5432');
    await userEvent.type(screen.getByLabelText('Host'), 'db.bank.internal');
    await userEvent.type(screen.getByLabelText('Database'), 'core');
    await userEvent.type(screen.getByLabelText('Schema (optional)'), 'staging');
    await userEvent.type(screen.getByLabelText('Username'), 'loader');
    await userEvent.type(screen.getByLabelText('Table name'), 'Rows');
    await userEvent.click(screen.getByRole('button', { name: 'Run database export' }));
    expect(exportToDatabase).toHaveBeenCalledWith('7', {
      vendor: 'POSTGRESQL',
      host: 'db.bank.internal',
      port: 5432,
      database: 'core',
      schema: 'staging',
      sslmode: 'require',
      username: 'loader',
      table: 'Rows',
      tableName: 'Rows',
      createTableIfMissing: true,
    });

    expect(await screen.findByText('This export needs a reason')).toBeInTheDocument();
    expect(screen.getByText(/This database export has/)).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText(/Reason for this export/), 'Migration to core');
    await userEvent.click(screen.getByRole('button', { name: 'Export with reason' }));

    await vi.waitFor(() => expect(exportToDatabase).toHaveBeenCalledTimes(2));
    expect(exportToDatabase).toHaveBeenLastCalledWith(
      '7',
      expect.objectContaining({
        vendor: 'POSTGRESQL',
        host: 'db.bank.internal',
        port: 5432,
        table: 'Rows',
        reason: 'Migration to core',
      })
    );
  });

  it('cancel closes the prompt without exporting again', async () => {
    downloadFile.mockRejectedValueOnce(capExceeded());
    renderPage('/ingestion/7?step=export');
    await screen.findByRole('heading', { name: 'Export' });

    await userEvent.click(screen.getByRole('button', { name: 'Download file' }));
    await screen.findByText('This export needs a reason');
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByText('This export needs a reason')).not.toBeInTheDocument();
    expect(downloadFile).toHaveBeenCalledTimes(1);
  });
});

describe('IngestionJobPage parse provenance (ws23, AC-EXT-02b/07/13/16)', () => {
  beforeEach(() => {
    getAllFields.mockResolvedValue([{ templateFieldsId: 1, templateId: 1, fieldName: 'FirstName' }]);
    waitForJob.mockImplementation((id) => getJob(id));
  });

  it('shows "Extracted by code" with the AI reason, the strategy and the confidence', async () => {
    getJob.mockResolvedValue({
      ...mappedJob,
      parsedBy: 'CODE',
      parseStrategy: 'KEY_VALUE',
      parseConfidence: 0.86,
      parseFallbackReason: 'QUOTA',
      parseMinConfidence: 0.75,
      parseLowConfidence: false,
    });
    renderPage();

    const badge = await screen.findByTestId('parse-provenance');
    expect(badge).toHaveTextContent('Extracted by code — AI unavailable (quota)');
    expect(screen.getByText('Read as label: value blocks')).toBeInTheDocument();
    expect(screen.getByTestId('parse-confidence')).toHaveTextContent('Confidence 86%');
    // The FallbackReason description, for hover and screen readers.
    expect(screen.getByText('Code extraction stood in for the AI: AI quota reached.')).toBeInTheDocument();
    expect(screen.queryByTestId('low-confidence')).not.toBeInTheDocument();
  });

  it('flags a code-only result below the threshold with a low-confidence callout', async () => {
    getJob.mockResolvedValue({
      ...mappedJob,
      parsedBy: 'CODE',
      parseStrategy: 'FIXED_WIDTH',
      parseConfidence: 0.52,
      parseFallbackReason: 'MODE_CODE_ONLY',
      parseMinConfidence: 0.8,
      parseLowConfidence: true,
    });
    renderPage();

    expect(await screen.findByTestId('parse-provenance')).toHaveTextContent(
      'Extracted by code — code-only extraction'
    );
    const callout = screen.getByTestId('low-confidence');
    expect(callout).toHaveAccessibleName('Low-confidence extraction');
    expect(callout).toHaveTextContent("Code extraction scored 52%, below this bank's 80% threshold.");
    expect(callout).toHaveTextContent('uses code-only extraction');
  });

  it('judges older views without a verdict against the shipped 75% threshold', async () => {
    getJob.mockResolvedValue({
      ...mappedJob,
      parsedBy: 'CODE',
      parseStrategy: 'DELIMITED',
      parseConfidence: 0.6,
      parseFallbackReason: 'MODE_CODE_ONLY',
    });
    renderPage();

    expect(await screen.findByTestId('low-confidence')).toHaveTextContent("below this bank's 75% threshold");
  });

  it('marks a partly-AI extraction and shows nothing extra for a plain AI one', async () => {
    getJob.mockResolvedValue({
      ...mappedJob,
      parsedBy: 'AI_PARTIAL',
      parseStrategy: 'LLM_BATCHED',
      parseConfidence: 0.9,
      parseFallbackReason: 'TIMEOUT',
      parseLowConfidence: false,
    });
    const { unmount } = renderPage();
    expect(await screen.findByTestId('parse-provenance')).toHaveTextContent(
      'Extracted by AI and code — AI unavailable for part (timeout)'
    );
    unmount();

    getJob.mockResolvedValue({ ...mappedJob, parsedBy: 'AI', parseStrategy: 'LLM_SINGLE_PASS' });
    renderPage();
    expect(await screen.findByTestId('parse-provenance')).toHaveTextContent('Extracted by AI');
    expect(screen.queryByTestId('parse-confidence')).not.toBeInTheDocument();
    expect(screen.queryByTestId('low-confidence')).not.toBeInTheDocument();
  });

  it('shows no provenance before the job is parsed', async () => {
    getJob.mockResolvedValue(mappedJob);
    renderPage();
    expect(await screen.findByRole('heading', { name: 'Job #7' })).toBeInTheDocument();
    expect(screen.queryByTestId('parse-provenance')).not.toBeInTheDocument();
  });
});
