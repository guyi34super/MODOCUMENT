import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AuthProvider } from '../context/AuthContext';
import AnnouncementBanner, { CHECK_INTERVAL_MS, DISMISSED_KEY } from './AnnouncementBanner';
import { APP_VERSION } from '../version';

import { getActiveAnnouncements } from '../api/announcements';
import { getReleaseNotes, getVersion } from '../api/system';

vi.mock('../api/announcements', () => ({
  getActiveAnnouncements: vi.fn(),
}));
vi.mock('../api/system', () => ({
  getVersion: vi.fn(),
  getReleaseNotes: vi.fn(),
}));

const INFO = {
  id: 11,
  kind: 'INFO',
  severity: 'INFO',
  title: 'New export formats',
  body: 'XLSX exports now keep column widths.',
  startsAt: '2026-10-10T08:00:00Z',
  endsAt: '2026-10-20T08:00:00Z',
  maintenanceStartsAt: null,
  maintenanceEndsAt: null,
  releaseVersion: null,
  dismissible: true,
};

const MAINTENANCE = {
  id: 12,
  kind: 'MAINTENANCE',
  severity: 'CRITICAL',
  title: 'Planned maintenance on Sunday',
  body: 'The service will be unavailable.',
  startsAt: '2026-10-10T08:00:00Z',
  endsAt: '2026-10-12T08:00:00Z',
  maintenanceStartsAt: '2026-10-11T22:00:00Z',
  maintenanceEndsAt: '2026-10-11T23:30:00Z',
  releaseVersion: null,
  dismissible: false,
};

const RELEASE = {
  id: 13,
  kind: 'RELEASE',
  severity: 'INFO',
  title: `MoDocument ${APP_VERSION} is now live`,
  body: 'A new version of MoDocument has been deployed.',
  startsAt: '2026-10-10T08:00:00Z',
  endsAt: '2026-10-17T08:00:00Z',
  maintenanceStartsAt: null,
  maintenanceEndsAt: null,
  releaseVersion: APP_VERSION,
  dismissible: true,
};

/** A token whose exp is far ahead, so the quiet periodic checks are allowed. */
function freshToken() {
  const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }));
  return `h.${payload}.s`;
}

function renderBanner(role = 'READ_WRITE', token = 'test-token') {
  localStorage.setItem('modoc.token', token);
  localStorage.setItem('modoc.user', JSON.stringify({ userId: 1, email: 'u@bank.example', role }));
  return render(
    <AuthProvider>
      <AnnouncementBanner />
    </AuthProvider>
  );
}

describe('AnnouncementBanner', () => {
  beforeEach(() => {
    getActiveAnnouncements.mockReset().mockResolvedValue({ announcements: [MAINTENANCE, INFO] });
    getVersion.mockReset().mockResolvedValue({ version: APP_VERSION });
    getReleaseNotes.mockReset().mockResolvedValue({
      releases: [
        {
          version: APP_VERSION,
          date: '2026-10-10',
          sections: { Added: ['Maintenance banners'], Fixed: ['Export widths'] },
        },
      ],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows each announcement in the Announcements region with a per-kind test id', async () => {
    renderBanner();

    const region = await screen.findByRole('region', { name: 'Announcements' });
    expect(within(region).getByTestId('announcement-maintenance')).toHaveTextContent(
      'Planned maintenance on Sunday'
    );
    expect(within(region).getByTestId('announcement-info')).toHaveTextContent(
      'XLSX exports now keep column widths.'
    );
  });

  it('states the severity in words and ties each Dismiss to its announcement', async () => {
    renderBanner();

    const maintenance = await screen.findByTestId('announcement-maintenance');
    expect(maintenance).toHaveTextContent('Critical: Planned maintenance on Sunday');
    const info = screen.getByTestId('announcement-info');
    const dismiss = within(info).getByRole('button', { name: 'Dismiss' });
    expect(dismiss).toHaveAccessibleDescription(/^Information: /);
  });

  it('never offers to dismiss maintenance and shows its window in local time', async () => {
    renderBanner();

    const maintenance = await screen.findByTestId('announcement-maintenance');
    expect(within(maintenance).queryByRole('button', { name: 'Dismiss' })).not.toBeInTheDocument();
    const times = maintenance.querySelectorAll('time');
    expect(times).toHaveLength(2);
    expect(times[0]).toHaveAttribute('dateTime', '2026-10-11T22:00:00Z');
    expect(times[0].textContent).toBe(
      new Date('2026-10-11T22:00:00Z').toLocaleString(undefined, {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        timeZoneName: 'short',
      })
    );
    expect(maintenance).toHaveTextContent('Maintenance window:');
  });

  it('remembers a dismissal per id across reloads, and ignores a broken store', async () => {
    const user = userEvent.setup();
    const { unmount } = renderBanner();

    const info = await screen.findByTestId('announcement-info');
    await user.click(within(info).getByRole('button', { name: 'Dismiss' }));
    expect(screen.queryByTestId('announcement-info')).not.toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem(DISMISSED_KEY))).toEqual([11]);
    unmount();

    const second = renderBanner();
    expect(await screen.findByTestId('announcement-maintenance')).toBeInTheDocument();
    expect(screen.queryByTestId('announcement-info')).not.toBeInTheDocument();
    second.unmount();

    // Storage that throws (blocked site data) must not break the banner:
    // nothing is remembered, and dismissing still hides it for now.
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation((key) => {
      if (key === DISMISSED_KEY) throw new Error('blocked');
      return null;
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    render(
      <AuthProvider>
        <AnnouncementBanner />
      </AuthProvider>
    );
    const again = await screen.findByTestId('announcement-info');
    await user.click(within(again).getByRole('button', { name: 'Dismiss' }));
    expect(screen.queryByTestId('announcement-info')).not.toBeInTheDocument();
  });

  it('renders markup in a body as inert text', async () => {
    getActiveAnnouncements.mockResolvedValue({
      announcements: [{ ...INFO, body: '<img src=x onerror="alert(1)"><script>alert(2)</script>' }],
    });
    renderBanner();

    const info = await screen.findByTestId('announcement-info');
    expect(info).toHaveTextContent('<img src=x onerror="alert(1)"><script>alert(2)</script>');
    expect(info.querySelector('img')).toBeNull();
    expect(info.querySelector('script')).toBeNull();
  });

  it("gives admins a What's new link to the release notes, but not other roles", async () => {
    getActiveAnnouncements.mockResolvedValue({ announcements: [RELEASE] });
    const user = userEvent.setup();
    const { unmount } = renderBanner('READ_WRITE');

    const forUser = await screen.findByTestId('announcement-release');
    expect(within(forUser).queryByRole('link', { name: /What's new in/ })).not.toBeInTheDocument();
    expect(within(forUser).getByRole('button', { name: 'Dismiss' })).toBeInTheDocument();
    unmount();

    renderBanner('ADMIN');
    const link = await screen.findByRole('link', { name: `What's new in ${APP_VERSION}` });
    await user.click(link);

    const dialog = await screen.findByRole('dialog', { name: `What's new in ${APP_VERSION}` });
    expect(await within(dialog).findByText('Maintenance banners')).toBeInTheDocument();
    expect(within(dialog).getByText('Export widths')).toBeInTheDocument();
    expect(getReleaseNotes).toHaveBeenCalled();
  });

  it('prompts a reload when the backend runs another version, and not otherwise', async () => {
    const { unmount } = renderBanner();
    await screen.findByTestId('announcement-info');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    unmount();

    getVersion.mockResolvedValue({ version: '99.0.0' });
    renderBanner();
    const prompt = await screen.findByRole('alert');
    expect(prompt).toHaveTextContent('A new version is available (99.0.0)');
    expect(within(prompt).getByRole('button', { name: 'Reload' })).toBeInTheDocument();
  });

  it('re-checks every 5 minutes while the session token is fresh, never when it is not', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    getActiveAnnouncements.mockResolvedValue({ announcements: [] });
    const { unmount } = renderBanner('READ_WRITE', freshToken());
    await waitFor(() => expect(getVersion).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('region', { name: 'Announcements' })).not.toBeInTheDocument();

    getVersion.mockResolvedValue({ version: '99.0.0' });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(CHECK_INTERVAL_MS);
    });
    await waitFor(() => expect(getVersion).toHaveBeenCalledTimes(2));
    expect(await screen.findByRole('alert')).toHaveTextContent('A new version is available');
    unmount();

    // A token that is not fresh: the periodic check stays quiet (it must not
    // refresh the session of an idle tab).
    getVersion.mockClear();
    renderBanner('READ_WRITE', 'not-a-jwt');
    await waitFor(() => expect(getVersion).toHaveBeenCalledTimes(1));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(CHECK_INTERVAL_MS * 2);
    });
    expect(getVersion).toHaveBeenCalledTimes(1);
  });

  it('renders nothing when there is nothing to say, and survives API failures', async () => {
    getActiveAnnouncements.mockRejectedValue(new Error('offline'));
    getVersion.mockRejectedValue(new Error('offline'));
    const { container } = renderBanner();
    await waitFor(() => expect(getVersion).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});
