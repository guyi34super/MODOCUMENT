import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ToastProvider } from '../context/ToastContext';
import PlatformBanksPage from './PlatformBanksPage';
import { listBanks, onboardBank } from '../api/platform';

vi.mock('../api/platform', () => ({ listBanks: vi.fn(), onboardBank: vi.fn() }));

const BANKS = [
  {
    bankId: 1,
    code: 'DEFAULT',
    name: 'Default Bank',
    kind: 'CUSTOMER',
    status: 'ACTIVE',
    users: 4,
    mfaEnrolledPct: 50,
    jobs7d: 3,
    failures7d: 0,
    lastActivityAt: null,
  },
  {
    bankId: 2,
    code: 'MOI-PLATFORM',
    name: 'MO Intelligence Platform',
    kind: 'PLATFORM',
    status: 'ACTIVE',
    users: 2,
    mfaEnrolledPct: 100,
    jobs7d: 0,
    failures7d: 0,
    lastActivityAt: null,
  },
];

const PENDING = { pendingChangeId: 31, changeType: 'BANK_CREATE', status: 'REQUESTED' };

function renderPage() {
  return render(
    <MemoryRouter>
      <ToastProvider>
        <PlatformBanksPage />
      </ToastProvider>
    </MemoryRouter>
  );
}

async function openOnboarding() {
  await userEvent.click(await screen.findByRole('button', { name: 'Onboard bank' }));
  return screen.getByRole('dialog', { name: 'Onboard bank' });
}

async function fillValidForm(dialog) {
  await userEvent.type(within(dialog).getByLabelText(/^Code/), 'acme');
  await userEvent.type(within(dialog).getByLabelText('Name'), 'Acme Bank');
  await userEvent.type(within(dialog).getByLabelText('Email of user 1'), 'ana@acme.example');
  await userEvent.type(within(dialog).getByLabelText('Display name of user 1'), 'Ana');
  await userEvent.type(
    within(dialog).getByLabelText('Temporary password of user 1'),
    'Temp-Password-Ana-1!'
  );
  await userEvent.type(within(dialog).getByLabelText('Email of user 2'), 'bo@acme.example');
  await userEvent.type(
    within(dialog).getByLabelText('Temporary password of user 2'),
    'Temp-Password-Bo-22!'
  );
}

describe('PlatformBanksPage', () => {
  beforeEach(() => {
    listBanks.mockReset().mockResolvedValue(BANKS);
    onboardBank.mockReset().mockResolvedValue(PENDING);
  });

  it('lists every bank, the platform bank marked as such', async () => {
    renderPage();

    const table = await screen.findByRole('table', { name: 'Banks' });
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(within(rows[1]).getByRole('link', { name: 'DEFAULT' })).toHaveAttribute(
      'href',
      '/platform/banks/1'
    );
    expect(within(rows[1]).getByText('Customer')).toBeInTheDocument();
    expect(within(rows[2]).getByText('Platform')).toBeInTheDocument();
  });

  it('opens the onboarding dialog with two administrator rows', async () => {
    renderPage();
    const dialog = await openOnboarding();

    expect(within(dialog).getAllByRole('listitem')).toHaveLength(2);
    expect(within(dialog).getByLabelText('Role of user 1')).toHaveValue('ADMIN');
    expect(within(dialog).getByLabelText('Role of user 2')).toHaveValue('ADMIN');
    // A platform role is never offered for a customer bank.
    expect(
      within(within(dialog).getByLabelText('Role of user 1')).queryByRole('option', {
        name: 'PLATFORM_ADMIN',
      })
    ).not.toBeInTheDocument();
    // Two rows is the minimum: nothing to remove yet.
    expect(within(dialog).queryByRole('button', { name: /Remove user/ })).not.toBeInTheDocument();
  });

  it('generates a temporary password on request', async () => {
    renderPage();
    const dialog = await openOnboarding();

    await userEvent.click(
      within(dialog).getByRole('button', { name: 'Generate temporary password of user 1' })
    );

    expect(within(dialog).getByLabelText('Temporary password of user 1').value).toMatch(
      /^[A-Za-z2-9]{4}-[A-Za-z2-9]{4}-[A-Za-z2-9]{4}-[A-Za-z2-9]{4}$/
    );
  });

  it('submits the onboarding and says it was sent for approval', async () => {
    renderPage();
    const dialog = await openOnboarding();
    await fillValidForm(dialog);

    await userEvent.click(within(dialog).getByRole('button', { name: 'Send for approval' }));

    await waitFor(() => expect(onboardBank).toHaveBeenCalledTimes(1));
    expect(onboardBank).toHaveBeenCalledWith({
      code: 'acme',
      name: 'Acme Bank',
      users: [
        {
          email: 'ana@acme.example',
          displayName: 'Ana',
          role: 'ADMIN',
          temporaryPassword: 'Temp-Password-Ana-1!',
        },
        {
          email: 'bo@acme.example',
          displayName: '',
          role: 'ADMIN',
          temporaryPassword: 'Temp-Password-Bo-22!',
        },
      ],
    });
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Onboard bank' })).not.toBeInTheDocument()
    );
    expect(screen.getAllByText('Sent for approval').length).toBeGreaterThan(0);
    expect(screen.getByText(/is change #31/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Approvals' })).toHaveAttribute(
      'href',
      '/admin/changes'
    );
  });

  it('refuses a team that could never approve its own changes, before calling the server', async () => {
    renderPage();
    const dialog = await openOnboarding();
    await fillValidForm(dialog);
    await userEvent.selectOptions(within(dialog).getByLabelText('Role of user 2'), 'READ_ONLY');

    await userEvent.click(within(dialog).getByRole('button', { name: 'Send for approval' }));

    expect(within(dialog).getByRole('alert')).toHaveTextContent(
      'At least two first users must be ADMIN or APPROVER'
    );
    expect(onboardBank).not.toHaveBeenCalled();
  });

  it('adds and removes extra user rows', async () => {
    renderPage();
    const dialog = await openOnboarding();

    await userEvent.click(within(dialog).getByRole('button', { name: 'Add user' }));
    expect(within(dialog).getAllByRole('listitem')).toHaveLength(3);
    expect(within(dialog).getByLabelText('Role of user 3')).toHaveValue('READ_ONLY');

    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove user 3' }));
    expect(within(dialog).getAllByRole('listitem')).toHaveLength(2);
  });

  it('keeps the dialog open when the server refuses', async () => {
    onboardBank.mockRejectedValue(new Error('A bank with the code ACME already exists.'));
    renderPage();
    const dialog = await openOnboarding();
    await fillValidForm(dialog);

    await userEvent.click(within(dialog).getByRole('button', { name: 'Send for approval' }));

    expect(await screen.findByText('A bank with the code ACME already exists.')).toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: 'Onboard bank' })).toBeInTheDocument();
    expect(screen.queryByText(/is change #/)).not.toBeInTheDocument();
  });
});
