import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import * as authApi from '../api/auth';

/**
 * OIDC landing (ws07 bridge pattern). The backend's login success handler
 * 302s here after the external IdP authenticated the browser, carrying ONLY
 * the httpOnly refresh cookie — never a token in the URL. This page
 * exchanges that cookie for an access token (POST /api/auth/refresh),
 * confirms the profile (GET /api/auth/me), stores the session and heads
 * home; any failure falls back to /login?error=oidc.
 */
export default function LoginCompletePage() {
  const { completeLogin, updateUser } = useAuth();
  const navigate = useNavigate();
  const ran = useRef(false);

  useEffect(() => {
    if (ran.current) return; // StrictMode double-invoke guard: refresh rotates.
    ran.current = true;
    (async () => {
      try {
        const session = await authApi.refresh();
        if (!session || !session.token) throw new Error('No session');
        completeLogin(session.token, session.user || null);
        try {
          const profile = await authApi.me();
          if (profile) updateUser(profile);
        } catch {
          // The refresh response already carried the user; proceed with it.
        }
        navigate('/', { replace: true });
      } catch {
        navigate('/login?error=oidc', { replace: true });
      }
    })();
  }, [completeLogin, updateUser, navigate]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-sidebar px-4">
      <div className="w-full max-w-sm rounded-lg border border-line bg-white p-8 text-center">
        <div className="mx-auto mb-4 flex size-10 items-center justify-center rounded border-2 border-ink text-lg font-black">
          MO
        </div>
        <p className="text-sm text-muted">Completing sign-in…</p>
      </div>
    </div>
  );
}
