import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';
import AdminAlertsPage from './AdminAlertsPage';

import { listAlerts } from '../api/alerts';

vi.mock('../api/alerts', () => ({
  listAlerts: vi.fn(),
}));

const ME = { userId: 1, email: 'admin@bank.example', displayName: 'Admin', role: 'ADMIN' };

const ALERTS = [
  {
    alertId: 12,
    rule: 'export-blocked',
    severity: 'HIGH',
    summary: 'Export above the role row cap refused - EXPORT_BLOCKED (DENIED) by user #7',
    eventCount: 1,
    auditEventId: '0192f1c4-7a3e-7c11-9b2e-5d0c6a1f4e88',
    createdAt: '2026-10-09T10:15:30Z',
    deliveredAt: '2026-10-09T10:15:32Z',
    channels: 'log,webhook',
    status: 'SENT',
  },
  {
    alertId: 11,
    rule: 'login-failures-per-user',
    severity: 'MEDIUM',
    summary: '5 LOGIN_FAILED event(s) within 5m for user #9',
    eventCount: 5,
    auditEventId: null,
    createdAt: '2026-10-09T10:10:00Z',
    deliveredAt: null,
    channels: 'log',
    status: 'PENDING',
  },
];

function renderPage() {
  localStorage.setItem('modoc.token', 'test-token');
  localStorage.setItem('modoc.user', JSON.stringify(ME));
  return render(
    <MemoryRouter>
      <AuthProvider>
        <ToastProvider>
          <AdminAlertsPage />
        </ToastProvider>
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('AdminAlertsPage', () => {
  beforeEach(() => {
    listAlerts.mockReset().mockResolvedValue(ALERTS);
  });

  it('lists the bank alerts with severity, rule, summary and delivery evidence', async () => {
    renderPage();

    expect(await screen.findByText('export-blocked')).toBeInTheDocument();
    expect(screen.getByText('HIGH')).toBeInTheDocument();
    expect(screen.getByText(/EXPORT_BLOCKED \(DENIED\) by user #7/)).toBeInTheDocument();
    expect(screen.getByText('Delivered')).toBeInTheDocument();
    expect(screen.getByText('via log,webhook')).toBeInTheDocument();
    expect(screen.getByText('in 2s')).toBeInTheDocument();

    expect(screen.getByText('login-failures-per-user')).toBeInTheDocument();
    expect(screen.getByText('(5 events)')).toBeInTheDocument();
    expect(screen.getByText('Delivering…')).toBeInTheDocument();
  });

  it('shows an empty state when nothing was raised', async () => {
    listAlerts.mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText('No alerts have been raised for this bank.')).toBeInTheDocument();
  });

  it('refresh reloads the list', async () => {
    renderPage();
    await screen.findByText('export-blocked');

    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));

    await waitFor(() => expect(listAlerts).toHaveBeenCalledTimes(2));
  });

  it('surfaces a load failure in the error banner', async () => {
    listAlerts.mockRejectedValue(new Error('Request failed (403)'));
    renderPage();

    expect(await screen.findByText('Request failed (403)')).toBeInTheDocument();
  });
});
