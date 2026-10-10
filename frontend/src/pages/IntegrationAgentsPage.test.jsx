import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import IntegrationAgentsPage from './IntegrationAgentsPage';
import { WRITER, pendingChange, renderWithProviders } from '../test/renderWithProviders';
import { getAgentEnrolment, listAgents, registerAgent } from '../api/integrations';

vi.mock('../api/integrations', () => ({
  listAgents: vi.fn(),
  registerAgent: vi.fn(),
  getAgentEnrolment: vi.fn(),
}));

const AGENTS = [
  {
    id: 5,
    name: 'dc1-agent',
    status: 'ONLINE',
    version: '1.2.0',
    latestVersion: '1.3.0',
    updateAvailable: true,
    lastHeartbeatAt: '2026-10-10T09:00:00Z',
    connections: [7, 8],
  },
  { id: 6, name: 'dc2-agent', status: 'OFFLINE', version: '1.3.0', updateAvailable: false, connections: [] },
  {
    id: 7,
    name: 'new-agent',
    status: 'PENDING_ENROLMENT',
    version: null,
    connections: [],
    enrolmentAvailable: true,
    enrolmentExpiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
  },
  // Pending, but the token is not this caller's to read (or already shown).
  {
    id: 8,
    name: 'colleague-agent',
    status: 'PENDING_ENROLMENT',
    version: null,
    connections: [],
    enrolmentAvailable: false,
    enrolmentExpiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
  },
  // Pending and expired.
  {
    id: 9,
    name: 'stale-agent',
    status: 'PENDING_ENROLMENT',
    version: null,
    connections: [],
    enrolmentAvailable: false,
    enrolmentExpiresAt: '2026-01-01T00:00:00Z',
  },
];

const TOKEN = 'enrol-tok-once-only';
const COMMAND = `docker run -e MODOCUMENT_ENROLMENT_TOKEN=${TOKEN} modocument/agent:1.3.0`;

const renderPage = (user) =>
  renderWithProviders(<IntegrationAgentsPage />, { user, path: '/admin/integrations/agents' });

describe('IntegrationAgentsPage', () => {
  beforeEach(() => {
    listAgents.mockReset().mockResolvedValue(AGENTS);
    registerAgent.mockReset().mockResolvedValue(pendingChange(70, 'AGENT_REGISTER'));
    getAgentEnrolment.mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows status chips, versions and the update badge', async () => {
    renderPage();
    expect(await screen.findByText('dc1-agent')).toBeInTheDocument();
    expect(screen.getByText('Online')).toBeInTheDocument();
    expect(screen.getByText('Offline')).toBeInTheDocument();
    expect(screen.getAllByText('Pending enrolment')).toHaveLength(3);
    // The badge comes from AgentView.updateAvailable + latestVersion only.
    expect(screen.getByText('Update available (1.3.0)')).toBeInTheDocument();
    expect(screen.getAllByText(/Update available/)).toHaveLength(1);
    expect(screen.getByRole('table', { name: 'Connector agents' })).toBeInTheDocument();
  });

  it('offers the enrolment command only while the server says it is available', async () => {
    renderPage();
    await screen.findByText('dc1-agent');
    expect(screen.getAllByRole('button', { name: /Get enrolment command/ })).toHaveLength(1);
    expect(
      screen.getByRole('button', { name: 'Get enrolment command for new-agent' })
    ).toBeInTheDocument();
    const row = (name) => screen.getByText(name).closest('tr');
    expect(within(row('new-agent')).getByText(/^Expires /)).toBeInTheDocument();
    expect(within(row('colleague-agent')).getByText(/Waiting for enrolment/)).toBeInTheDocument();
    expect(within(row('stale-agent')).getByText(/Enrolment expired/)).toBeInTheDocument();
  });

  it('registers an agent for approval', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Register agent' }));
    const dialog = screen.getByRole('dialog', { name: 'Register agent' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send for approval' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Give the agent a name');

    await userEvent.type(within(dialog).getByLabelText('Agent name'), 'dc3-agent');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send for approval' }));
    await waitFor(() => expect(registerAgent).toHaveBeenCalledWith('dc3-agent'));
    expect(await screen.findByText('Sent for approval (change #70)')).toBeInTheDocument();
    expect(screen.getByText(/Registering agent "dc3-agent" is change #70/)).toBeInTheDocument();
  });

  it('shows the enrolment command once with a countdown, then forgets it', async () => {
    const expiresAt = new Date(Date.now() + 15 * 60 * 1000).toISOString();
    getAgentEnrolment.mockResolvedValue({ token: TOKEN, expiresAt, dockerCommand: COMMAND });
    renderPage();
    await userEvent.click(
      await screen.findByRole('button', { name: 'Get enrolment command for new-agent' })
    );

    const dialog = await screen.findByRole('dialog', { name: 'Enrol "new-agent"' });
    expect(getAgentEnrolment).toHaveBeenCalledWith(7);
    expect(within(dialog).getByLabelText('Docker command')).toHaveValue(COMMAND);
    expect(within(dialog).getByLabelText('Enrolment token')).toHaveValue(TOKEN);
    expect(within(dialog).getByText("You won't see this again")).toBeInTheDocument();
    const countdown = within(dialog).getByRole('timer');
    expect(countdown.textContent).toMatch(/^1[45]:\d\d$/);

    await userEvent.click(within(dialog).getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(document.body.textContent).not.toContain(TOKEN);
  });

  it('counts down to "Expired"', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const expiresAt = new Date(Date.now() + 2000).toISOString();
    getAgentEnrolment.mockResolvedValue({ token: TOKEN, expiresAt, dockerCommand: COMMAND });
    renderPage();
    await userEvent.click(
      await screen.findByRole('button', { name: 'Get enrolment command for new-agent' })
    );
    await screen.findByRole('timer');
    await act(async () => {
      vi.advanceTimersByTime(3000);
    });
    expect(screen.getByText(/Expired — request a new one/)).toBeInTheDocument();
  });

  it('is for administrators only', async () => {
    renderPage(WRITER);
    expect(await screen.findByText('Administrators only')).toBeInTheDocument();
    expect(listAgents).not.toHaveBeenCalled();
  });
});
