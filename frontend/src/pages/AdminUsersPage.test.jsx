import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';
import AdminUsersPage from './AdminUsersPage';

import {
  accessReview,
  createUser,
  exportUsers,
  listUsers,
  resetUserMfa,
  resetUserPassword,
  setUserDisabled,
  setUserRole,
  unlockUser,
  updateUser,
} from '../api/admin';

vi.mock('../api/admin', () => ({
  listUsers: vi.fn(),
  createUser: vi.fn(),
  setUserRole: vi.fn(),
  setUserDisabled: vi.fn(),
  accessReview: vi.fn(),
  exportUsers: vi.fn(),
  unlockUser: vi.fn(),
  resetUserPassword: vi.fn(),
  resetUserMfa: vi.fn(),
  updateUser: vi.fn(),
}));

const ME = { userId: 1, email: 'admin@bank.example', displayName: 'Admin', role: 'ADMIN' };

const ADMIN_ROW = {
  id: 1,
  email: 'admin@bank.example',
  displayName: 'Admin',
  role: 'ADMIN',
  disabled: false,
  lastLoginAt: '2026-10-01T09:00:00',
  mfaEnabled: true,
  authProvider: null,
  locked: false,
  lockedUntil: null,
  mustChangePassword: false,
};

const WORKER_ROW = {
  id: 2,
  email: 'worker@bank.example',
  displayName: 'Worker',
  role: 'READ_WRITE',
  disabled: false,
  lastLoginAt: null,
  mfaEnabled: false,
  authProvider: null,
  locked: false,
  lockedUntil: null,
  mustChangePassword: false,
};

const ROWS = [ADMIN_ROW, WORKER_ROW];

function renderPage() {
  localStorage.setItem('modoc.token', 'test-token');
  localStorage.setItem('modoc.user', JSON.stringify(ME));
  return render(
    <MemoryRouter>
      <AuthProvider>
        <ToastProvider>
          <AdminUsersPage />
        </ToastProvider>
      </AuthProvider>
    </MemoryRouter>
  );
}

/** What the ws10 backend answers with 202: a pending change, not the result. */
const pending = (id, changeType) => ({
  pendingChangeId: id,
  changeType,
  status: 'REQUESTED',
});

describe('AdminUsersPage', () => {
  beforeEach(() => {
    listUsers.mockReset().mockResolvedValue(ROWS);
    createUser.mockReset().mockResolvedValue(pending(11, 'USER_CREATE'));
    setUserRole.mockReset().mockResolvedValue(pending(12, 'USER_ROLE_CHANGE'));
    setUserDisabled.mockReset().mockResolvedValue(pending(13, 'USER_DISABLE'));
    accessReview.mockReset().mockResolvedValue([]);
    exportUsers.mockReset().mockResolvedValue(undefined);
    unlockUser.mockReset().mockResolvedValue({ ...WORKER_ROW, locked: false });
    resetUserPassword.mockReset().mockResolvedValue(pending(14, 'USER_PASSWORD_RESET'));
    resetUserMfa.mockReset().mockResolvedValue(pending(15, 'USER_MFA_RESET'));
    updateUser.mockReset().mockResolvedValue({ ...WORKER_ROW, displayName: 'Worker Bee' });
  });

  /** ws21: the platform bank hands out PLATFORM_ADMIN only (the server enforces it too). */
  it('offers a PLATFORM_ADMIN only the PLATFORM_ADMIN role', async () => {
    const platformMe = { ...ME, role: 'PLATFORM_ADMIN' };
    listUsers.mockResolvedValue([
      { ...ADMIN_ROW, role: 'PLATFORM_ADMIN' },
      { ...WORKER_ROW, role: 'PLATFORM_ADMIN' },
    ]);
    localStorage.setItem('modoc.token', 'test-token');
    localStorage.setItem('modoc.user', JSON.stringify(platformMe));
    render(
      <MemoryRouter>
        <AuthProvider>
          <ToastProvider>
            <AdminUsersPage />
          </ToastProvider>
        </AuthProvider>
      </MemoryRouter>
    );

    const rowPicker = await screen.findByRole('combobox', { name: 'Role of worker@bank.example' });
    expect(within(rowPicker).getAllByRole('option').map((o) => o.value)).toEqual([
      'PLATFORM_ADMIN',
    ]);
    await userEvent.click(screen.getByRole('button', { name: 'Create user' }));
    const createPicker = screen.getByLabelText(/Least privilege/);
    expect(createPicker).toHaveValue('PLATFORM_ADMIN');
    expect(within(createPicker).getAllByRole('option').map((o) => o.value)).toEqual([
      'PLATFORM_ADMIN',
    ]);
  });

  it('renders every user of the bank with role, status and MFA state', async () => {
    renderPage();

    expect(await screen.findByText('worker@bank.example')).toBeInTheDocument();
    expect(screen.getByText('admin@bank.example')).toBeInTheDocument();
    // The caller's own row is marked and carries no self-footgun controls.
    expect(screen.getByText('(you)')).toBeInTheDocument();
    expect(screen.getAllByText('Active')).toHaveLength(2);
    expect(screen.getByText('On')).toBeInTheDocument();
    expect(
      screen.queryByRole('combobox', { name: 'Role of admin@bank.example' })
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Edit admin@bank.example' })
    ).not.toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Role of worker@bank.example' })).toHaveValue(
      'READ_WRITE'
    );
  });

  it('creates a user with the exact payload', async () => {
    renderPage();
    await screen.findByText('worker@bank.example');

    await userEvent.click(screen.getByRole('button', { name: 'Create user' }));
    await userEvent.type(screen.getByLabelText(/Email/), 'new@bank.example');
    await userEvent.type(screen.getByLabelText(/Display name/), 'Newcomer');
    // The form's Role field (its label carries the least-privilege hint),
    // not one of the per-row role dropdowns.
    await userEvent.selectOptions(
      screen.getByRole('combobox', { name: /Least privilege/ }),
      'READ_ONLY'
    );
    await userEvent.type(screen.getByLabelText(/Temporary password/), 'Temp-Rotor-91x!');
    // With the panel open the header button reads "Close", so the form's
    // submit is the only "Create user" button left.
    await userEvent.click(screen.getByRole('button', { name: 'Create user' }));

    await waitFor(() =>
      expect(createUser).toHaveBeenCalledWith({
        email: 'new@bank.example',
        displayName: 'Newcomer',
        role: 'READ_ONLY',
        temporaryPassword: 'Temp-Rotor-91x!',
      })
    );
    // ws10: the 202 answer is a PENDING change — the page says so instead of
    // pretending the user exists.
    expect(
      await screen.findByText(/submitted for approval.*change #11/)
    ).toBeInTheDocument();
    // The list reloads after the submission.
    await waitFor(() => expect(listUsers).toHaveBeenCalledTimes(2));
  });

  it('disables a user only after the confirm dialog agrees', async () => {
    renderPage();
    await screen.findByText('worker@bank.example');

    await userEvent.click(screen.getByRole('button', { name: 'Disable worker@bank.example' }));
    const dialog = screen.getByRole('dialog', { name: 'Disable user' });
    expect(dialog).toHaveTextContent('worker@bank.example');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(setUserDisabled).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: 'Disable worker@bank.example' }));
    await userEvent.click(screen.getByRole('button', { name: 'Request disable' }));
    await waitFor(() => expect(setUserDisabled).toHaveBeenCalledWith(2, true));
    // ws10: the disable is pending a second administrator's approval.
    expect(
      await screen.findByText(/submitted for approval \(change #13\)/)
    ).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('changes a role through a confirm that mentions the session revocation', async () => {
    renderPage();
    await screen.findByText('worker@bank.example');

    await userEvent.selectOptions(
      screen.getByRole('combobox', { name: 'Role of worker@bank.example' }),
      'APPROVER'
    );

    const dialog = screen.getByRole('dialog', { name: 'Change role' });
    expect(dialog).toHaveTextContent('signed out');
    expect(dialog).toHaveTextContent('from READ_WRITE to APPROVER');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Request change' }));
    await waitFor(() => expect(setUserRole).toHaveBeenCalledWith(2, 'APPROVER'));
    expect(
      await screen.findByText(/Role change for worker@bank.example submitted for approval/)
    ).toBeInTheDocument();
  });

  // ------------------------------------------------ phase 2 (ws22) actions

  it('unlocks a locked account through a confirmation', async () => {
    listUsers.mockResolvedValue([
      ADMIN_ROW,
      { ...WORKER_ROW, locked: true, lockedUntil: '2026-10-10T12:00:00' },
    ]);
    renderPage();
    await screen.findByText('worker@bank.example');
    expect(screen.getByText('Locked')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Unlock worker@bank.example' }));
    const dialog = screen.getByRole('dialog', { name: 'Unlock account' });
    expect(dialog).toHaveTextContent('The password does not change');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Unlock' }));

    await waitFor(() => expect(unlockUser).toHaveBeenCalledWith(2));
    expect(await screen.findByText(/worker@bank.example unlocked/)).toBeInTheDocument();
    await waitFor(() => expect(listUsers).toHaveBeenCalledTimes(2));
  });

  it('offers Unlock only for locked accounts and Reset MFA only when MFA is on', async () => {
    renderPage();
    await screen.findByText('worker@bank.example');

    expect(
      screen.queryByRole('button', { name: 'Unlock worker@bank.example' })
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Reset MFA of worker@bank.example' })
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Reset password of worker@bank.example' })
    ).toBeInTheDocument();
  });

  it('requests a password reset with a generated, copyable temporary password', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('worker@bank.example');

    await user.click(screen.getByRole('button', { name: 'Reset password of worker@bank.example' }));
    const dialog = screen.getByRole('dialog', { name: 'Reset password' });
    expect(dialog).toHaveAccessibleDescription('worker@bank.example');
    const field = within(dialog).getByLabelText('Temporary password');
    expect(field).toHaveFocus();
    expect(within(dialog).getByRole('button', { name: 'Request reset' })).toBeDisabled();

    await user.click(within(dialog).getByRole('button', { name: 'Generate' }));
    const generated = field.value;
    expect(generated).toMatch(/^[A-Za-z2-9]{4}(-[A-Za-z2-9]{4}){3}$/);

    await user.click(within(dialog).getByRole('button', { name: 'Copy' }));
    expect(await navigator.clipboard.readText()).toBe(generated);
    expect(await screen.findByText('Temporary password copied')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Request reset' }));
    await waitFor(() => expect(resetUserPassword).toHaveBeenCalledWith(2, generated));
    // 202: the reset is pending a second administrator's approval.
    expect(
      await screen.findByText(
        'Sent for approval: password reset for worker@bank.example (change #14)'
      )
    ).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('keeps the password dialog open when the server refuses the password', async () => {
    resetUserPassword.mockRejectedValue(new Error('Password must be at least 12 characters.'));
    renderPage();
    await screen.findByText('worker@bank.example');

    await userEvent.click(
      screen.getByRole('button', { name: 'Reset password of worker@bank.example' })
    );
    await userEvent.type(screen.getByLabelText('Temporary password'), 'too-short');
    await userEvent.click(screen.getByRole('button', { name: 'Request reset' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('at least 12 characters');
    expect(screen.getByRole('dialog', { name: 'Reset password' })).toBeInTheDocument();
  });

  it('never offers a password reset for an SSO account', async () => {
    listUsers.mockResolvedValue([ADMIN_ROW, { ...WORKER_ROW, authProvider: 'entra' }]);
    renderPage();
    await screen.findByText('worker@bank.example');

    expect(
      screen.queryByRole('button', { name: 'Reset password of worker@bank.example' })
    ).not.toBeInTheDocument();
  });

  it('requests an MFA reset with a mandatory reason', async () => {
    listUsers.mockResolvedValue([ADMIN_ROW, { ...WORKER_ROW, mfaEnabled: true }]);
    renderPage();
    await screen.findByText('worker@bank.example');

    await userEvent.click(screen.getByRole('button', { name: 'Reset MFA of worker@bank.example' }));
    const dialog = screen.getByRole('dialog', { name: 'Reset MFA' });
    const submit = within(dialog).getByRole('button', { name: 'Request MFA reset' });
    expect(submit).toBeDisabled();

    await userEvent.type(within(dialog).getByLabelText(/Reason/), 'Lost phone, ticket HD-1234');
    await userEvent.click(submit);

    await waitFor(() =>
      expect(resetUserMfa).toHaveBeenCalledWith(2, 'Lost phone, ticket HD-1234')
    );
    expect(
      await screen.findByText('Sent for approval: MFA reset for worker@bank.example (change #15)')
    ).toBeInTheDocument();
  });

  it('edits the display name directly and leaves the email alone', async () => {
    renderPage();
    await screen.findByText('worker@bank.example');

    await userEvent.click(screen.getByRole('button', { name: 'Edit worker@bank.example' }));
    const dialog = screen.getByRole('dialog', { name: 'Edit user' });
    const name = within(dialog).getByLabelText('Display name');
    expect(name).toHaveValue('Worker');
    expect(dialog).toHaveTextContent('cannot be changed');

    await userEvent.clear(name);
    await userEvent.type(name, 'Worker Bee');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(updateUser).toHaveBeenCalledWith(2, { displayName: 'Worker Bee' })
    );
    expect(
      await screen.findByText('Display name of worker@bank.example updated')
    ).toBeInTheDocument();
    await waitFor(() => expect(listUsers).toHaveBeenCalledTimes(2));
  });

  it('closes a dialog on Escape without calling the server', async () => {
    renderPage();
    await screen.findByText('worker@bank.example');
    const opener = screen.getByRole('button', { name: 'Edit worker@bank.example' });

    await userEvent.click(opener);
    expect(screen.getByRole('dialog', { name: 'Edit user' })).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
    expect(updateUser).not.toHaveBeenCalled();
  });

  it('exports the CSV through the download helper', async () => {
    renderPage();
    await screen.findByText('worker@bank.example');

    await userEvent.click(screen.getByRole('button', { name: 'Export CSV' }));

    await waitFor(() => expect(exportUsers).toHaveBeenCalledTimes(1));
  });

  it('shows the access-review worksheet with inactive accounts flagged', async () => {
    accessReview.mockResolvedValue([
      {
        id: 2,
        email: 'worker@bank.example',
        role: 'READ_WRITE',
        disabled: false,
        mfaEnabled: false,
        lastLoginAt: '2026-05-01T09:00:00',
        daysSinceLastLogin: 156,
        inactive90d: true,
      },
    ]);
    renderPage();
    await screen.findByText('worker@bank.example');

    await userEvent.click(screen.getByRole('button', { name: 'Access review' }));

    // Once in the explainer, once as the row's badge.
    expect(await screen.findAllByText('inactive 90d+')).toHaveLength(2);
    expect(screen.getByText('156')).toBeInTheDocument();
    expect(accessReview).toHaveBeenCalledTimes(1);
  });
});
