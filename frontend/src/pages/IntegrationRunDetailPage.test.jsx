import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import IntegrationRunDetailPage from './IntegrationRunDetailPage';
import { READER, WRITER, renderWithProviders } from '../test/renderWithProviders';
import { getRun, retryRun } from '../api/integrations';

vi.mock('../api/integrations', () => ({ getRun: vi.fn(), retryRun: vi.fn() }));

const RUN = {
  id: 41,
  flowId: 13,
  flowName: 'Push results',
  direction: 'OUTBOUND',
  status: 'SUCCEEDED',
  trigger: 'EVENT',
  scheduledFor: '2026-10-10T09:00:00Z',
  startedAt: '2026-10-10T09:00:01Z',
  finishedAt: '2026-10-10T09:00:05Z',
  attempt: 1,
  recordsIn: 20,
  recordsOut: 18,
  ingestionJobId: null,
  error: null,
  events: [
    { at: '2026-10-10T09:00:01Z', level: 'INFO', message: 'Batch 1 sent' },
    { at: '2026-10-10T09:00:04Z', level: 'ERROR', message: 'Batch 2 refused (HTTP 422)' },
  ],
  deliveries: [
    { id: 7, status: 'DELIVERED', attempts: 1, lastError: null, nextAttemptAt: null },
    { id: 8, status: 'DEAD', attempts: 5, lastError: 'HTTP 422', nextAttemptAt: null },
  ],
};

const renderPage = (user) =>
  renderWithProviders(<IntegrationRunDetailPage />, {
    user,
    path: '/admin/integrations/runs/41',
    route: '/admin/integrations/runs/:id',
  });

describe('IntegrationRunDetailPage', () => {
  beforeEach(() => {
    getRun.mockReset().mockResolvedValue(RUN);
    retryRun.mockReset().mockResolvedValue(null);
  });

  it('shows the log and deliveries with their levels and statuses in words', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: 'Run #41' })).toBeInTheDocument();
    // The heading comes from the route (rendered before getRun resolves): wait
    // for the run itself, or a loaded box races the mock (QA, flaky at load 2+).
    // The status is announced when polling changes it.
    expect(await screen.findByTestId('run-status')).toHaveAttribute('aria-live', 'polite');
    expect(screen.getByTestId('run-status')).toHaveTextContent('Run status: Succeeded');
    // Log levels are text, not only colour.
    expect(screen.getByText(/\[ERROR\]/)).toBeInTheDocument();
    const deliveries = screen.getByRole('table', { name: 'Deliveries' });
    expect(within(deliveries).getByText('Dead')).toBeInTheDocument();
  });

  it('replays the dead deliveries for a run operator', async () => {
    renderPage(WRITER);
    await userEvent.click(await screen.findByRole('button', { name: 'Retry 1 dead delivery' }));
    await waitFor(() => expect(retryRun).toHaveBeenCalledWith(41));
  });

  it('explains itself to a read-only user instead of loading', async () => {
    renderPage(READER);
    expect(await screen.findByText('Administrators only')).toBeInTheDocument();
    expect(getRun).not.toHaveBeenCalled();
  });
});
