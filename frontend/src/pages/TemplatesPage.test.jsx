import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';
import TemplatesPage from './TemplatesPage';

import { getTemplates } from '../api/cache';
import { setTemplateVisibility } from '../api/templates';

vi.mock('../api/cache', () => ({
  getTemplates: vi.fn(),
  invalidateTemplates: vi.fn(),
}));
vi.mock('../api/templates', () => ({
  deleteTemplate: vi.fn(),
  setTemplateVisibility: vi.fn(),
}));

const MINE = {
  templateId: 1,
  name: 'Invoices',
  description: 'mine',
  visibility: 'PRIVATE',
  ownedByMe: true,
  editable: true,
  ownerName: null,
};
const SHARED_BY_COLLEAGUE = {
  templateId: 2,
  name: 'KYC form',
  description: 'bank-wide',
  visibility: 'BANK',
  ownedByMe: false,
  editable: false,
  ownerName: 'Grace Admin',
};

function renderAs(role) {
  localStorage.setItem('modoc.token', 'test-token');
  localStorage.setItem('modoc.user', JSON.stringify({ userId: 5, email: 'me@bank.example', role }));
  return render(
    <MemoryRouter>
      <AuthProvider>
        <ToastProvider>
          <TemplatesPage />
        </ToastProvider>
      </AuthProvider>
    </MemoryRouter>
  );
}

const rowOf = (name) => screen.getByText(name).closest('tr');

describe('TemplatesPage sharing', () => {
  beforeEach(() => {
    getTemplates.mockReset().mockResolvedValue([MINE, SHARED_BY_COLLEAGUE]);
    setTemplateVisibility.mockReset();
  });

  it('badges private and shared templates and names the owner of a shared one', async () => {
    renderAs('READ_WRITE');
    await screen.findByText('Invoices');

    expect(within(rowOf('Invoices')).getByText('Private')).toBeInTheDocument();
    expect(within(rowOf('Invoices')).getByText('You')).toBeInTheDocument();
    expect(within(rowOf('KYC form')).getByText('Shared')).toBeInTheDocument();
    expect(within(rowOf('KYC form')).getByText('Grace Admin')).toBeInTheDocument();
  });

  it('gives a non-administrator no share switch and no Delete on a colleague\'s shared template', async () => {
    renderAs('READ_WRITE');
    await screen.findByText('Invoices');

    expect(screen.queryByRole('switch', { name: 'Share with bank' })).toBeNull();
    expect(within(rowOf('Invoices')).getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    expect(within(rowOf('KYC form')).queryByRole('button', { name: 'Delete' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'New shared template' })).toBeNull();
  });

  it('lets an administrator share a template with the bank', async () => {
    getTemplates
      .mockResolvedValueOnce([MINE, { ...SHARED_BY_COLLEAGUE, editable: true }])
      .mockResolvedValueOnce([
        { ...MINE, visibility: 'BANK' },
        { ...SHARED_BY_COLLEAGUE, editable: true },
      ]);
    setTemplateVisibility.mockResolvedValue({ ...MINE, visibility: 'BANK' });
    renderAs('ADMIN');
    await screen.findByText('Invoices');

    const toggle = within(rowOf('Invoices')).getByRole('switch', { name: 'Share with bank' });
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    await userEvent.click(toggle);

    await waitFor(() => expect(setTemplateVisibility).toHaveBeenCalledWith(1, 'BANK'));
    expect(
      await screen.findByText('"Invoices" is now shared with everyone in your bank')
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(
        within(rowOf('Invoices')).getByRole('switch', { name: 'Share with bank' })
      ).toHaveAttribute('aria-checked', 'true')
    );
  });

  it('shows the refusal when unsharing a template still in use', async () => {
    getTemplates.mockResolvedValue([{ ...SHARED_BY_COLLEAGUE, editable: true }]);
    setTemplateVisibility.mockRejectedValue(
      new Error('This shared template is in use by 1 unfinished upload job of other users.')
    );
    renderAs('ADMIN');
    await screen.findByText('KYC form');

    await userEvent.click(screen.getByRole('switch', { name: 'Share with bank' }));

    await waitFor(() => expect(setTemplateVisibility).toHaveBeenCalledWith(2, 'PRIVATE'));
    expect(await screen.findByText(/in use by 1 unfinished upload job/)).toBeInTheDocument();
  });
});
