import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import AppVersionCard from './AppVersionCard';
import { APP_VERSION } from '../version';

import { getVersion } from '../api/system';

vi.mock('../api/system', () => ({
  getVersion: vi.fn(),
}));

describe('AppVersionCard', () => {
  beforeEach(() => {
    getVersion.mockReset();
  });

  it('shows the bundle version under the app-version test id and what the server runs', async () => {
    getVersion.mockResolvedValue({
      version: APP_VERSION,
      commit: 'abc1234',
      buildTime: '2026-10-10T12:00:00Z',
      schemaVersion: '241',
      deployedAt: '2026-10-10T12:05:00Z',
      environment: 'staging',
    });
    render(<AppVersionCard />);

    expect(screen.getByTestId('app-version')).toHaveTextContent(APP_VERSION);
    expect(await screen.findByText('abc1234')).toBeInTheDocument();
    expect(screen.getByText('staging')).toBeInTheDocument();
    expect(screen.getByText('V241')).toBeInTheDocument();
    expect(screen.queryByText(/reload the page/)).not.toBeInTheDocument();
  });

  it('points out a newer server version, and still shows its own when the server is unreachable', async () => {
    getVersion.mockResolvedValueOnce({ version: '99.0.0', environment: 'production' });
    const { unmount } = render(<AppVersionCard />);
    expect(await screen.findByText(/99\.0\.0 \(reload the page/)).toBeInTheDocument();
    unmount();

    getVersion.mockRejectedValueOnce(new Error('offline'));
    render(<AppVersionCard />);
    expect(screen.getByTestId('app-version')).toHaveTextContent(APP_VERSION);
  });
});
