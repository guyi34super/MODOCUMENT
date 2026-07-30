import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import DashboardPage from './DashboardPage';

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

function mockEndpoints({ jobs = [], extraction = [], imports = [] } = {}) {
  api.get.mockImplementation((path) => {
    if (path === '/api/ingestion/jobs') return Promise.resolve(jobs);
    if (path === '/api/extraction/jobs') return Promise.resolve(extraction);
    if (path === '/ImportHistory/GetAll') return Promise.resolve(imports);
    return Promise.reject(new Error(`unexpected ${path}`));
  });
}

describe('DashboardPage', () => {
  beforeEach(() => {
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
});
