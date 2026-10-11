import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import { ToastProvider } from './context/ToastContext';
import App from './App';
import { getOverview } from './api/platform';

// ws21 routing contract: "/" is the platform console for a PLATFORM_ADMIN.
vi.mock('./api/platform', () => ({
  getOverview: vi.fn(),
  listBanks: vi.fn(),
  getBank: vi.fn(),
  onboardBank: vi.fn(),
  suspendBank: vi.fn(),
  reactivateBank: vi.fn(),
  addBankAdmin: vi.fn(),
}));

function renderAt(path, user) {
  localStorage.setItem('modoc.token', 'test-token');
  localStorage.setItem('modoc.user', JSON.stringify(user));
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <ToastProvider>
          <App />
        </ToastProvider>
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('App routing for the platform owner', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => ({ matches: true, addEventListener: () => {}, removeEventListener: () => {} }))
    );
    getOverview.mockReset().mockResolvedValue({
      banks: [],
      totals: { banks: 0, activeBanks: 0, suspendedBanks: 0, users: 0 },
    });
  });

  it('sends a PLATFORM_ADMIN from "/" to the platform overview', async () => {
    renderAt('/', { userId: 9, role: 'PLATFORM_ADMIN', displayName: 'Ops' });

    expect(await screen.findByRole('heading', { name: 'Platform overview' })).toBeInTheDocument();
    expect(getOverview).toHaveBeenCalled();
  });

  it('sends a PLATFORM_ADMIN from an unknown path to the platform overview too', async () => {
    renderAt('/no-such-page', { userId: 9, role: 'PLATFORM_ADMIN', displayName: 'Ops' });

    expect(await screen.findByRole('heading', { name: 'Platform overview' })).toBeInTheDocument();
  });
});
