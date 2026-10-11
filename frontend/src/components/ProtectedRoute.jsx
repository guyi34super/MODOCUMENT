import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export default function ProtectedRoute({ children }) {
  const { isAuthenticated, user, signOutNotice } = useAuth();
  const location = useLocation();
  if (!isAuthenticated) {
    // A deliberate sign-out may leave a notice for the login page (e.g.
    // 'password-changed'). This redirect usually wins the race against the
    // signing-out page's own navigate, so it must carry the notice (BUG-04).
    return (
      <Navigate
        to="/login"
        replace
        state={signOutNotice ? { notice: signOutNotice } : undefined}
      />
    );
  }
  // ws09 forced password change: a temporary password buys nothing but the
  // change itself (the server refuses everything else anyway) — route the
  // user straight to the change page before any other screen.
  if (user?.mustChangePassword && location.pathname !== '/force-password-change') {
    return <Navigate to="/force-password-change" replace />;
  }
  return children;
}
