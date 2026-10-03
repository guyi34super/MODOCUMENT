import { api } from './client';

// Registration is admin-only now — there is no public register endpoint.

export const login = (email, password) => api.post('/api/auth/login', { email, password });

/**
 * Configured OIDC providers ([{ id, name }]) for the login page's SSO
 * buttons; public, answers [] when no provider is configured.
 */
export const providers = () => api.get('/api/auth/providers');

/**
 * Exchanges the httpOnly refresh cookie for a fresh access token
 * ({ token, user }). Used by the OIDC landing page (/login/complete) — the
 * token never travels in the redirect URL, only in this response body.
 */
export const refresh = () => api.post('/api/auth/refresh');

/** Revokes the access token and every refresh token server-side. */
export const logout = () => api.post('/api/auth/logout');

export const me = () => api.get('/api/auth/me');

export const updateProfile = (displayName, settings) =>
  api.put('/api/auth/profile', { displayName, settings });

export const changePassword = (currentPassword, newPassword) =>
  api.put('/api/auth/password', { currentPassword, newPassword });
