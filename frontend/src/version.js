/**
 * Release version of this SPA bundle (ws24): package.json's "version",
 * injected at build time by vite.config.js (`define.__APP_VERSION__`).
 * Compare it with GET /api/system/version → `version` to detect that the
 * backend was upgraded under an open tab. "dev" only when the constant was
 * not injected (a tool that bypasses the Vite config).
 */
export const APP_VERSION = typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : 'dev';
