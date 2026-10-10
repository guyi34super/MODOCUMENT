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

// ---- Phase 2 (ws22) user actions. None of them accepts the caller's own id
// (409); another bank's user answers 404.

/** Lifts a login lockout now (direct, 200 + the user row). */
export const unlockUser = (id) => api.post(`/api/admin/users/${id}/unlock`);

/**
 * Maker side of USER_PASSWORD_RESET: 202 + the pending change. On approval
 * every session of the user ends and the temporary password must be changed
 * at the next sign-in.
 */
export const resetUserPassword = (id, temporaryPassword) =>
  api.post(`/api/admin/users/${id}/password-reset`, { temporaryPassword });

/** Maker side of USER_MFA_RESET: 202 + the pending change. */
export const resetUserMfa = (id, reason) =>
  api.post(`/api/admin/users/${id}/mfa-reset`, { reason });

/** Direct edit (200 + the user row); only the display name is editable. */
export const updateUser = (id, { displayName }) =>
  api.patch(`/api/admin/users/${id}`, { displayName });

/** The SEC-013 quarterly recertification worksheet. */
export const accessReview = () => api.get('/api/admin/users/access-review');

/** B10: CSV of the bank's users with last-login dates (browser download). */
export const exportUsers = () => downloadFile('/api/admin/users/export', 'users.csv');
