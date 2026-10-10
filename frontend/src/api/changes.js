import { api } from './client';

// Maker/checker approval queue (ws10, SEC-023). Submissions happen through
// the domain endpoints (users, templates), which answer 202 with the
// pending change; this module drives the queue itself. Approve/reject need
// change:approve (ADMIN or APPROVER); makers see and cancel their own.

/** Returns true when an API result is a pending change awaiting approval. */
export const isPendingChange = (result) =>
  !!result && result.status === 'REQUESTED' && typeof result.pendingChangeId === 'number';

export const listChanges = (status) =>
  api.get(status ? `/api/admin/changes?status=${encodeURIComponent(status)}` : '/api/admin/changes');

/** Approves AND applies — the backend hard-blocks approving your own change (409). */
export const approveChange = (id, comment) =>
  api.post(`/api/admin/changes/${id}/approve`, { comment });

export const rejectChange = (id, comment) =>
  api.post(`/api/admin/changes/${id}/reject`, { comment });

/** Maker only: withdraws an undecided request. */
export const cancelChange = (id) => api.post(`/api/admin/changes/${id}/cancel`);
