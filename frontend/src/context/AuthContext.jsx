import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import * as authApi from '../api/auth';
import { clearSession, getStoredUser, getToken, setSession, setStoredUser } from '../api/client';
import { clearCaches } from '../api/cache';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(getStoredUser);

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
    setUser(data.user);
    return { mfaRequired: false };
  }, []);

  /** Phase two: challenge + TOTP/recovery code → the normal session. */
  const completeMfaLogin = useCallback(async (challenge, code) => {
    const { token, user: verified } = await authApi.verifyMfa(challenge, code);
    setSession(token, verified);
    setUser(verified);
  }, []);

  const logout = useCallback(async () => {
    try {
      // Best-effort server-side revocation (access-token jti + refresh
      // family); the local session is cleared regardless.
      await authApi.logout();
    } catch {
      /* already signed out server-side, or offline */
    }
    clearSession();
    clearCaches();
    setUser(null);
  }, []);

  const updateUser = useCallback((updated) => {
    setStoredUser(updated);
    setUser(updated);
  }, []);

  const value = useMemo(
    () => ({ user, isAuthenticated: Boolean(getToken()), login, completeMfaLogin, logout, updateUser }),
    [user, login, completeMfaLogin, logout, updateUser]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}
