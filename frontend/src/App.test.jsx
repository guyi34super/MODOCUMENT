import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import { ToastProvider } from './context/ToastContext';
import App from './App';
import { getOverview } from './api/platform';
import { listAuditActions, listAuditEvents } from './api/audit';

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

vi.mock('./api/audit', async (importOriginal) => ({
  ...(await importOriginal()),
  listAuditEvents: vi.fn(),
  listAuditActions: vi.fn(),
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

// v1.2.0: route pages are lazy chunks behind <Suspense>; the chrome renders
// at once, the page when its chunk has loaded.
describe('App lazy routes', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => ({ matches: true, addEventListener: () => {}, removeEventListener: () => {} }))
    );
    listAuditEvents.mockReset().mockResolvedValue({ events: [], nextCursor: null });
    listAuditActions.mockReset().mockResolvedValue({ actions: [] });
  });

  it('loads the audit log page for an ADMIN inside the app chrome', async () => {
    renderAt('/admin/audit', { userId: 1, role: 'ADMIN', displayName: 'Admin' });

    expect(screen.getByRole('button', { name: 'Log out' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Audit log' })).toBeInTheDocument();
    expect(await screen.findByText('No audit events match these filters.')).toBeInTheDocument();
    expect(listAuditEvents).toHaveBeenCalled();
  });
});
