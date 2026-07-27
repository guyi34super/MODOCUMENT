import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import IngestionJobPage from './IngestionJobPage';

import { getAllFields } from '../api/templates';
import { chatEdit, getJob, resumeJob, setWizardStep } from '../api/ingestion';

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

  it('shows mapping step with source columns and template fields panel', async () => {
    renderPage();
    await screen.findByText('Column mapping');
    expect(screen.getByText('Source columns')).toBeInTheDocument();
    expect(screen.getByText('Template fields')).toBeInTheDocument();
    expect(screen.getAllByText('first_name').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('FirstName').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(/Mapped from/)).toBeInTheDocument();
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
      await screen.findByText(/Applied your changes. Click Re-validate/i)
    ).toBeInTheDocument();
  });

  it('validation step shows AI assistant toggle when validated', async () => {
    getJob.mockResolvedValue(validatedJob);
    renderPage('/ingestion/7?step=validation');
    await screen.findByRole('heading', { name: 'Validation' });
    expect(screen.getByRole('button', { name: 'Open AI assistant' })).toBeInTheDocument();
  });

  it('export step disables download until validated', async () => {
    getJob.mockResolvedValue(mappedJob);
    renderPage('/ingestion/7?step=export');
    await screen.findByRole('heading', { name: 'Export' });
    expect(screen.getByRole('button', { name: 'Download file' })).toBeDisabled();
  });

  it('export step enables download when validated', async () => {
    getJob.mockResolvedValue(validatedJob);
    renderPage('/ingestion/7?step=export');
    await screen.findByRole('heading', { name: 'Export' });
    expect(screen.getByRole('button', { name: 'Download file' })).toBeEnabled();
  });
});
