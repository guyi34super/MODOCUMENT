import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
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
    expect(listChanges).toHaveBeenCalledWith('REQUESTED');
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
});
