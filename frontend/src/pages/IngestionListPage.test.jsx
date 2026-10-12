import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import IngestionListPage from './IngestionListPage';

import { getTemplates } from '../api/cache';
import { getSupportedTypes, listJobSummaries } from '../api/ingestion';

vi.mock('../api/cache', () => ({
  getTemplates: vi.fn(),
}));

vi.mock('../api/ingestion', () => ({
  createJobFromFile: vi.fn(),
  waitForJob: vi.fn(),
  getSupportedTypes: vi.fn(),
  listJobSummaries: vi.fn(),
}));

function renderPage() {
  return render(
    <MemoryRouter>
      <ToastProvider>
        <IngestionListPage />
      </ToastProvider>
    </MemoryRouter>
  );
}

describe('IngestionListPage', () => {
  beforeEach(() => {
    getTemplates.mockReset();
    listJobSummaries.mockReset();
    getSupportedTypes.mockReset();
    getTemplates.mockResolvedValue([{ templateId: 1, name: 'People' }]);
    listJobSummaries.mockResolvedValue([]);
    getSupportedTypes.mockResolvedValue({
      extensions: ['.csv', '.pdf'],
      accept: '.csv,.pdf',
      description: 'csv, pdf',
    });
  });

  it('offers exactly the formats this build can parse', async () => {
    renderPage();

    // The panel auto-opens for a first-time user (no jobs yet).
    expect(await screen.findByText('Accepts csv, pdf.')).toBeInTheDocument();
    expect(screen.getByLabelText('File')).toHaveAttribute('accept', '.csv,.pdf');
  });

  it('falls back to a static list when the supported-types call fails', async () => {
    getSupportedTypes.mockRejectedValue(new Error('nope'));
    renderPage();

    const input = await screen.findByLabelText('File');
    expect(input.getAttribute('accept')).toContain('.csv');
    expect(input.getAttribute('accept')).not.toContain('.pptx');
  });

  it('clears a stale error banner once a refresh succeeds', async () => {
    listJobSummaries.mockRejectedValueOnce(new Error('jobs endpoint down'));
    renderPage();

    expect(await screen.findByText('jobs endpoint down')).toBeInTheDocument();

    listJobSummaries.mockResolvedValue([]);
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));

    expect(screen.queryByText('jobs endpoint down')).not.toBeInTheDocument();
  });

  it('describes automatic processing through validation', async () => {
    renderPage();
    expect(
      await screen.findByText(/parse and map automatically/i)
    ).toBeInTheDocument();
  });

  it('tags jobs that code read, and leaves AI-read jobs unmarked (ws23)', async () => {
    const base = { templateId: 1, currentStep: 'VALIDATED', status: 'COMPLETED', validationErrorCount: 0 };
    listJobSummaries.mockResolvedValue([
      { ...base, ingestionJobId: 1, fileName: 'ai.pdf', parsedBy: 'AI' },
      {
        ...base,
        ingestionJobId: 2,
        fileName: 'fallback.pdf',
        parsedBy: 'CODE',
        parseConfidence: 0.9,
        parseFallbackReason: 'QUOTA',
        parseLowConfidence: false,
      },
      {
        ...base,
        ingestionJobId: 3,
        fileName: 'weak.txt',
        parsedBy: 'CODE',
        parseConfidence: 0.5,
        parseFallbackReason: 'MODE_CODE_ONLY',
        parseLowConfidence: true,
      },
    ]);
    renderPage();

    const tags = await screen.findAllByTestId('job-parsed-by');
    expect(tags.map((tag) => tag.textContent).sort()).toEqual(['Code', 'Code · low confidence']);
    // The full provenance is available to screen readers and on hover.
    expect(screen.getByText(': Extracted by code — AI unavailable (quota)')).toBeInTheDocument();
  });

  it('pages through job summaries (v1.2.0) and names templates from the summary', async () => {
    const summary = (id) => ({
      ingestionJobId: id,
      fileName: `file-${id}.csv`,
      templateId: 1,
      templateName: 'Payroll',
      status: 'COMPLETED',
      currentStep: 'MAPPED',
      validationErrorCount: 2,
      unmappedFieldCount: 0,
    });
    listJobSummaries.mockImplementation(({ page }) =>
      Promise.resolve({
        items: page === 0 ? [summary(120), summary(119)] : [summary(70)],
        page,
        size: 50,
        total: 51,
      })
    );
    renderPage();

    expect(await screen.findByText('file-120.csv')).toBeInTheDocument();
    expect(listJobSummaries).toHaveBeenCalledWith({ page: 0, size: 50 });
    expect(screen.getAllByText('Payroll').length).toBeGreaterThan(0);
    // 51 jobs: the upload panel stays closed even though this page is short.
    expect(screen.queryByText('Upload a document', { selector: 'h2' })).not.toBeInTheDocument();
    const pager = screen.getByRole('navigation', { name: 'Jobs pages' });
    expect(pager).toHaveTextContent('1–50 of 51 · page 1 of 2');

    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(await screen.findByText('file-70.csv')).toBeInTheDocument();
    expect(listJobSummaries).toHaveBeenLastCalledWith({ page: 1, size: 50 });
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled();
  });
});
