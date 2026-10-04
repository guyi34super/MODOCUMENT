import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from '../context/AuthContext';
import ProtectedRoute from './ProtectedRoute';

function renderApp() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<div>LOGIN</div>} />
          <Route
            path="/force-password-change"
            element={
              <ProtectedRoute>
                <div>FORCED CHANGE</div>
              </ProtectedRoute>
            }
          />
          <Route
            path="/"
            element={
              <ProtectedRoute>
                <div>SECRET</div>
              </ProtectedRoute>
            }
          />
        </Routes>
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('ProtectedRoute', () => {
  it('redirects to /login without a token', () => {
    renderApp();
    expect(screen.getByText('LOGIN')).toBeInTheDocument();
    expect(screen.queryByText('SECRET')).not.toBeInTheDocument();
  });

  it('renders the protected content with a token', () => {
    localStorage.setItem('modoc.token', 'jwt-1');
    localStorage.setItem('modoc.user', JSON.stringify({ userId: 1 }));

    renderApp();
    expect(screen.getByText('SECRET')).toBeInTheDocument();
  });

  /** ws09: a temporary password routes to the forced change before anything. */
  it('redirects a must-change-password user to the forced change page', () => {
    localStorage.setItem('modoc.token', 'jwt-1');
    localStorage.setItem(
      'modoc.user',
      JSON.stringify({ userId: 1, mustChangePassword: true })
    );

    renderApp();
    expect(screen.getByText('FORCED CHANGE')).toBeInTheDocument();
    expect(screen.queryByText('SECRET')).not.toBeInTheDocument();
  });
});
