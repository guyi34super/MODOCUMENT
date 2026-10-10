import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import PlatformBankDetailPage from './PlatformBankDetailPage';
import { addBankAdmin, getBank, reactivateBank, suspendBank } from '../api/platform';

vi.mock('../api/platform', () => ({
  getBank: vi.fn(),
  suspendBank: vi.fn(),
  reactivateBank: vi.fn(),
  addBankAdmin: vi.fn(),
}));

const ACTIVE = {
  bankId: 7,
  code: 'ACME',
  name: 'Acme Bank',
  kind: 'CUSTOMER',
  status: 'ACTIVE',
  users: 12,
  mfaEnrolledPct: 75,
  jobs7d: 40,
  failures7d: 2,
  aiShare7d: null,
  codeShare7d: null,
  lastActivityAt: '2026-10-09T08:00:00',
};
const SUSPENDED = { ...ACTIVE, status: 'SUSPENDED' };
const PLATFORM = { ...ACTIVE, bankId: 2, code: 'MOI-PLATFORM', kind: 'PLATFORM', users: 2 };

const pending = (id, changeType) => ({ pendingChangeId: id, changeType, status: 'REQUESTED' });

function renderPage(bankId = 7) {
  return render(
    <MemoryRouter initialEntries={[`/platform/banks/${bankId}`]}>
      <ToastProvider>
        <Routes>
          <Route path="/platform/banks/:id" element={<PlatformBankDetailPage />} />
        </Routes>
      </ToastProvider>
    </MemoryRouter>
  );
}

describe('PlatformBankDetailPage', () => {
  beforeEach(() => {
    getBank.mockReset().mockResolvedValue(ACTIVE);
    suspendBank.mockReset().mockResolvedValue(pending(41, 'BANK_SUSPEND'));
    reactivateBank.mockReset().mockResolvedValue(ACTIVE);
    addBankAdmin.mockReset().mockResolvedValue(pending(42, 'BANK_ADMIN_ADD'));
  });

  it('shows the bank with counts-only figures', async () => {
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Acme Bank (ACME)' })).toBeInTheDocument();
    expect(getBank).toHaveBeenCalledWith('7');
    expect(screen.getByText('75%')).toBeInTheDocument();
    expect(screen.getByText('Customer bank')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Suspend bank' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reactivate bank' })).not.toBeInTheDocument();
  });

  it('shows integration health (counts only) only when the platform API carries it', async () => {
    renderPage();
    await screen.findByRole('heading', { name: 'Acme Bank (ACME)' });
    expect(screen.queryByRole('region', { name: 'Integration health' })).not.toBeInTheDocument();
  });

  it('renders the per-bank integration counts', async () => {
    getBank.mockResolvedValue({
      ...ACTIVE,
      integrations: {
        connections: 4,
        connectionsFailing: 1,
        flowsEnabled: 3,
        runs24h: { ok: 20, failed: 2 },
        deadLetters: 1,
        agentsOffline: 0,
      },
    });
    renderPage();

    const health = await screen.findByRole('region', { name: 'Integration health' });
    const value = (id) => within(within(health).getByTestId(id)).getByTestId('kpi-value');
    expect(value('kpi-bank-connections')).toHaveTextContent('4');
    expect(within(health).getByText('1 failing')).toBeInTheDocument();
    expect(value('kpi-bank-flows')).toHaveTextContent('3');
    expect(value('kpi-bank-runs')).toHaveTextContent('20');
    expect(within(health).getByText('2 failed')).toBeInTheDocument();
    expect(value('kpi-bank-dead-letters')).toHaveTextContent('1');
    expect(value('kpi-bank-agents-offline')).toHaveTextContent('0');
  });

  it('requests a suspension with a reason and says it was sent for approval', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Suspend bank' }));
    const dialog = screen.getByRole('dialog', { name: 'Suspend ACME' });

    // No reason, no request.
    await userEvent.click(within(dialog).getByRole('button', { name: 'Request suspension' }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent('A reason is required');
    expect(suspendBank).not.toHaveBeenCalled();

    await userEvent.type(within(dialog).getByLabelText('Reason'), 'Contract ended');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Request suspension' }));

    await waitFor(() => expect(suspendBank).toHaveBeenCalledWith(7, 'Contract ended'));
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Suspend ACME' })).not.toBeInTheDocument()
    );
    expect(screen.getByText(/Suspending ACME is change #41/)).toBeInTheDocument();
    // Still ACTIVE until a second platform admin approves.
    expect(screen.getByRole('button', { name: 'Suspend bank' })).toBeInTheDocument();
  });

  it('reactivates a suspended bank directly', async () => {
    getBank.mockResolvedValue(SUSPENDED);
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Reactivate bank' }));
    const dialog = screen.getByRole('dialog', { name: 'Reactivate ACME' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reactivate' }));

    await waitFor(() => expect(reactivateBank).toHaveBeenCalledWith(7));
    expect(await screen.findByRole('button', { name: 'Suspend bank' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reactivate bank' })).not.toBeInTheDocument();
  });

  it('requests a break-glass administrator', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Add admin' }));
    const dialog = screen.getByRole('dialog', { name: 'Add administrator (break-glass)' });

    await userEvent.type(within(dialog).getByLabelText('Email'), 'rescue@acme.example');
    await userEvent.type(within(dialog).getByLabelText('Display name'), 'Rescue Admin');
    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Generate temporary password' })
    );
    await userEvent.type(within(dialog).getByLabelText(/^Reason/), 'Both admins left');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send for approval' }));

    await waitFor(() => expect(addBankAdmin).toHaveBeenCalledTimes(1));
    const [bankId, request] = addBankAdmin.mock.calls[0];
    expect(bankId).toBe(7);
    expect(request).toMatchObject({
      email: 'rescue@acme.example',
      displayName: 'Rescue Admin',
      reason: 'Both admins left',
    });
    expect(request.temporaryPassword).toHaveLength(19);
    expect(await screen.findByText(/as an administrator is change #42/)).toBeInTheDocument();
  });

  it('offers no actions on the platform bank', async () => {
    getBank.mockResolvedValue(PLATFORM);
    renderPage(2);

    expect(await screen.findByText('This is the platform bank')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Suspend bank' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add admin' })).not.toBeInTheDocument();
  });

  it('shows a not-found bank as an error', async () => {
    getBank.mockRejectedValue(new Error('Bank 9 not found.'));
    renderPage(9);

    expect(await screen.findByText('Bank 9 not found.')).toBeInTheDocument();
  });
});
