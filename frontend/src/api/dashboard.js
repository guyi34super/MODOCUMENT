import { api } from './client';

// Admin dashboard (phase 2, ws22): metrics:read (ADMIN). Counts for the
// caller's own bank only, cached server-side for 30 seconds — `generatedAt`
// says when the numbers were taken. A null counter means "not tracked" (the
// tiles show "—"). Shape:
//   { generatedAt, bank:{code,name},
//     users:{total, active, disabled, locked, mustChangePassword, byRole:{},
//            mfaEnrolled, adminsWithoutMfa, activeLast7d},
//     approvals:{pendingChanges, pendingReleases},
//     ingestion:{today:{byStatus}, last7d:{byStatus},
//                parsedBy:{AI, CODE, AI_PARTIAL}, failures7d},
//     extraction:{last7d:{byStatus}}, alerts:{last7dBySeverity, lastAt},
//     retention:{enabled, days, lastRunAt, lastPurged},
//     templates:{private, shared}, settings:{pendingSecurityChanges, clamped},
//     version:{version, commit, deployedAt},
//     integrations:{runs24h:{ok, failed}, deadLetters, agentsOffline, apiCalls24h} }

export const getDashboard = () => api.get('/api/admin/dashboard');

// v1.2.0 (docs/optimization/contracts.md §5): the user Home page's counts in
// one call instead of four full lists —
//   { templates, ingestion:{total, byStatus:{…}, recent:[IngestionJobSummary ×8]},
//     extraction:{total, running, recent:[…×5]}, imports:{total, recent:[…×5]} }
// (`templates` is read as a count, or as a list when the server sends one).
// A server without it answers 404; DashboardPage then loads the lists.
export const getHomeSummary = () => api.get('/api/dashboard/summary');
