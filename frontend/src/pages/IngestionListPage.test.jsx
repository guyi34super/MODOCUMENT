import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import IngestionListPage from './IngestionListPage';

import { getTemplates } from '../api/cache';
import { getSupportedTypes, listJobs } from '../api/ingestion';

vi.mock('../api/cache', () => ({
  getTemplates: vi.fn(),
}));

vi.mock('../api/ingestion', () => ({
  createJobFromFile: vi.fn(),
  waitForJob: vi.fn(),
  getSupportedTypes: vi.fn(),
  listJobs: vi.fn(),
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
    listJobs.mockReset();
    getSupportedTypes.mockReset();
    getTemplates.mockResolvedValue([{ templateId: 1, name: 'People' }]);
    listJobs.mockResolvedValue([]);
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
    listJobs.mockRejectedValueOnce(new Error('jobs endpoint down'));
    renderPage();

    expect(await screen.findByText('jobs endpoint down')).toBeInTheDocument();

    listJobs.mockResolvedValue([]);
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));

    expect(screen.queryByText('jobs endpoint down')).not.toBeInTheDocument();
  });

  it('describes automatic processing through validation', async () => {
    renderPage();
    expect(
      await screen.findByText(/parse, map, and validate automatically/i)
    ).toBeInTheDocument();
  });
});
