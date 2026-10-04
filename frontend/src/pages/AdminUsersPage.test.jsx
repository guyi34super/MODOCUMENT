import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
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
  setUserDisabled,
  setUserRole,
} from '../api/admin';

vi.mock('../api/admin', () => ({
  listUsers: vi.fn(),
  createUser: vi.fn(),
  setUserRole: vi.fn(),
  setUserDisabled: vi.fn(),
  accessReview: vi.fn(),
  exportUsers: vi.fn(),
}));

const ME = { userId: 1, email: 'admin@bank.example', displayName: 'Admin', role: 'ADMIN' };

const ROWS = [
  {
    id: 1,
    email: 'admin@bank.example',
    displayName: 'Admin',
    role: 'ADMIN',
    disabled: false,
    lastLoginAt: '2026-10-01T09:00:00',
    mfaEnabled: true,
    authProvider: null,
  },
  {
    id: 2,
    email: 'worker@bank.example',
    displayName: 'Worker',
    role: 'READ_WRITE',
    disabled: false,
    lastLoginAt: null,
    mfaEnabled: false,
    authProvider: null,
  },
];

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

describe('AdminUsersPage', () => {
  beforeEach(() => {
    listUsers.mockReset().mockResolvedValue(ROWS);
    createUser.mockReset().mockResolvedValue({});
    setUserRole.mockReset().mockResolvedValue({});
    setUserDisabled.mockReset().mockResolvedValue({});
    accessReview.mockReset().mockResolvedValue([]);
    exportUsers.mockReset().mockResolvedValue(undefined);
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
    // The list reloads after creation.
    await waitFor(() => expect(listUsers).toHaveBeenCalledTimes(2));
  });

  it('disables a user only after the confirm dialog agrees', async () => {
    vi.spyOn(window, 'confirm').mockReturnValueOnce(false);
    renderPage();
    await screen.findByText('worker@bank.example');

    await userEvent.click(screen.getByRole('button', { name: 'Disable' }));
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('worker@bank.example'));
    expect(setUserDisabled).not.toHaveBeenCalled();

    vi.spyOn(window, 'confirm').mockReturnValueOnce(true);
    await userEvent.click(screen.getByRole('button', { name: 'Disable' }));
    await waitFor(() => expect(setUserDisabled).toHaveBeenCalledWith(2, true));
  });

  it('changes a role through a confirm that mentions the session revocation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderPage();
    await screen.findByText('worker@bank.example');

    await userEvent.selectOptions(
      screen.getByRole('combobox', { name: 'Role of worker@bank.example' }),
      'APPROVER'
    );

    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('signed out'));
    await waitFor(() => expect(setUserRole).toHaveBeenCalledWith(2, 'APPROVER'));
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
