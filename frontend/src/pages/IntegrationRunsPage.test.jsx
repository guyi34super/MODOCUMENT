import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import IntegrationRunsPage from './IntegrationRunsPage';
import IntegrationRunDetailPage from './IntegrationRunDetailPage';
import { READER, WRITER, renderWithProviders } from '../test/renderWithProviders';
import { getRun, listRuns, retryRun } from '../api/integrations';

vi.mock('../api/integrations', () => ({
  listRuns: vi.fn(),
  getRun: vi.fn(),
  retryRun: vi.fn(),
}));

const FAILED = {
  id: 90,
  flowId: 13,
  flowName: 'Push results',
  direction: 'OUTBOUND',
  status: 'FAILED',
  trigger: 'EVENT',
  scheduledFor: '2026-10-10T08:00:00Z',
  startedAt: '2026-10-10T08:00:01Z',
  finishedAt: '2026-10-10T08:00:09Z',
  attempt: 2,
  recordsIn: 120,
  recordsOut: 80,
  ingestionJobId: 400,
  error: 'Remote answered 503',
};
const OK = { ...FAILED, id: 91, status: 'SUCCEEDED', error: null, flowName: 'Pull loans' };
const DETAIL = {
  ...FAILED,
  events: [
    { at: '2026-10-10T08:00:01Z', level: 'INFO', message: 'Batch 1 of 2 sent' },
    { at: '2026-10-10T08:00:09Z', level: 'ERROR', message: 'Batch 2: 503 Service Unavailable' },
  ],
  deliveries: [
    { id: 1, status: 'DELIVERED', attempts: 1, lastError: null, nextAttemptAt: null },
    { id: 2, status: 'DEAD', attempts: 8, lastError: '503 Service Unavailable', nextAttemptAt: null },
  ],
};

describe('IntegrationRunsPage', () => {
  beforeEach(() => {
    listRuns.mockReset().mockResolvedValue([FAILED, OK]);
  });

  it('lists runs for READ_WRITE users too (integration:run) and filters by status', async () => {
    renderWithProviders(<IntegrationRunsPage />, { user: WRITER, path: '/admin/integrations/runs' });

    expect(await screen.findByRole('link', { name: '#90' })).toHaveAttribute(
      'href',
      '/admin/integrations/runs/90'
    );
    expect(screen.getByText('Remote answered 503')).toBeInTheDocument();
    expect(screen.getAllByText('120 / 80')).toHaveLength(2);
    expect(listRuns).toHaveBeenCalledWith({ status: '' });

    await userEvent.selectOptions(screen.getByLabelText('Status filter'), 'FAILED');
    await waitFor(() => expect(listRuns).toHaveBeenLastCalledWith({ status: 'FAILED' }));
  });

  it('is hidden from READ_ONLY users', async () => {
    renderWithProviders(<IntegrationRunsPage />, { user: READER, path: '/admin/integrations/runs' });
    expect(await screen.findByText('Administrators only')).toBeInTheDocument();
    expect(listRuns).not.toHaveBeenCalled();
  });
});

describe('IntegrationRunDetailPage', () => {
  beforeEach(() => {
    getRun.mockReset().mockResolvedValue(DETAIL);
    retryRun.mockReset().mockResolvedValue({ ...FAILED, status: 'QUEUED' });
  });

  const renderDetail = (user) =>
    renderWithProviders(<IntegrationRunDetailPage />, {
      user,
      path: '/admin/integrations/runs/90',
      route: '/admin/integrations/runs/:id',
    });

  it('shows the log, the deliveries and retries the dead ones', async () => {
    renderDetail(WRITER);

    const log = await screen.findByRole('region', { name: 'Run log' });
    expect(within(log).getByText(/Batch 2: 503 Service Unavailable/)).toBeInTheDocument();
    const deliveries = screen.getByRole('region', { name: 'Deliveries' });
    expect(within(deliveries).getByText('Dead')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Job #400' })).toHaveAttribute('href', '/ingestion/400');
    // A READ_WRITE user does not get the admin-only flow link.
    expect(screen.queryByRole('link', { name: 'Push results' })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Retry 1 dead delivery' }));
    await waitFor(() => expect(retryRun).toHaveBeenCalledWith(90));
    expect(await screen.findByText('Run #90 queued for retry')).toBeInTheDocument();
  });

  it('offers no retry for a successful run', async () => {
    getRun.mockResolvedValue({ ...OK, events: [], deliveries: [] });
    renderDetail();
    expect(await screen.findByText('Succeeded')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Retry/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Pull loans' })).toHaveAttribute(
      'href',
      '/admin/integrations/flows/13'
    );
  });
});
