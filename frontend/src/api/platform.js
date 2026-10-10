import { api } from './client';

// Platform owner console (ws21). Every call needs a PLATFORM_ADMIN of the
// platform bank (platform:read / platform:banks); the backend answers counts
// only — never a bank's users, templates, jobs or documents.

/** Customer banks as counts-only summaries plus totals. */
export const getOverview = () => api.get('/api/platform/overview');

/** Every bank (customer banks and the platform bank itself). */
export const listBanks = () => api.get('/api/platform/banks');

export const getBank = (id) => api.get(`/api/platform/banks/${id}`);

/**
 * Maker side of BANK_CREATE: 202 + the pending change a SECOND platform
 * admin approves. `users`: [{email, displayName, role, temporaryPassword}],
 * at least two, at least one ADMIN and two who can approve (ADMIN/APPROVER).
 */
export const onboardBank = ({ code, name, users }) =>
  api.post('/api/platform/banks', {
    code,
    name,
    users: users.map(({ email, displayName, role, temporaryPassword }) => ({
      email,
      displayName,
      role,
      temporaryPassword,
    })),
  });

/** Maker side of BANK_SUSPEND: 202 + the pending change. */
export const suspendBank = (id, reason) =>
  api.post(`/api/platform/banks/${id}/suspend`, { reason });

/** Direct (200 + the bank summary): the bank's users can sign in again. */
export const reactivateBank = (id) => api.post(`/api/platform/banks/${id}/reactivate`);

/** Maker side of BANK_ADMIN_ADD (break-glass): 202 + the pending change. */
export const addBankAdmin = (id, { email, displayName, temporaryPassword, reason }) =>
  api.post(`/api/platform/banks/${id}/admins`, {
    email,
    displayName,
    temporaryPassword,
    reason,
  });
