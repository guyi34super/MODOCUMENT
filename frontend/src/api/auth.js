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

// ----------------------------------------------------------- MFA (ws06)

/** Second phase of login: challenge token + TOTP (or recovery) code → tokens. */
export const verifyMfa = (challenge, code) =>
  api.post('/api/auth/mfa/verify', { challenge, code });

export const mfaStatus = () => api.get('/api/auth/mfa');

/**
 * Starts enrolment. During a forced enrolment (ADMIN without MFA) the
 * challenge token from the login response authenticates these two calls;
 * normally the stored session token does.
 */
export const enrollMfa = (challengeToken) =>
  api.post('/api/auth/mfa/enroll', undefined,
    challengeToken ? { authToken: challengeToken } : {});

export const activateMfa = (code, challengeToken) =>
  api.post('/api/auth/mfa/activate', { code },
    challengeToken ? { authToken: challengeToken } : {});

/** Disable needs a fresh proof: a current TOTP code or an unused recovery code. */
export const disableMfa = (code) => api.del('/api/auth/mfa', { code });
