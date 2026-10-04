import { api, downloadFile } from './client';

// Bank-scoped user administration (ws09). Every call is ADMIN-only
// (user:admin) and the backend scopes everything to the caller's own bank.

export const listUsers = () => api.get('/api/admin/users');

export const createUser = ({ email, displayName, role, temporaryPassword }) =>
  api.post('/api/admin/users', { email, displayName, role, temporaryPassword });

/** Mover: the backend kills every outstanding session of the target. */
export const setUserRole = (id, role) => api.put(`/api/admin/users/${id}/role`, { role });

/** Leaver switch: disabling makes the account inert immediately. */
export const setUserDisabled = (id, disabled) =>
  api.put(`/api/admin/users/${id}/disabled`, { disabled });

/** The SEC-013 quarterly recertification worksheet. */
export const accessReview = () => api.get('/api/admin/users/access-review');

/** B10: CSV of the bank's users with last-login dates (browser download). */
export const exportUsers = () => downloadFile('/api/admin/users/export', 'users.csv');
