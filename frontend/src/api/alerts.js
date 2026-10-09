import { api } from './client';

// ws18 (gate D1): security alerts raised for the caller's bank. ADMIN-only
// (user:admin); the backend scopes the list to the caller's own bank.

/** Newest first: rule, severity, summary, created/delivered times, channels, status. */
export const listAlerts = (limit = 50) => api.get(`/api/admin/alerts?limit=${limit}`);
