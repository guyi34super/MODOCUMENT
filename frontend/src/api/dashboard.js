import { api } from './client';

// Admin dashboard (phase 2, ws22): metrics:read (ADMIN). Counts for the
// caller's own bank only, cached server-side for 30 seconds — `generatedAt`
// says when the numbers were taken. Shape (sections owned by later
// workstreams stay zero/null until their data exists):
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
