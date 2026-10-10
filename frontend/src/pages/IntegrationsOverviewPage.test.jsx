import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import IntegrationsOverviewPage from './IntegrationsOverviewPage';
import { WRITER, renderWithProviders } from '../test/renderWithProviders';
import { getDashboard } from '../api/dashboard';
import { listAgents, listConnections, listFlows, listRuns } from '../api/integrations';

vi.mock('../api/dashboard', () => ({ getDashboard: vi.fn() }));
vi.mock('../api/integrations', () => ({
  listConnections: vi.fn(),
  listFlows: vi.fn(),
  listRuns: vi.fn(),
  listAgents: vi.fn(),
}));

const recent = new Date(Date.now() - 60 * 60 * 1000).toISOString();
const old = new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString();

const kpi = (metric) => within(screen.getByTestId(`kpi-${metric}`)).getByTestId('kpi-value');

describe('IntegrationsOverviewPage', () => {
  beforeEach(() => {
    listConnections.mockReset().mockResolvedValue([
      { id: 1, status: 'OK' },
      { id: 2, status: 'FAILED' },
      { id: 3, status: 'UNTESTED' },
    ]);
    listFlows.mockReset().mockResolvedValue([
      { id: 1, enabled: true },
      { id: 2, enabled: false },
    ]);
    listRuns.mockReset().mockResolvedValue([
      { id: 10, flowName: 'Pull loans', status: 'FAILED', startedAt: recent, finishedAt: recent },
      { id: 9, flowName: 'Pull loans', status: 'FAILED', startedAt: old, finishedAt: old },
      { id: 8, flowName: 'Push results', status: 'SUCCEEDED', startedAt: old, finishedAt: old },
    ]);
    listAgents.mockReset().mockResolvedValue([
      { id: 1, status: 'ONLINE', updateAvailable: true },
      { id: 2, status: 'OFFLINE' },
    ]);
    getDashboard.mockReset().mockResolvedValue({ integrations: { deadLetters: 4 } });
  });

  it('summarises connections, flows, failures, dead letters and agents', async () => {
    renderWithProviders(<IntegrationsOverviewPage />, { path: '/admin/integrations' });

    await screen.findByTestId('kpi-connections');
    expect(kpi('connections')).toHaveTextContent('3');
    expect(screen.getByText('1 OK · 1 failing · 1 untested')).toBeInTheDocument();
    expect(kpi('flows-enabled')).toHaveTextContent('1');
    // Only the failure of the last 24 hours counts.
    expect(kpi('run-failures')).toHaveTextContent('1');
    expect(kpi('dead-letters')).toHaveTextContent('4');
    expect(kpi('agents')).toHaveTextContent('1');
    expect(screen.getByText(/1 offline · 0 awaiting enrolment · 1 update available/)).toBeInTheDocument();
    const runs = screen.getByRole('region', { name: 'Last runs' });
    expect(within(runs).getByRole('link', { name: '#10' })).toHaveAttribute(
      'href',
      '/admin/integrations/runs/10'
    );
  });

  it('still renders when one endpoint fails', async () => {
    listAgents.mockRejectedValue(new Error('Request failed (503)'));
    renderWithProviders(<IntegrationsOverviewPage />, { path: '/admin/integrations' });
    expect(await screen.findByText('Request failed (503)')).toBeInTheDocument();
    expect(kpi('connections')).toHaveTextContent('3');
  });

  it('is for administrators only', async () => {
    renderWithProviders(<IntegrationsOverviewPage />, { user: WRITER, path: '/admin/integrations' });
    expect(await screen.findByText('Administrators only')).toBeInTheDocument();
    expect(listConnections).not.toHaveBeenCalled();
  });
});
