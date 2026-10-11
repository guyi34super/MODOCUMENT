// Run-scoped account book: what the suite knows about each account it
// drives (current password, TOTP secret, last consumed TOTP step, a cached
// access token). Persisted as JSON under E2E_STATE_DIR so the global setup,
// every spec file and a re-run against the same database agree. The state
// dir is per run and gitignored — it holds throwaway credentials of a
// throwaway stack, never anything real.
import fs from 'node:fs';
import path from 'node:path';
import { env } from './env.js';

const file = () => path.join(env.stateDir, 'accounts.json');

function readAll() {
  try {
    return JSON.parse(fs.readFileSync(file(), 'utf8'));
  } catch {
    return {};
  }
}

function writeAll(all) {
  fs.mkdirSync(env.stateDir, { recursive: true });
  const tmp = `${file()}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(all, null, 2), { mode: 0o600 });
  fs.renameSync(tmp, file());
}

const key = (email) => String(email).trim().toLowerCase();

export function getAccount(email) {
  return readAll()[key(email)] ?? null;
}

/** Shallow-merges `patch` into the stored account and returns the result. */
export function saveAccount(email, patch) {
  const all = readAll();
  const merged = { ...(all[key(email)] ?? { email: key(email) }), ...patch };
  all[key(email)] = merged;
  writeAll(all);
  return merged;
}

export function forgetToken(email) {
  saveAccount(email, { token: null, tokenExp: null });
}

/** Seconds-since-epoch exp claim of a JWT (no signature check — bookkeeping only). */
export function jwtExp(token) {
  try {
    const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
    return typeof payload.exp === 'number' ? payload.exp : null;
  } catch {
    return null;
  }
}

/** Cached token still good for at least `marginSeconds`? */
export function usableToken(account, marginSeconds = 90) {
  if (!account?.token || !account.tokenExp) return null;
  return account.tokenExp - Date.now() / 1000 > marginSeconds ? account.token : null;
}
