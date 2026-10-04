import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';
import AdminRetentionPage from './AdminRetentionPage';

import { dsrErase, dsrExport, dsrSearch, retentionStatus, runRetention } from '../api/retention';

vi.mock('../api/retention', () => ({
  retentionStatus: vi.fn(),
  runRetention: vi.fn(),
  dsrSearch: vi.fn(),
  dsrExport: vi.fn(),
  dsrErase: vi.fn(),
}));

const ME = { userId: 1, email: 'admin@bank.example', displayName: 'Admin', role: 'ADMIN' };

const STATUS = {
  enabled: true,
  retentionDays: 30,
  lastRun: {
    occurredAt: '2026-10-01T02:41:00Z',
    counts: { uploadedFiles: 3, ingestionJobs: 2 },
  },
};

const SEARCH_RESULT = {
  totalMatches: 4,
  stores: [
    {
      store: 'ingestionJobs',
      matches: 2,
      hits: [{ ref: '7', label: 'accounts.csv', rowCount: 2 }],
    },
    { store: 'importDetails', matches: 1, hits: [{ ref: '9', label: 'import 3', rowCount: 1 }] },
    { store: 'extractionRawRecords', matches: 0, hits: [] },
    { store: 'extractionProcessedRecords', matches: 0, hits: [] },
    { store: 'uploadedFiles', matches: 1, hits: [{ ref: 'a.csv', label: 'name match', rowCount: 1 }] },
    { store: 'docContentBlocks', matches: 0, hits: [] },
  ],
};

function renderPage() {
  localStorage.setItem('modoc.token', 'test-token');
  localStorage.setItem('modoc.user', JSON.stringify(ME));
  return render(
    <MemoryRouter>
      <AuthProvider>
        <ToastProvider>
          <AdminRetentionPage />
        </ToastProvider>
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('AdminRetentionPage', () => {
  beforeEach(() => {
    retentionStatus.mockReset().mockResolvedValue(STATUS);
    runRetention.mockReset().mockResolvedValue({});
    dsrSearch.mockReset().mockResolvedValue(SEARCH_RESULT);
    dsrExport.mockReset().mockResolvedValue(undefined);
    dsrErase.mockReset().mockResolvedValue({ totalRemoved: 4, counts: {} });
  });

  it('shows the retention policy and the last run counts', async () => {
    renderPage();

    expect(await screen.findByText('30 days')).toBeInTheDocument();
    expect(screen.getByText('Uploaded files')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  it('search renders the per-store results table', async () => {
    renderPage();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Subject identifier'), 'ANNA-NIC-7788');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText('Ingestion job rows')).toBeInTheDocument();
    expect(screen.getByText(/9 \(import 3\)/)).toBeInTheDocument();
    expect(screen.getByText(/7 \(accounts\.csv\)/)).toBeInTheDocument();
    expect(dsrSearch).toHaveBeenCalledWith('ANNA-NIC-7788');
  });

  it('erase stays disabled until the identifier is typed back exactly', async () => {
    renderPage();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Subject identifier'), 'ANNA-NIC-7788');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    const eraseButton = await screen.findByRole('button', { name: 'Erase permanently' });

    expect(eraseButton).toBeDisabled();

    await user.type(screen.getByLabelText('Erase confirmation'), 'wrong-value');
    expect(eraseButton).toBeDisabled();
    expect(dsrErase).not.toHaveBeenCalled();

    await user.clear(screen.getByLabelText('Erase confirmation'));
    await user.type(screen.getByLabelText('Erase confirmation'), 'ANNA-NIC-7788');
    expect(eraseButton).toBeEnabled();

    dsrSearch.mockResolvedValueOnce(SEARCH_RESULT); // the pre-erase search already consumed one
    await user.click(eraseButton);

    await waitFor(() => expect(dsrErase).toHaveBeenCalledWith('ANNA-NIC-7788'));
    // Closure evidence: the page re-runs the search after the erase.
    await waitFor(() => expect(dsrSearch).toHaveBeenCalledTimes(2));
  });

  it('export downloads the ZIP for the searched identifier', async () => {
    renderPage();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Subject identifier'), 'ANNA-NIC-7788');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await user.click(await screen.findByRole('button', { name: 'Export ZIP' }));

    await waitFor(() => expect(dsrExport).toHaveBeenCalledWith('ANNA-NIC-7788'));
  });
});
