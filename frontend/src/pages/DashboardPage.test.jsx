import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import DashboardPage, { resetSummaryProbe } from './DashboardPage';

import { api } from '../api/client';
import { getTemplates } from '../api/cache';

vi.mock('../api/client', () => ({
  api: { get: vi.fn() },
}));

vi.mock('../api/cache', () => ({
  getTemplates: vi.fn(),
}));

const inProgressJob = {
  ingestionJobId: 4,
  fileName: 'people.csv',
  currentStep: 'MAPPED',
  // Every successful stage leaves the job COMPLETED — an in-progress upload
  // looks exactly like this.
  status: 'COMPLETED',
};

function renderPage() {
  return render(
    <MemoryRouter>
      <DashboardPage />
    </MemoryRouter>
  );
}

function notFound() {
  const error = new Error('Request failed (404)');
  error.status = 404;
  return error;
}

/** A server without GET /api/dashboard/summary (404): the four lists. */
function mockEndpoints({ jobs = [], extraction = [], imports = [] } = {}) {
  api.get.mockImplementation((path) => {
    if (path === '/api/dashboard/summary') return Promise.reject(notFound());
    if (path === '/api/ingestion/jobs') return Promise.resolve(jobs);
    if (path === '/api/extraction/jobs') return Promise.resolve(extraction);
    if (path === '/ImportHistory/GetAll') return Promise.resolve(imports);
    return Promise.reject(new Error(`unexpected ${path}`));
  });
}

describe('DashboardPage', () => {
  beforeEach(() => {
    resetSummaryProbe();
    api.get.mockReset();
    getTemplates.mockReset();
    getTemplates.mockResolvedValue([{ templateId: 1, name: 'People' }]);
  });

  it('offers to resume an unfinished job even though its status is COMPLETED', async () => {
    mockEndpoints({ jobs: [inProgressJob] });
    renderPage();

    expect(await screen.findByText('Pick up where you left off')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Job #4' })).toHaveAttribute('href', '/ingestion/4');
    expect(screen.getByText('Ready to review and validate')).toBeInTheDocument();
  });

  it('says what a job is waiting on rather than only its stage', async () => {
    mockEndpoints({
      jobs: [
        { ...inProgressJob, ingestionJobId: 7, unmappedFields: ['Email', 'Phone'] },
        {
          ...inProgressJob,
          ingestionJobId: 8,
          currentStep: 'VALIDATED',
          unmappedFields: [],
          validationErrorCount: 3,
        },
      ],
    });
    renderPage();

    expect(await screen.findByText('2 fields still to map')).toBeInTheDocument();
    expect(screen.getByText('3 rows to fix')).toBeInTheDocument();
  });

  it('leaves out exported and cancelled jobs', async () => {
    mockEndpoints({
      jobs: [
        { ...inProgressJob, ingestionJobId: 5, currentStep: 'EXPORTED' },
        { ...inProgressJob, ingestionJobId: 6, status: 'CANCELLED' },
      ],
    });
    renderPage();

    await screen.findByText('Recent activity');
    expect(screen.queryByText('Pick up where you left off')).not.toBeInTheDocument();
  });

  it('renders the rest of the page when one endpoint fails, with a Retry', async () => {
    getTemplates.mockRejectedValue(new Error('templates exploded'));
    mockEndpoints({ jobs: [inProgressJob] });
    renderPage();

    expect(await screen.findByText(/Could not load templates/)).toBeInTheDocument();
    // The page is usable: the panels that did load are on screen.
    expect(screen.getByText('Pick up where you left off')).toBeInTheDocument();

    getTemplates.mockResolvedValue([{ templateId: 1, name: 'People' }]);
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));

    expect(screen.queryByText(/Could not load templates/)).not.toBeInTheDocument();
  });

  it('highlights field rules once a template exists but nothing has been uploaded', async () => {
    mockEndpoints({ jobs: [] });
    renderPage();

    const rules = await screen.findByRole('link', { name: /Set Field Rules/ });
    expect(rules.className).toContain('border-accent');
  });

  it('asks for the 404ing summary only once per session', async () => {
    mockEndpoints({ jobs: [inProgressJob] });
    renderPage();
    await screen.findByText('Pick up where you left off');

    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    await screen.findByText('Pick up where you left off');
    const summaryCalls = api.get.mock.calls.filter(([p]) => p === '/api/dashboard/summary');
    expect(summaryCalls).toHaveLength(1);
    expect(api.get.mock.calls.filter(([p]) => p === '/api/ingestion/jobs')).toHaveLength(2);
  });

  describe('with GET /api/dashboard/summary (v1.2.0)', () => {
    const summary = {
      templates: 3,
      ingestion: {
        total: 42,
        byStatus: { COMPLETED: 30, FAILED: 2 },
        recent: [
          {
            ingestionJobId: 41,
            fileName: 'payroll.xlsx',
            templateId: 1,
            templateName: 'People',
            status: 'COMPLETED',
            currentStep: 'PARSED',
            validationErrorCount: null,
            unmappedFieldCount: 3,
          },
          { ingestionJobId: 40, fileName: 'done.csv', status: 'COMPLETED', currentStep: 'EXPORTED' },
        ],
      },
      extraction: { total: 7, running: 2, recent: [] },
      imports: {
        total: 1,
        recent: [{ importHistoryId: 9, fileName: 'import.csv', templateId: 1, importDate: '2026-10-01T10:00:00' }],
      },
    };

    beforeEach(() => {
      api.get.mockImplementation((path) =>
        path === '/api/dashboard/summary'
          ? Promise.resolve(summary)
          : Promise.reject(new Error(`unexpected ${path}`))
      );
    });

    it('renders from the one summary call, never the full lists', async () => {
      renderPage();

      expect(await screen.findByText('3 fields still to map')).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Job #41' })).toBeInTheDocument();
      expect(screen.queryByRole('link', { name: 'Job #40' })).not.toBeInTheDocument();
      expect(screen.getByText('42 jobs · 30 completed · 2 failed')).toBeInTheDocument();
      expect(screen.getByText('2 failed jobs')).toBeInTheDocument();
      expect(screen.getByText(/7 jobs, 2 running/)).toBeInTheDocument();
      expect(screen.getByText('import.csv')).toBeInTheDocument();
      // Template names still come from the session's template cache.
      expect(screen.getByRole('cell', { name: 'People' })).toBeInTheDocument();
      expect(api.get.mock.calls.map(([p]) => p)).toEqual(['/api/dashboard/summary']);
      expect(screen.queryByText(/Could not load/)).not.toBeInTheDocument();
    });

    it('falls back to the lists when the summary fails for another reason', async () => {
      api.get.mockImplementation((path) => {
        if (path === '/api/dashboard/summary') return Promise.reject(new Error('boom'));
        if (path === '/api/ingestion/jobs') return Promise.resolve([inProgressJob]);
        return Promise.resolve([]);
      });
      renderPage();

      expect(await screen.findByRole('link', { name: 'Job #4' })).toBeInTheDocument();
    });
  });
});
