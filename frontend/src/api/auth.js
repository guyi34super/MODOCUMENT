import { api } from './client';

// Registration is admin-only now — there is no public register endpoint.

export const login = (email, password) => api.post('/api/auth/login', { email, password });

/**
 * Configured OIDC providers ([{ id, name }]) for the login page's SSO
 * buttons; public, answers [] when no provider is configured.
 */
export const providers = () => api.get('/api/auth/providers');

/**
 * Exchanges the httpOnly refresh cookie for a fresh access token
 * ({ token, user }). Used by the OIDC landing page (/login/complete) — the
 * token never travels in the redirect URL, only in this response body.
 */
export const refresh = () => api.post('/api/auth/refresh');

/**
 * Revokes the access token and every refresh token server-side. A 401 here
 * means the session is already gone — never a reason for the
 * "session expired" redirect (BUG-02).
 */
export const logout = () => api.post('/api/auth/logout', undefined, { noExpiryRedirect: true });

export const me = () => api.get('/api/auth/me');

export const updateProfile = (displayName, settings) =>
  api.put('/api/auth/profile', { displayName, settings });

/**
 * Changes the caller's password. The backend then ENDS every session of the
 * account (TokenVersion bump, refresh tokens revoked, cookie cleared): the
 * caller must sign in again — use AuthContext.logout({ revoke: false }).
 */
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
