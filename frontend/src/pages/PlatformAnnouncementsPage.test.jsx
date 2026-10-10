import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';
import PlatformAnnouncementsPage, { fromLocalInput, toLocalInput, toRequest } from './PlatformAnnouncementsPage';

import {
  archiveAnnouncement,
  createAnnouncement,
  listAudienceBanks,
  listPlatformAnnouncements,
  updateAnnouncement,
} from '../api/announcements';

vi.mock('../api/announcements', () => ({
  listPlatformAnnouncements: vi.fn(),
  listAudienceBanks: vi.fn(),
  createAnnouncement: vi.fn(),
  updateAnnouncement: vi.fn(),
  archiveAnnouncement: vi.fn(),
}));

const ME = { userId: 7, email: 'owner@moi.example', displayName: 'Owner', role: 'PLATFORM_ADMIN' };

const ROWS = [
  {
    id: 3,
    kind: 'MAINTENANCE',
    severity: 'CRITICAL',
    title: 'Sunday maintenance',
    body: 'Down for an hour.',
    audienceBankCodes: null,
    startsAt: '2026-10-10T08:00:00Z',
    endsAt: '2026-10-12T08:00:00Z',
    maintenanceStartsAt: '2026-10-11T22:00:00Z',
    maintenanceEndsAt: '2026-10-11T23:00:00Z',
    releaseVersion: null,
    archived: false,
    status: 'ACTIVE',
  },
  {
    id: 2,
    kind: 'INFO',
    severity: 'INFO',
    title: 'Only for bank A',
    body: '',
    audienceBankCodes: ['BANK-A'],
    startsAt: '2026-10-01T08:00:00Z',
    endsAt: '2026-10-02T08:00:00Z',
    maintenanceStartsAt: null,
    maintenanceEndsAt: null,
    releaseVersion: null,
    archived: true,
    status: 'ARCHIVED',
  },
];

function renderPage() {
  localStorage.setItem('modoc.token', 'test-token');
  localStorage.setItem('modoc.user', JSON.stringify(ME));
  return render(
    <MemoryRouter>
      <AuthProvider>
        <ToastProvider>
          <PlatformAnnouncementsPage />
        </ToastProvider>
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('PlatformAnnouncementsPage', () => {
  beforeEach(() => {
    listPlatformAnnouncements.mockReset().mockResolvedValue({ announcements: ROWS });
    listAudienceBanks.mockReset().mockResolvedValue([
      { code: 'BANK-A', name: 'Bank A', status: 'ACTIVE' },
      { code: 'BANK-B', name: 'Bank B', status: 'ACTIVE' },
    ]);
    createAnnouncement.mockReset().mockResolvedValue({ id: 9 });
    updateAnnouncement.mockReset().mockResolvedValue({ id: 3 });
    archiveAnnouncement.mockReset().mockResolvedValue(null);
  });

  it('lists announcements with audience and status; archived ones cannot be edited', async () => {
    renderPage();

    const maintenance = (await screen.findByText('Sunday maintenance')).closest('tr');
    expect(within(maintenance).getByText('All banks')).toBeInTheDocument();
    expect(within(maintenance).getByText('ACTIVE')).toBeInTheDocument();
    expect(within(maintenance).getByText(/^Maintenance /)).toBeInTheDocument();
    expect(within(maintenance).getByRole('button', { name: 'Edit' })).toBeInTheDocument();

    const archived = screen.getByText('Only for bank A').closest('tr');
    expect(within(archived).getByText('BANK-A')).toBeInTheDocument();
    expect(within(archived).getByText('ARCHIVED')).toBeInTheDocument();
    expect(within(archived).queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
  });

  it('publishes a maintenance announcement for picked banks with ISO instants', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Sunday maintenance');

    await user.click(screen.getByRole('button', { name: 'New announcement' }));
    const dialog = screen.getByRole('dialog', { name: 'New announcement' });
    // Maintenance fields appear only for the MAINTENANCE kind.
    expect(within(dialog).queryByLabelText(/^Maintenance starts/)).not.toBeInTheDocument();
    await user.selectOptions(within(dialog).getByLabelText('Kind'), 'MAINTENANCE');
    await user.selectOptions(within(dialog).getByLabelText('Severity'), 'WARNING');
    await user.type(within(dialog).getByLabelText(/^Title/), 'Database upgrade');
    await user.type(within(dialog).getByLabelText(/^Text/), 'Read-only for 30 minutes.');
    // datetime-local inputs take a whole value (typing key by key is not
    // supported for this input type in jsdom).
    const setDate = (label, value) =>
      fireEvent.change(within(dialog).getByLabelText(label), { target: { value } });
    setDate('Show from', '2026-11-01T09:00');
    setDate('Show until', '2026-11-03T09:00');
    setDate(/^Maintenance starts/, '2026-11-02T22:00');
    setDate(/Maintenance ends/, '2026-11-02T22:30');
    await user.click(within(dialog).getByLabelText('Selected banks'));
    await user.click(within(dialog).getByLabelText(/BANK-B/));
    await user.click(within(dialog).getByRole('button', { name: 'Publish' }));

    await waitFor(() => expect(createAnnouncement).toHaveBeenCalledTimes(1));
    expect(createAnnouncement).toHaveBeenCalledWith({
      kind: 'MAINTENANCE',
      severity: 'WARNING',
      title: 'Database upgrade',
      body: 'Read-only for 30 minutes.',
      audienceBankCodes: ['BANK-B'],
      startsAt: new Date('2026-11-01T09:00').toISOString(),
      endsAt: new Date('2026-11-03T09:00').toISOString(),
      maintenanceStartsAt: new Date('2026-11-02T22:00').toISOString(),
      maintenanceEndsAt: new Date('2026-11-02T22:30').toISOString(),
      releaseVersion: null,
    });
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'New announcement' })).not.toBeInTheDocument()
    );
    expect(listPlatformAnnouncements).toHaveBeenCalledTimes(2);
  });

  it('keeps the dialog open with the server message when the announcement is refused', async () => {
    const refusal = new Error('The maintenance window must lie inside the display window.');
    refusal.status = 400;
    refusal.code = 'ANNOUNCEMENT_INVALID';
    updateAnnouncement.mockRejectedValue(refusal);
    const user = userEvent.setup();
    renderPage();

    const row = (await screen.findByText('Sunday maintenance')).closest('tr');
    await user.click(within(row).getByRole('button', { name: 'Edit' }));
    const dialog = screen.getByRole('dialog', { name: 'Edit announcement' });
    expect(within(dialog).getByLabelText(/^Title/)).toHaveValue('Sunday maintenance');
    expect(within(dialog).getByLabelText(/^Maintenance starts/)).toHaveValue(
      toLocalInput('2026-10-11T22:00:00Z')
    );
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }));

    expect(
      await within(dialog).findByText('The maintenance window must lie inside the display window.')
    ).toBeInTheDocument();
    expect(updateAnnouncement).toHaveBeenCalledWith(3, expect.objectContaining({ kind: 'MAINTENANCE' }));
    expect(screen.getByRole('dialog', { name: 'Edit announcement' })).toBeInTheDocument();
  });

  it('withdraws an announcement only after confirmation', async () => {
    const user = userEvent.setup();
    renderPage();

    const row = (await screen.findByText('Sunday maintenance')).closest('tr');
    await user.click(within(row).getByRole('button', { name: 'Withdraw' }));
    const confirm = screen.getByRole('dialog', { name: 'Withdraw announcement' });
    expect(archiveAnnouncement).not.toHaveBeenCalled();
    await user.click(within(confirm).getByRole('button', { name: 'Withdraw' }));

    await waitFor(() => expect(archiveAnnouncement).toHaveBeenCalledWith(3));
  });

  it('converts between datetime-local values and ISO instants', () => {
    const iso = new Date('2026-11-02T22:30').toISOString();
    expect(toLocalInput(iso)).toBe('2026-11-02T22:30');
    expect(fromLocalInput('2026-11-02T22:30')).toBe(iso);
    expect(fromLocalInput('')).toBeNull();
    expect(toLocalInput(null)).toBe('');

    const request = toRequest({
      kind: 'INFO',
      severity: 'INFO',
      title: '  Hello ',
      body: 'x',
      startsAt: '2026-11-02T22:30',
      endsAt: '2026-11-03T22:30',
      maintenanceStartsAt: '2026-11-02T22:30',
      maintenanceEndsAt: '2026-11-02T23:30',
      releaseVersion: '1.2.3',
      allBanks: false,
      audience: ['A'],
      audienceText: 'B, A,, C',
    });
    expect(request.title).toBe('Hello');
    expect(request.audienceBankCodes).toEqual(['A', 'B', 'C']);
    // Fields of other kinds are never sent.
    expect(request.maintenanceStartsAt).toBeNull();
    expect(request.releaseVersion).toBeNull();
  });
});
