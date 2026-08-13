import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import ExtractionJobDetailPage from './ExtractionJobDetailPage';

import {
  getAudit,
  getJob,
  getRecords,
} from '../api/extraction';
import { getTemplates } from '../api/cache';

vi.mock('../api/extraction', () => ({
  bridgeToIngestion: vi.fn(),
  cancelJob: vi.fn(),
  getAudit: vi.fn(),
  getJob: vi.fn(),
  getRecords: vi.fn(),
  pauseJob: vi.fn(),
  resumeJob: vi.fn(),
  startJob: vi.fn(),
}));

vi.mock('../api/cache', () => ({
  getTemplates: vi.fn(),
}));

const job = (status, processed = 0, extra = {}) => ({
  id: 'job-1234567890',
  sourceId: 'src-1',
  status,
  counters: { tasksCompleted: 1, recordsProcessed: processed },
  ...extra,
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/extraction/jobs/job-1234567890']}>
      <ToastProvider>
        <Routes>
          <Route path="/extraction/jobs/:id" element={<ExtractionJobDetailPage />} />
        </Routes>
      </ToastProvider>
    </MemoryRouter>
  );
}

describe('ExtractionJobDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getTemplates.mockResolvedValue([]);
    getRecords.mockResolvedValue({ records: [], nextAfterId: null });
    getAudit.mockResolvedValue({ events: [], nextAfterId: null });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('auto-loads records for a completed job without announcing a completion toast', async () => {
    getJob.mockResolvedValue(job('COMPLETED', 2));
    getRecords.mockResolvedValue({
      records: [{ id: 'rec-1-abcdefgh', fields: { Bank: 'JPMorgan' } }],
      nextAfterId: null,
    });
    renderPage();

    expect(await screen.findByText(/JPMorgan/)).toBeInTheDocument();
    // Arriving on an already-finished job stays silent — no completion toast.
    expect(screen.queryByText(/Extraction completed/)).not.toBeInTheDocument();
    expect(getAudit).not.toHaveBeenCalled();
  });

  it('explains a zero-record completion and auto-loads the audit trail', async () => {
    getJob.mockResolvedValue(job('COMPLETED', 0));
    getAudit.mockResolvedValue({
      events: [
        {
          id: 'ev-1',
          action: 'ROBOTS_BLOCKED',
          outcome: 'blocked',
          uri: 'https://example.com/',
          detail: 'Disallowed by robots.txt (or robots.txt unavailable).',
          timestamp: '2026-08-13T10:00:00Z',
        },
      ],
      nextAfterId: null,
    });
    renderPage();

    expect(await screen.findByText('Completed without records')).toBeInTheDocument();
    expect(await screen.findByText('ROBOTS_BLOCKED')).toBeInTheDocument();
    expect(screen.getByText(/Disallowed by robots.txt/)).toBeInTheDocument();
  });

  it('polls a running job and morphs the running toast into the completion outcome', async () => {
    vi.useFakeTimers();
    getJob
      .mockResolvedValueOnce(job('RUNNING'))
      .mockResolvedValue(job('COMPLETED', 3));
    renderPage();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(screen.getByText('Extraction running…')).toBeInTheDocument();
    expect(screen.getByText('Updating live…')).toBeInTheDocument();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2500);
    });
    expect(getJob).toHaveBeenCalledTimes(2);
    expect(screen.getByText('Extraction completed — 3 processed records')).toBeInTheDocument();
    expect(screen.queryByText('Updating live…')).not.toBeInTheDocument();
    expect(getRecords).toHaveBeenCalled();
  });

  it('toasts the audit-trail hint when a watched run ends with zero records', async () => {
    vi.useFakeTimers();
    getJob
      .mockResolvedValueOnce(job('RUNNING'))
      .mockResolvedValue(job('COMPLETED', 0));
    renderPage();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2500);
    });
    expect(
      screen.getByText('Job completed but produced no records — check the audit trail')
    ).toBeInTheDocument();
    expect(getAudit).toHaveBeenCalled();
  });
});
