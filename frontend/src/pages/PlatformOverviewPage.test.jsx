import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import PlatformOverviewPage from './PlatformOverviewPage';
import { getOverview } from '../api/platform';

vi.mock('../api/platform', () => ({ getOverview: vi.fn() }));

const OVERVIEW = {
  banks: [
    {
      bankId: 7,
      code: 'ACME',
      name: 'Acme Bank',
      kind: 'CUSTOMER',
      status: 'ACTIVE',
      users: 12,
      mfaEnrolledPct: 75,
      jobs7d: 40,
      failures7d: 2,
      aiShare7d: null,
      codeShare7d: null,
      lastActivityAt: '2026-10-09T08:00:00',
    },
    {
      bankId: 9,
      code: 'GONE',
      name: 'Gone Bank',
      kind: 'CUSTOMER',
      status: 'SUSPENDED',
      users: 3,
      mfaEnrolledPct: null,
      jobs7d: 0,
      failures7d: 0,
      aiShare7d: null,
      codeShare7d: null,
      lastActivityAt: null,
    },
  ],
  totals: { banks: 2, activeBanks: 1, suspendedBanks: 1, users: 15 },
};

/** The KPI tile whose label is `label` (table headers and badges share some words). */
function tile(label) {
  return screen
    .getAllByText(label)
    .map((element) => element.closest('[data-tone]'))
    .find(Boolean);
}

function renderPage() {
  return render(
    <MemoryRouter>
      <PlatformOverviewPage />
    </MemoryRouter>
  );
}

describe('PlatformOverviewPage', () => {
  beforeEach(() => {
    getOverview.mockReset().mockResolvedValue(OVERVIEW);
  });

  it('shows the totals as KPI tiles', async () => {
    renderPage();

    await screen.findByRole('table', { name: 'Banks' });
    const banksTile = tile('Banks');
    expect(banksTile).toHaveTextContent('2');
    expect(banksTile).toHaveAttribute('href', '/platform/banks');
    expect(tile('Active')).toHaveTextContent('1');
    expect(tile('Suspended')).toHaveTextContent('1');
    expect(tile('Suspended')).toHaveAttribute('data-tone', 'warning');
    expect(tile('Users')).toHaveTextContent('15');
  });

  it('lists every customer bank with counts only, linking to its detail page', async () => {
    renderPage();

    const table = await screen.findByRole('table', { name: 'Banks' });
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(3); // header + 2 banks
    expect(within(rows[1]).getByRole('link', { name: 'ACME' })).toHaveAttribute(
      'href',
      '/platform/banks/7'
    );
    expect(within(rows[1]).getByText('75%')).toBeInTheDocument();
    expect(within(rows[1]).getByText('Active')).toBeInTheDocument();
    expect(within(rows[2]).getByText('Suspended')).toBeInTheDocument();
    // No MFA share for a bank without users: a dash, not "null%".
    expect(within(rows[2]).getAllByText('—').length).toBeGreaterThan(0);
  });

  it('invites the first onboarding when there are no customer banks', async () => {
    getOverview.mockResolvedValue({
      banks: [],
      totals: { banks: 0, activeBanks: 0, suspendedBanks: 0, users: 0 },
    });
    renderPage();

    expect(await screen.findByRole('link', { name: 'Onboard the first one' })).toHaveAttribute(
      'href',
      '/platform/banks'
    );
    expect(screen.queryByRole('table', { name: 'Banks' })).not.toBeInTheDocument();
  });

  it('shows a load failure', async () => {
    getOverview.mockRejectedValue(new Error('Request failed (403)'));
    renderPage();

    expect(await screen.findByText('Request failed (403)')).toBeInTheDocument();
  });
});
