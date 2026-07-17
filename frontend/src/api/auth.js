import { api } from './client';

export const login = (email, password) => api.post('/api/auth/login', { email, password });

export const register = (email, password, displayName) =>
  api.post('/api/auth/register', { email, password, displayName });

export const me = () => api.get('/api/auth/me');

export const updateProfile = (displayName, settings) =>
  api.put('/api/auth/profile', { displayName, settings });

export const changePassword = (currentPassword, newPassword) =>
  api.put('/api/auth/password', { currentPassword, newPassword });
