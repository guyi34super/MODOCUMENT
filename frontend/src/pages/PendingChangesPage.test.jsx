import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';
import PendingChangesPage from './PendingChangesPage';

import { approveChange, cancelChange, listChanges, rejectChange } from '../api/changes';

vi.mock('../api/changes', () => ({
  listChanges: vi.fn(),
  approveChange: vi.fn(),
  rejectChange: vi.fn(),
  cancelChange: vi.fn(),
}));

const ME = { userId: 1, email: 'admin@bank.example', displayName: 'Admin', role: 'ADMIN' };

/** #5 is the viewer's own submission, #6 a colleague's. */
const ROWS = [
  {
    pendingChangeId: 5,
    changeType: 'USER_ROLE_CHANGE',
    targetEntityType: 'AppUser',
    targetEntityId: '7',
    status: 'REQUESTED',
    makerUserId: 1,
    makerEmail: 'admin@bank.example',
    requestedAt: '2026-10-01T09:00:00',
    payload: { targetUserId: 7, targetEmail: 'worker@bank.example', role: 'READ_ONLY' },
  },
  {
    pendingChangeId: 6,
    changeType: 'USER_DISABLE',
    targetEntityType: 'AppUser',
    targetEntityId: '8',
    status: 'REQUESTED',
    makerUserId: 2,
    makerEmail: 'other-admin@bank.example',
    requestedAt: '2026-10-02T10:00:00',
    payload: { targetUserId: 8, targetEmail: 'leaver@bank.example' },
  },
];

function renderPage(user = ME) {
  localStorage.setItem('modoc.token', 'test-token');
  localStorage.setItem('modoc.user', JSON.stringify(user));
  return render(
    <MemoryRouter>
      <AuthProvider>
        <ToastProvider>
          <PendingChangesPage />
        </ToastProvider>
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('PendingChangesPage', () => {
  beforeEach(() => {
    listChanges.mockReset().mockResolvedValue(ROWS);
    approveChange.mockReset().mockResolvedValue({ pendingChangeId: 6, status: 'APPLIED' });
    rejectChange.mockReset().mockResolvedValue({ pendingChangeId: 6, status: 'REJECTED' });
    cancelChange.mockReset().mockResolvedValue({ pendingChangeId: 5, status: 'CANCELLED' });
  });

  it('lists the REQUESTED queue with human summaries and marks own submissions', async () => {
    renderPage();

    expect(
      await screen.findByText('Change worker@bank.example to READ_ONLY')
    ).toBeInTheDocument();
    expect(screen.getByText('Disable leaver@bank.example')).toBeInTheDocument();
    expect(listChanges).toHaveBeenCalledWith('REQUESTED', { page: 0, size: 50 });
    // The viewer's own submission is marked and cancellable; the other not.
    expect(screen.getByText('(yours)')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Cancel request' })).toHaveLength(1);
  });

  it('approves with the typed comment', async () => {
    renderPage();
    await screen.findByText('Disable leaver@bank.example');

    await userEvent.type(
      screen.getByRole('textbox', { name: 'Comment for change 6' }),
      'verified with HR'
    );
    // Row order follows the API; #5 renders first, #6 second.
    await userEvent.click(screen.getAllByRole('button', { name: 'Approve' })[1]);

    await waitFor(() => expect(approveChange).toHaveBeenCalledWith(6, 'verified with HR'));
    expect(await screen.findByText('Change #6 approved and applied')).toBeInTheDocument();
    // The queue reloads after a decision.
    await waitFor(() => expect(listChanges).toHaveBeenCalledTimes(2));
  });

  it('surfaces the self-approval 409 as an error toast', async () => {
    approveChange.mockRejectedValue(
      new Error('Self-approval is blocked: the user who requested a change cannot approve it '
        + '— a different user must.')
    );
    renderPage();
    await screen.findByText('Disable leaver@bank.example');

    await userEvent.click(screen.getAllByRole('button', { name: 'Approve' })[0]);

    await waitFor(() => expect(approveChange).toHaveBeenCalledWith(5, ''));
    const toast = await screen.findByRole('alert');
    expect(toast.textContent).toContain('Self-approval is blocked');
    // Nothing was decided: no reload beyond the initial one.
    expect(listChanges).toHaveBeenCalledTimes(1);
  });

  it('rejects with the typed comment', async () => {
    renderPage();
    await screen.findByText('Disable leaver@bank.example');

    await userEvent.type(
      screen.getByRole('textbox', { name: 'Comment for change 6' }),
      'person is only on leave'
    );
    await userEvent.click(screen.getAllByRole('button', { name: 'Reject' })[1]);

    await waitFor(() => expect(rejectChange).toHaveBeenCalledWith(6, 'person is only on leave'));
  });

  it('lets the maker cancel their own request', async () => {
    renderPage();
    await screen.findByText('(yours)');

    await userEvent.click(screen.getByRole('button', { name: 'Cancel request' }));

    await waitFor(() => expect(cancelChange).toHaveBeenCalledWith(5));
  });

  it('labels and summarizes every phase-2 change type', async () => {
    const row = (id, changeType, payload) => ({
      pendingChangeId: id,
      changeType,
      status: 'REQUESTED',
      makerUserId: 2,
      makerEmail: 'other-admin@bank.example',
      requestedAt: '2026-10-05T10:00:00',
      payload,
    });
    listChanges.mockResolvedValue([
      row(20, 'USER_PASSWORD_RESET', { userId: 7, targetEmail: 'worker@bank.example' }),
      row(21, 'USER_MFA_RESET', {
        userId: 7,
        targetEmail: 'worker@bank.example',
        reason: 'Lost phone',
      }),
      row(22, 'SETTING_UPDATE', {
        changes: [
          { key: 'security.lockout.allowed-attempts', from: 5, to: 3, baseVersion: 1 },
          { key: 'retention.customer-data-days', from: 30, to: null, baseVersion: 4 },
        ],
      }),
      row(23, 'PLATFORM_SETTING_UPDATE', {
        changes: [
          { key: 'ratelimit.uploads', from: 20, to: 40, bankMin: 10, bankMax: 80, baseVersion: 2 },
        ],
      }),
      row(24, 'BANK_CREATE', {
        code: 'ACME',
        name: 'Acme Bank',
        users: [
          { email: 'a@acme.example', role: 'ADMIN' },
          { email: 'b@acme.example', role: 'APPROVER' },
        ],
      }),
      row(25, 'BANK_SUSPEND', { bankId: 9, bankCode: 'ACME', reason: 'Contract ended' }),
      row(26, 'BANK_ADMIN_ADD', { bankId: 9, email: 'c@acme.example', reason: 'Break-glass' }),
      row(27, 'INTEGRATION_CONNECTION_CHANGE', {
        name: 'Core banking DB',
        type: 'DATABASE',
        mode: 'AGENT',
        secretRef: 'vault:1',
      }),
      row(28, 'INTEGRATION_FLOW_ENABLE', { flowId: 3, flowVersion: 2 }),
      row(29, 'API_CLIENT_CREATE', {
        name: 'Loans system',
        authMethod: 'OAUTH2_CLIENT_CREDENTIALS',
        scopes: ['integration:ingest', 'integration:results'],
      }),
      row(30, 'AGENT_REGISTER', { name: 'dc1-agent' }),
      row(31, 'EGRESS_RULE_APPROVE', { ruleId: 12 }),
    ]);
    renderPage();

    expect(await screen.findByText('Password reset for worker@bank.example')).toBeInTheDocument();
    expect(screen.getByText('#20 · Reset password')).toBeInTheDocument();
    expect(screen.getByText('MFA reset for worker@bank.example — Lost phone')).toBeInTheDocument();
    expect(screen.getByText('#21 · Reset MFA')).toBeInTheDocument();

    // Settings changes list one `key: from → to` line each (null = default).
    expect(screen.getByText('#22 · Change settings')).toBeInTheDocument();
    expect(screen.getByText('Change 2 settings')).toBeInTheDocument();
    const diffs = screen.getByRole('list', { name: 'Changes in #22' });
    expect(within(diffs).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'security.lockout.allowed-attempts: 5 → 3',
      'retention.customer-data-days: 30 → default',
    ]);
    expect(screen.getByText('#23 · Change platform settings')).toBeInTheDocument();
    expect(
      screen.getByText('ratelimit.uploads: 20 → 40 (bank range 10–80)')
    ).toBeInTheDocument();

    expect(screen.getByText('#24 · Onboard bank')).toBeInTheDocument();
    expect(
      screen.getByText('Onboard bank ACME "Acme Bank" with 2 users (1 administrator)')
    ).toBeInTheDocument();
    expect(screen.getByText('#25 · Suspend bank')).toBeInTheDocument();
    expect(screen.getByText('Suspend ACME — Contract ended')).toBeInTheDocument();
    expect(screen.getByText('#26 · Add bank administrator')).toBeInTheDocument();
    expect(
      screen.getByText('Add c@acme.example as administrator of bank 9 — Break-glass')
    ).toBeInTheDocument();
    expect(screen.getByText('#27 · Change integration connection')).toBeInTheDocument();
    expect(
      screen.getByText('Create connection "Core banking DB" (DATABASE, AGENT, new credentials)')
    ).toBeInTheDocument();
    expect(screen.getByText('#28 · Enable integration flow')).toBeInTheDocument();
    expect(screen.getByText('Enable flow #3 (version 2)')).toBeInTheDocument();
    expect(screen.getByText('#29 · Create API client')).toBeInTheDocument();
    expect(
      screen.getByText(
        'Create API client "Loans system" (OAUTH2_CLIENT_CREDENTIALS) with scopes ' +
          'integration:ingest, integration:results'
      )
    ).toBeInTheDocument();
    expect(screen.getByText('#30 · Register connector agent')).toBeInTheDocument();
    expect(screen.getByText('Register connector agent "dc1-agent"')).toBeInTheDocument();
    expect(screen.getByText('#31 · Approve egress destination')).toBeInTheDocument();
    expect(screen.getByText('Approve egress rule #12')).toBeInTheDocument();
  });

  it('names the destination, allowlist and template extraction mode for the checker', async () => {
    const row = (id, changeType, payload) => ({
      pendingChangeId: id,
      changeType,
      status: 'REQUESTED',
      makerUserId: 2,
      requestedAt: '2026-10-05T10:00:00',
      payload,
    });
    listChanges.mockResolvedValue([
      row(50, 'INTEGRATION_CONNECTION_CHANGE', {
        connectionId: 7,
        name: 'Core DB',
        type: 'DATABASE',
        mode: 'DIRECT',
        config: { host: 'db.bank.example', port: '5432' },
        secretCiphertext: 'AAAA',
      }),
      row(51, 'API_CLIENT_CREATE', {
        name: 'Treasury',
        authMethod: 'API_KEY',
        scopes: ['INTEGRATION_EVENTS'],
        allowedCidrs: ['10.0.0.0/8'],
      }),
      row(52, 'TEMPLATE_UPDATE', { templateId: 3, name: 'Loans', extractionMode: 'CODE_ONLY' }),
      row(53, 'TEMPLATE_UPDATE', { templateId: 4, name: 'Cards', extractionMode: null }),
      row(54, 'PLATFORM_SETTING_UPDATE', {
        bankId: 9,
        changes: [{ key: 'integration.auth.allowApiKeys', from: null, to: false, baseVersion: 0 }],
      }),
    ]);
    renderPage();

    expect(
      await screen.findByText(
        'Change connection "Core DB" (DATABASE, DIRECT, new credentials) to db.bank.example:5432'
      )
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        'Create API client "Treasury" (API_KEY) with scopes INTEGRATION_EVENTS, from 10.0.0.0/8'
      )
    ).toBeInTheDocument();
    expect(
      screen.getByText('Rename template 3 to "Loans"; extraction mode → code only')
    ).toBeInTheDocument();
    expect(
      screen.getByText('Rename template 4 to "Cards"; extraction mode → the bank default')
    ).toBeInTheDocument();
    expect(screen.getByText('Change 1 platform-set setting of bank 9')).toBeInTheDocument();
    // The sealed secret itself is never rendered.
    expect(document.body.textContent).not.toContain('AAAA');
  });

  it('lets a platform admin decide platform changes', async () => {
    listChanges.mockResolvedValue([
      {
        pendingChangeId: 40,
        changeType: 'BANK_SUSPEND',
        status: 'REQUESTED',
        makerUserId: 2,
        makerEmail: 'owner@moi.example',
        requestedAt: '2026-10-05T10:00:00',
        payload: { bankId: 9, bankName: 'Acme Bank', reason: 'Unpaid' },
      },
    ]);
    renderPage({ userId: 3, email: 'second-owner@moi.example', role: 'PLATFORM_ADMIN' });

    expect(await screen.findByText('Suspend Acme Bank — Unpaid')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument();
  });

  it('hides the decision controls from makers without approval rights', async () => {
    // A READ_WRITE template maker sees their own queue, but cannot decide.
    listChanges.mockResolvedValue([
      {
        pendingChangeId: 9,
        changeType: 'TEMPLATE_UPDATE',
        status: 'REQUESTED',
        makerUserId: 4,
        makerEmail: 'writer@bank.example',
        requestedAt: '2026-10-03T08:00:00',
        payload: { templateId: 3, name: 'New Name' },
      },
    ]);
    renderPage({ userId: 4, email: 'writer@bank.example', role: 'READ_WRITE' });

    expect(await screen.findByText('Rename template 3 to "New Name"')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel request' })).toBeInTheDocument();
  });

  it('pages the queue (v1.2.0 envelope) and starts over on a new status', async () => {
    listChanges.mockImplementation((status, { page }) =>
      Promise.resolve({ items: page === 0 ? [ROWS[0]] : [ROWS[1]], page, size: 50, total: 70 })
    );
    renderPage();

    expect(await screen.findByText('Change worker@bank.example to READ_ONLY')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Changes pages' })).toHaveTextContent('1–50 of 70');
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(await screen.findByText('Disable leaver@bank.example')).toBeInTheDocument();
    expect(listChanges).toHaveBeenLastCalledWith('REQUESTED', { page: 1, size: 50 });

    await userEvent.selectOptions(screen.getByLabelText('Status filter'), 'APPLIED');
    await waitFor(() => expect(listChanges).toHaveBeenLastCalledWith('APPLIED', { page: 0, size: 50 }));
  });
});
