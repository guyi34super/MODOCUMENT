import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import IntegrationEgressRulesPage from './IntegrationEgressRulesPage';
import PlatformEgressRulesPage from './PlatformEgressRulesPage';
import { PLATFORM, pendingChange, renderWithProviders } from '../test/renderWithProviders';
import {
  approveEgressRule,
  listEgressRules,
  listPlatformEgressRules,
  requestEgressRule,
  revokeEgressRule,
} from '../api/integrations';

vi.mock('../api/integrations', () => ({
  listEgressRules: vi.fn(),
  requestEgressRule: vi.fn(),
  revokeEgressRule: vi.fn(),
  listPlatformEgressRules: vi.fn(),
  approveEgressRule: vi.fn(),
}));

const RULES = [
  {
    id: 1,
    host: 'db.internal.bank.example',
    cidr: null,
    port: 5432,
    status: 'APPROVED',
    requestedBy: 'admin@bank.example',
    approvedBy: 'owner@moi.example',
    createdAt: '2026-10-01T09:00:00Z',
  },
  { id: 2, host: null, cidr: '10.20.0.0/24', port: 22, status: 'PENDING', requestedBy: 'admin@bank.example' },
];

describe('IntegrationEgressRulesPage', () => {
  beforeEach(() => {
    listEgressRules.mockReset().mockResolvedValue(RULES);
    requestEgressRule.mockReset().mockResolvedValue({ ...RULES[1], id: 3 });
    revokeEgressRule.mockReset().mockResolvedValue({ ...RULES[0], status: 'REVOKED' });
  });

  const renderPage = () =>
    renderWithProviders(<IntegrationEgressRulesPage />, { path: '/admin/integrations/egress-rules' });

  it('lists the destinations and requests a new one', async () => {
    renderPage();
    expect(await screen.findByText('db.internal.bank.example:5432')).toBeInTheDocument();
    expect(screen.getByText('10.20.0.0/24:22')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('radio', { name: 'CIDR range' }));
    await userEvent.type(screen.getByLabelText('CIDR'), '10.30.0.0/16');
    await userEvent.type(screen.getByLabelText('Port'), '99999');
    await userEvent.type(screen.getByLabelText('Reason'), 'Core banking VPN');
    await userEvent.click(screen.getByRole('button', { name: 'Request destination' }));
    expect(screen.getByRole('alert')).toHaveTextContent('port between 1 and 65535');

    await userEvent.clear(screen.getByLabelText('Port'));
    await userEvent.type(screen.getByLabelText('Port'), '1521');
    await userEvent.click(screen.getByRole('button', { name: 'Request destination' }));
    await waitFor(() =>
      expect(requestEgressRule).toHaveBeenCalledWith({
        host: '',
        cidr: '10.30.0.0/16',
        port: 1521,
        reason: 'Core banking VPN',
      })
    );
    expect(
      await screen.findByText('Requested 10.30.0.0/16:1521 — the platform owner must approve it')
    ).toBeInTheDocument();
  });

  it('shows the pending toast when the request answers 202', async () => {
    requestEgressRule.mockResolvedValue(pendingChange(80, 'EGRESS_RULE_APPROVE'));
    renderPage();
    await userEvent.type(await screen.findByRole('textbox', { name: 'Host' }), 'sftp.internal');
    await userEvent.type(screen.getByLabelText('Port'), '22');
    await userEvent.type(screen.getByLabelText('Reason'), 'Statements');
    await userEvent.click(screen.getByRole('button', { name: 'Request destination' }));
    expect(await screen.findByText('Sent for approval (change #80)')).toBeInTheDocument();
  });

  it('revokes after confirmation', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Revoke db.internal.bank.example' }));
    const dialog = screen.getByRole('dialog', { name: 'Revoke destination' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Revoke' }));
    await waitFor(() => expect(revokeEgressRule).toHaveBeenCalledWith(1));
    expect(await screen.findByText('Destination revoked')).toBeInTheDocument();
  });
});

describe('PlatformEgressRulesPage', () => {
  beforeEach(() => {
    listPlatformEgressRules.mockReset().mockResolvedValue([
      { ...RULES[1], bankCode: 'ACME', reason: 'Core banking VPN' },
    ]);
    approveEgressRule.mockReset().mockResolvedValue(pendingChange(81, 'EGRESS_RULE_APPROVE'));
  });

  it('lists pending requests and sends an approval for the second platform admin', async () => {
    renderWithProviders(<PlatformEgressRulesPage />, { user: PLATFORM, path: '/platform/egress-rules' });

    expect(await screen.findByText('ACME')).toBeInTheDocument();
    expect(listPlatformEgressRules).toHaveBeenCalledWith('PENDING');
    await userEvent.click(screen.getByRole('button', { name: 'Approve 10.20.0.0/24:22' }));
    const dialog = screen.getByRole('dialog', { name: 'Approve egress destination' });
    expect(within(dialog).getByText(/Core banking VPN/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send for approval' }));

    await waitFor(() => expect(approveEgressRule).toHaveBeenCalledWith(2));
    expect(await screen.findByText('Sent for approval (change #81)')).toBeInTheDocument();
    expect(screen.getByText(/Allowing 10.20.0.0\/24:22 for ACME is change #81/)).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText('Status filter'), 'APPROVED');
    await waitFor(() => expect(listPlatformEgressRules).toHaveBeenLastCalledWith('APPROVED'));
  });
});
