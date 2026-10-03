import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import * as authApi from '../api/auth';
import { clearSession, getStoredUser, getToken, setSession, setStoredUser } from '../api/client';
import { clearCaches } from '../api/cache';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(getStoredUser);

  const login = useCallback(async (email, password) => {
    const { token, user: loggedIn } = await authApi.login(email, password);
    setSession(token, loggedIn);
    setUser(loggedIn);
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
    () => ({ user, isAuthenticated: Boolean(getToken()), login, logout, updateUser }),
    [user, login, logout, updateUser]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}
