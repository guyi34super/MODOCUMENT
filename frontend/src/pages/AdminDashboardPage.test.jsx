import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import AdminDashboardPage from './AdminDashboardPage';

import { getDashboard } from '../api/dashboard';
import { createUser } from '../api/admin';

vi.mock('../api/dashboard', () => ({ getDashboard: vi.fn() }));
vi.mock('../api/admin', () => ({ createUser: vi.fn() }));

/** The GET /api/admin/dashboard shape (contracts §3), with integrations not tracked yet. */
const DASHBOARD = {
  generatedAt: '2026-10-10T09:00:00Z',
  bank: { code: 'ACME', name: 'Acme Bank' },
  users: {
    total: 4,
    active: 3,
    disabled: 1,
    locked: 1,
    mustChangePassword: 1,
    byRole: { ADMIN: 2, APPROVER: 0, READ_WRITE: 1, READ_ONLY: 1 },
    mfaEnrolled: 1,
    adminsWithoutMfa: 1,
    activeLast7d: 2,
  },
  approvals: { pendingChanges: 2, pendingReleases: 1 },
  ingestion: {
    today: { byStatus: { PENDING: 0, RUNNING: 0, COMPLETED: 1, FAILED: 1, CANCELLED: 0 } },
    last7d: { byStatus: { PENDING: 0, RUNNING: 1, COMPLETED: 5, FAILED: 2, CANCELLED: 0 } },
    parsedBy: { AI: 4, CODE: 1, AI_PARTIAL: 0 },
    failures7d: 2,
  },
  extraction: { last7d: { byStatus: { PENDING: 0, RUNNING: 1, COMPLETED: 2, FAILED: 0 } } },
  alerts: { last7dBySeverity: { INFO: 0, LOW: 0, MEDIUM: 1, HIGH: 1, CRITICAL: 0 }, lastAt: null },
  retention: { enabled: true, days: 30, lastRunAt: '2026-10-09T02:00:00Z', lastPurged: 5 },
  templates: { private: 3, shared: 1 },
  settings: { pendingSecurityChanges: 1, clamped: 0 },
  version: { version: '1.1.0', commit: 'abc1234', deployedAt: null },
  integrations: {
    runs24h: { ok: null, failed: null },
    deadLetters: null,
    agentsOffline: null,
    apiCalls24h: null,
  },
};

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname + location.search}</div>;
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/admin']}>
      <ToastProvider>
        <Routes>
          <Route path="/admin" element={<AdminDashboardPage />} />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </ToastProvider>
    </MemoryRouter>
  );
}

const kpi = (metric) => within(screen.getByTestId(`kpi-${metric}`)).getByTestId('kpi-value');

describe('AdminDashboardPage', () => {
  beforeEach(() => {
    getDashboard.mockReset().mockResolvedValue(DASHBOARD);
    createUser.mockReset();
  });

  it('renders the KPI tiles from the dashboard counts', async () => {
    renderPage();

    await screen.findByTestId('kpi-users-active');
    expect(kpi('users-active')).toHaveTextContent('3');
    expect(kpi('users-locked')).toHaveTextContent('1');
    expect(kpi('users-disabled')).toHaveTextContent('1');
    expect(kpi('mfa-enrolment')).toHaveTextContent('25%');
    expect(kpi('admins-without-mfa')).toHaveTextContent('1');
    expect(kpi('pending-approvals')).toHaveTextContent('2');
    expect(kpi('pending-security-settings')).toHaveTextContent('1');
    expect(kpi('ingestion-today')).toHaveTextContent('2');
    expect(kpi('ingestion-7d')).toHaveTextContent('8');
    expect(kpi('ingestion-failures')).toHaveTextContent('2');
    expect(kpi('parsed-by-ai')).toHaveTextContent('4');
    expect(kpi('parsed-by-code')).toHaveTextContent('1');
    expect(kpi('extraction-7d')).toHaveTextContent('3');
    expect(kpi('alerts-7d')).toHaveTextContent('2');
    expect(kpi('templates-private')).toHaveTextContent('3');
    expect(kpi('templates-shared')).toHaveTextContent('1');
    expect(kpi('version')).toHaveTextContent('1.1.0');
    expect(screen.getByTestId('kpi-admins-without-mfa')).toHaveAttribute('data-tone', 'danger');
    expect(screen.getByText('Acme Bank at a glance')).toBeInTheDocument();
  });

  it('shows "—" for integration counters that are not tracked yet', async () => {
    renderPage();
    await screen.findByTestId('kpi-integration-runs');

    for (const metric of ['integration-runs', 'integration-dead-letters', 'agents-offline', 'api-calls']) {
      expect(kpi(metric)).toHaveTextContent('—');
    }
    expect(screen.getByText('Available with the integration hub')).toBeInTheDocument();
  });

  it('shows the integration counters once the hub tracks them', async () => {
    getDashboard.mockResolvedValue({
      ...DASHBOARD,
      integrations: { runs24h: { ok: 12, failed: 2 }, deadLetters: 1, agentsOffline: 0, apiCalls24h: 340 },
    });
    renderPage();
    await screen.findByTestId('kpi-integration-runs');

    expect(kpi('integration-runs')).toHaveTextContent('12');
    expect(screen.getByText('12 ok · 2 failed')).toBeInTheDocument();
    expect(screen.getByTestId('kpi-integration-runs')).toHaveAttribute('data-tone', 'danger');
    expect(kpi('integration-dead-letters')).toHaveTextContent('1');
    expect(kpi('agents-offline')).toHaveTextContent('0');
    expect(kpi('api-calls')).toHaveTextContent('340');

    await userEvent.click(screen.getByTestId('kpi-agents-offline'));
    expect(screen.getByTestId('location')).toHaveTextContent('/admin/integrations/agents');
  });

  it('links the tiles to the pages behind them', async () => {
    renderPage();
    await userEvent.click(await screen.findByTestId('kpi-pending-approvals'));
    expect(screen.getByTestId('location')).toHaveTextContent('/admin/changes');
  });

  it('"New shared template" opens the editor preset to bank visibility', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'New shared template' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/templates/new?visibility=BANK');
  });

  it('"Review approvals" opens the approval queue', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Review approvals' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/admin/changes');
  });

  it('"Add user" opens the create-user form in a dialog', async () => {
    createUser.mockResolvedValue({ pendingChangeId: 12, status: 'REQUESTED' });
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Add user' }));

    const dialog = screen.getByRole('dialog', { name: 'Add user' });
    await userEvent.type(within(dialog).getByLabelText(/Email/), 'new@bank.example');
    await userEvent.type(within(dialog).getByLabelText(/Temporary password/), 'Temp-Rotor-91x!');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create user' }));

    await waitFor(() =>
      expect(createUser).toHaveBeenCalledWith({
        email: 'new@bank.example',
        displayName: '',
        role: 'READ_ONLY',
        temporaryPassword: 'Temp-Rotor-91x!',
      })
    );
    expect(await screen.findByText(/submitted for approval.*change #12/)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('"Invite" creates the account and produces a copyable invitation', async () => {
    createUser.mockResolvedValue({ pendingChangeId: 13, status: 'REQUESTED' });
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } });
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Invite' }));

    const dialog = screen.getByRole('dialog', { name: 'Invite a user' });
    expect(within(dialog).getByText('No email is sent')).toBeInTheDocument();
    const password = within(dialog).getByLabelText(/Temporary password/).value;
    expect(password).toMatch(/^[A-Za-z0-9]{4}(-[A-Za-z0-9]{4}){3}$/);
    await userEvent.type(within(dialog).getByLabelText(/Email/), 'joiner@bank.example');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create account' }));

    await waitFor(() =>
      expect(createUser).toHaveBeenCalledWith(
        expect.objectContaining({ email: 'joiner@bank.example', temporaryPassword: password })
      )
    );
    const text = (await within(dialog).findByTestId('invite-text')).value;
    expect(text).toContain(`${window.location.origin}/login`);
    expect(text).toContain('Email: joiner@bank.example');
    expect(text).toContain(`Temporary password: ${password}`);
    expect(text).toContain('Acme Bank');
    expect(text).toContain('once a second administrator has approved it');

    await userEvent.click(within(dialog).getByRole('button', { name: 'Copy invitation' }));
    expect(writeText).toHaveBeenCalledWith(text);
  });

  it('shows the load error', async () => {
    getDashboard.mockRejectedValue(new Error('Request failed (403)'));
    renderPage();
    expect(await screen.findByText('Request failed (403)')).toBeInTheDocument();
  });
});
