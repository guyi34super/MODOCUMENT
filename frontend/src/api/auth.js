import { api } from './client';

// Registration is admin-only now — there is no public register endpoint.

export const login = (email, password) => api.post('/api/auth/login', { email, password });

/** Revokes the access token and every refresh token server-side. */
export const logout = () => api.post('/api/auth/logout');

export const me = () => api.get('/api/auth/me');

export const updateProfile = (displayName, settings) =>
  api.put('/api/auth/profile', { displayName, settings });

export const changePassword = (currentPassword, newPassword) =>
  api.put('/api/auth/password', { currentPassword, newPassword });
