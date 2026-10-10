import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import IntegrationFlowsPage from './IntegrationFlowsPage';
import { READER, pendingChange, renderWithProviders } from '../test/renderWithProviders';
import { disableFlow, enableFlow, listFlows, runFlow } from '../api/integrations';

vi.mock('../api/integrations', () => ({
  listFlows: vi.fn(),
  enableFlow: vi.fn(),
  disableFlow: vi.fn(),
  runFlow: vi.fn(),
}));

const PULL = {
  id: 12,
  name: 'Pull loans',
  direction: 'INBOUND',
  connectionName: 'Loans API',
  templateName: 'Loan statement',
  trigger: 'SCHEDULE',
  cron: '0 */15 * * * *',
  enabled: true,
  pendingChangeId: null,
  lastRun: { id: 90, status: 'SUCCEEDED', finishedAt: '2026-10-10T08:00:00Z' },
};
const PUSH = {
  id: 13,
  name: 'Push results',
  direction: 'OUTBOUND',
  connectionName: 'Core DB',
  templateName: 'Loan statement',
  trigger: 'EVENT',
  events: ['INGESTION_VALIDATED', 'DOC_RELEASED'],
  enabled: false,
  pendingChangeId: null,
  lastRun: null,
};

const renderPage = (user) =>
  renderWithProviders(<IntegrationFlowsPage />, { user, path: '/admin/integrations/flows' });

describe('IntegrationFlowsPage', () => {
  beforeEach(() => {
    listFlows.mockReset().mockResolvedValue([PULL, PUSH]);
    enableFlow.mockReset().mockResolvedValue(pendingChange(51, 'INTEGRATION_FLOW_ENABLE'));
    disableFlow.mockReset().mockResolvedValue({ ...PULL, enabled: false });
    runFlow.mockReset().mockResolvedValue({ id: 91, status: 'QUEUED' });
  });

  it('lists flows with their trigger, state and last run', async () => {
    renderPage();
    expect(await screen.findByRole('link', { name: 'Pull loans' })).toHaveAttribute(
      'href',
      '/admin/integrations/flows/12'
    );
    expect(screen.getByText('Schedule 0 */15 * * * *')).toBeInTheDocument();
    expect(screen.getByText('On Upload validated, Document released')).toBeInTheDocument();
    expect(screen.getByText('Succeeded').closest('a')).toHaveAttribute(
      'href',
      '/admin/integrations/runs/90'
    );
    expect(screen.getByRole('link', { name: 'New flow' })).toHaveAttribute(
      'href',
      '/admin/integrations/flows/new'
    );
  });

  it('sends an outbound enable for approval and marks it pending', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Enable Push results' }));

    await waitFor(() => expect(enableFlow).toHaveBeenCalledWith(13));
    expect(await screen.findByText('Sent for approval (change #51)')).toBeInTheDocument();
    expect(screen.getByText(/Enabling flow "Push results" is change #51/)).toBeInTheDocument();
  });

  it('disables at once and runs on demand', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Disable Pull loans' }));
    await waitFor(() => expect(disableFlow).toHaveBeenCalledWith(12));
    expect(await screen.findByText('Flow "Pull loans" disabled')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Run Pull loans now' }));
    await waitFor(() => expect(runFlow).toHaveBeenCalledWith(12));
    expect(await screen.findByText('Run #91 queued')).toBeInTheDocument();
  });

  it('is for administrators only', async () => {
    renderPage(READER);
    expect(await screen.findByText('Administrators only')).toBeInTheDocument();
    expect(listFlows).not.toHaveBeenCalled();
  });
});
