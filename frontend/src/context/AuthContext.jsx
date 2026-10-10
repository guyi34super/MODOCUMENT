import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import * as authApi from '../api/auth';
import { clearSession, getStoredUser, getToken, setSession, setStoredUser } from '../api/client';
import { clearCaches } from '../api/cache';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(getStoredUser);
  // Why the last sign-out happened, for the login page (BUG-04). Never a
  // secret: one of a few fixed words; cleared by the next sign-in.
  const [signOutNotice, setSignOutNotice] = useState(null);

  /**
   * Phase one. When the account has (or must enrol) a second factor the
   * backend answers with a short-lived challenge instead of tokens; the
   * caller then drives the second step ({@link completeMfaLogin}, or the
   * enrolment flow when `enrollmentRequired`).
   *
   * @returns {{mfaRequired: boolean, enrollmentRequired?: boolean, challenge?: string}}
   */
  const login = useCallback(async (email, password) => {
    const data = await authApi.login(email, password);
    if (data?.mfaRequired) {
      return {
        mfaRequired: true,
        enrollmentRequired: Boolean(data.enrollmentRequired),
        challenge: data.challenge,
      };
    }
    setSession(data.token, data.user);
    setSignOutNotice(null);
    setUser(data.user);
    return { mfaRequired: false };
  }, []);

  /** Phase two: challenge + TOTP/recovery code → the normal session. */
  const completeMfaLogin = useCallback(async (challenge, code) => {
    const { token, user: verified } = await authApi.verifyMfa(challenge, code);
    setSession(token, verified);
    setSignOutNotice(null);
    setUser(verified);
  }, []);

  // OIDC landing (/login/complete): the session was opened by the backend's
  // redirect (refresh cookie); the page hands the exchanged token + profile
  // here so the SPA state matches a password login's.
  const completeLogin = useCallback((token, loggedIn) => {
    setSession(token, loggedIn);
    setSignOutNotice(null);
    setUser(loggedIn);
  }, []);

  /**
   * Ends the session. `revoke: false` skips the server call — for when the
   * server already ended it (a password change kills every token): calling
   * logout with the dead token would only earn a 401 (BUG-02).
   *
   * `notice` (e.g. 'password-changed') is what the login page should say.
   * It is committed in the same render as the sign-out, so whichever
   * redirect reaches /login first — the caller's navigate or the
   * ProtectedRoute around the page that signed out — carries it (BUG-04).
   */
  const logout = useCallback(async ({ revoke = true, notice = null } = {}) => {
    if (revoke) {
      try {
        // Best-effort server-side revocation (access-token jti + refresh
        // family); the local session is cleared regardless.
        await authApi.logout();
      } catch {
        /* already signed out server-side, or offline */
      }
    }
    clearSession();
    clearCaches();
    setSignOutNotice(notice);
    setUser(null);
  }, []);

  const updateUser = useCallback((updated) => {
    setStoredUser(updated);
    setUser(updated);
  }, []);

  const value = useMemo(
    () => ({
      user,
      isAuthenticated: Boolean(getToken()),
      signOutNotice,
      login,
      completeMfaLogin,
      completeLogin,
      logout,
      updateUser,
    }),
    [user, signOutNotice, login, completeMfaLogin, completeLogin, logout, updateUser]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}
