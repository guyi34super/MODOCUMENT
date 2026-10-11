import { render } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';

export const ADMIN = { userId: 1, email: 'admin@bank.example', displayName: 'Admin', role: 'ADMIN' };
export const WRITER = { userId: 2, email: 'writer@bank.example', role: 'READ_WRITE' };
export const READER = { userId: 3, email: 'reader@bank.example', role: 'READ_ONLY' };
export const PLATFORM = { userId: 9, email: 'owner@moi.example', role: 'PLATFORM_ADMIN' };

/** A 202 PendingChange DTO (isPendingChange()). */
export const pendingChange = (id, changeType) => ({
  pendingChangeId: id,
  changeType,
  status: 'REQUESTED',
});

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}</div>;
}

/**
 * Renders `element` at `path` (route pattern `route`) signed in as `user`,
 * inside the auth + toast providers; any other route renders a location
 * probe (`data-testid="location"`).
 */
export function renderWithProviders(element, { user = ADMIN, path = '/', route } = {}) {
  localStorage.setItem('modoc.token', 'test-token');
  if (user) localStorage.setItem('modoc.user', JSON.stringify(user));
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <ToastProvider>
          <Routes>
            <Route path={route || path} element={element} />
            <Route path="*" element={<LocationProbe />} />
          </Routes>
        </ToastProvider>
      </AuthProvider>
    </MemoryRouter>
  );
}
