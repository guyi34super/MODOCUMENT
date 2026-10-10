import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from '../context/AuthContext';
import Layout from './Layout';

/** Stubs matchMedia so the component believes it is on a phone or a desktop. */
function stubViewport(isDesktop) {
  const listeners = new Set();
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: isDesktop,
      addEventListener: (_, handler) => listeners.add(handler),
      removeEventListener: (_, handler) => listeners.delete(handler),
    }))
  );
  return listeners;
}

function renderLayout() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <AuthProvider>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<div>HOME PAGE</div>} />
            <Route path="/templates" element={<div>TEMPLATES PAGE</div>} />
          </Route>
        </Routes>
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('Layout sidebar', () => {
  beforeEach(() => {
    localStorage.setItem('modoc.token', 'test-token');
  });

  it('docks the sidebar open on a desktop viewport', () => {
    stubViewport(true);
    renderLayout();

    expect(screen.getByRole('button', { name: 'Toggle sidebar' })).toHaveAttribute(
      'aria-expanded',
      'true'
    );
    expect(screen.queryByRole('button', { name: 'Close navigation' })).not.toBeInTheDocument();
  });

  it('starts closed below lg and keeps its links out of the tab order', () => {
    stubViewport(false);
    renderLayout();

    const nav = screen.getByLabelText('Main navigation');
    expect(nav).toHaveAttribute('inert');
    expect(screen.queryByRole('button', { name: 'Close navigation' })).not.toBeInTheDocument();
  });

  it('opens as a drawer with a backdrop and closes on navigation', async () => {
    stubViewport(false);
    renderLayout();

    await userEvent.click(screen.getByRole('button', { name: 'Toggle sidebar' }));
    const nav = screen.getByLabelText('Main navigation');
    expect(nav).not.toHaveAttribute('inert');
    expect(screen.getByRole('button', { name: 'Close navigation' })).toBeInTheDocument();
    // Above the sticky page chrome it overlays.
    expect(nav.className).toContain('z-[45]');

    await userEvent.click(screen.getByRole('link', { name: /Design Templates/ }));

    expect(await screen.findByText('TEMPLATES PAGE')).toBeInTheDocument();
    expect(screen.getByLabelText('Main navigation')).toHaveAttribute('inert');
  });

  /** ws09/ws10: admin links render per role (UX, not security). */
  it('shows Users and Approvals to ADMINs', () => {
    stubViewport(true);
    localStorage.setItem('modoc.user', JSON.stringify({ userId: 1, role: 'ADMIN' }));
    renderLayout();

    expect(screen.getByRole('link', { name: 'Users' })).toHaveAttribute('href', '/admin/users');
    expect(screen.getByRole('link', { name: 'Approvals' })).toHaveAttribute(
      'href',
      '/admin/changes'
    );
    // ws18: the bank's security alerts.
    expect(screen.getByRole('link', { name: 'Security alerts' })).toHaveAttribute(
      'href',
      '/admin/alerts'
    );
    expect(screen.getByText('Administration')).toBeInTheDocument();
    // Phase 2 (ws22): the admin dashboard and the bank settings.
    expect(screen.getByRole('link', { name: 'Admin dashboard' })).toHaveAttribute(
      'href',
      '/admin'
    );
    expect(screen.getByRole('link', { name: 'Settings' })).toHaveAttribute(
      'href',
      '/admin/settings'
    );
    // The user workspace stays at "/" for admins too.
    expect(screen.getByRole('link', { name: 'Home' })).toHaveAttribute('href', '/');
  });

  it('shows Approvals but not Users to APPROVERs', () => {
    stubViewport(true);
    localStorage.setItem('modoc.user', JSON.stringify({ userId: 3, role: 'APPROVER' }));
    renderLayout();

    expect(screen.getByRole('link', { name: 'Approvals' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Users' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Security alerts' })).not.toBeInTheDocument();
    expect(screen.getByText('Administration')).toBeInTheDocument();
  });

  it('hides the Administration section from everyone else', () => {
    stubViewport(true);
    localStorage.setItem('modoc.user', JSON.stringify({ userId: 2, role: 'READ_WRITE' }));
    renderLayout();

    expect(screen.queryByRole('link', { name: 'Users' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Approvals' })).not.toBeInTheDocument();
    expect(screen.queryByText('Administration')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Admin dashboard' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Settings' })).not.toBeInTheDocument();
  });

  /** ws21: the platform owner sees the Platform console, never the domain sections. */
  it('shows a PLATFORM_ADMIN only the Platform section, users, approvals and account', () => {
    stubViewport(true);
    localStorage.setItem('modoc.user', JSON.stringify({ userId: 9, role: 'PLATFORM_ADMIN' }));
    renderLayout();

    expect(screen.getByText('Platform')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Overview' })).toHaveAttribute('href', '/platform');
    expect(screen.getByRole('link', { name: 'Banks' })).toHaveAttribute(
      'href',
      '/platform/banks'
    );
    expect(screen.getByRole('link', { name: 'Users' })).toHaveAttribute('href', '/admin/users');
    expect(screen.getByRole('link', { name: 'Approvals' })).toHaveAttribute(
      'href',
      '/admin/changes'
    );
    // ws22: the platform layer of the settings screen (not the bank one).
    expect(screen.getByRole('link', { name: 'Settings' })).toHaveAttribute(
      'href',
      '/platform/settings'
    );
    expect(screen.getByRole('link', { name: 'Profile' })).toBeInTheDocument();
    for (const hidden of ['Home', /Design Templates/, 'Blueprints', 'Extraction Jobs',
      'Security alerts', 'Releases', 'Admin dashboard']) {
      expect(screen.queryByRole('link', { name: hidden })).not.toBeInTheDocument();
    }
    expect(screen.queryByText('Document Flow')).not.toBeInTheDocument();
    expect(screen.queryByText('Data Extraction')).not.toBeInTheDocument();
  });

  it('never shows the Platform section to a bank ADMIN', () => {
    stubViewport(true);
    localStorage.setItem('modoc.user', JSON.stringify({ userId: 1, role: 'ADMIN' }));
    renderLayout();

    expect(screen.queryByText('Platform')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Overview' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Home' })).toBeInTheDocument();
    expect(screen.getByText('Document Flow')).toBeInTheDocument();
  });

  it('closes the drawer on Escape and on a backdrop click', async () => {
    stubViewport(false);
    renderLayout();

    await userEvent.click(screen.getByRole('button', { name: 'Toggle sidebar' }));
    await userEvent.keyboard('{Escape}');
    expect(screen.getByLabelText('Main navigation')).toHaveAttribute('inert');

    await userEvent.click(screen.getByRole('button', { name: 'Toggle sidebar' }));
    await userEvent.click(screen.getByRole('button', { name: 'Close navigation' }));
    expect(screen.getByLabelText('Main navigation')).toHaveAttribute('inert');
  });
});
