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
