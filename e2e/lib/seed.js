// Seed helpers: build the accounts and data a spec needs through the
// product's own APIs (maker/checker included), falling back to SQL only for
// the fresh-install bootstrap problem (lib/db.js explains why).
import { env } from './env.js';
import { getAccount, saveAccount } from './accounts.js';
import { approveChange, sessionFor, strongPassword, submitUserCreate } from './api.js';
import { seedClonedUser } from './db.js';

export const BOOTSTRAP = () => env.bootstrapEmail.toLowerCase();
export const SECOND_ADMIN = () => `e2e-admin2-${env.runId}@e2e.modocument.test`;
export const OPERATOR = () => `e2e-operator-${env.runId}@e2e.modocument.test`;

/** Registers the bootstrap admin in the account book (password from the run's env). */
export function registerBootstrapAdmin() {
  const email = BOOTSTRAP();
  if (!getAccount(email)?.password) {
    saveAccount(email, { password: env.bootstrapPassword, role: 'ADMIN', origin: 'bootstrap' });
  }
  return email;
}

/**
 * The bootstrap admin's CURRENT password. Since phase 2 (OBS-02) the
 * bootstrap password is temporary: the first sign-in changes it, and the
 * account book keeps the new one. Seeded clones copy the CURRENT hash, so
 * they must be registered with this password, not the env one.
 */
export function bootstrapPassword() {
  return getAccount(registerBootstrapAdmin())?.password ?? env.bootstrapPassword;
}

/** Bootstrap admin API session (enrols MFA on first use). */
export async function bootstrapAdmin() {
  return sessionFor(registerBootstrapAdmin());
}

async function clonedAccount(email, role, displayName) {
  if (!getAccount(email)?.password) {
    const password = bootstrapPassword();
    await seedClonedUser({ email, displayName, role, cloneFromEmail: BOOTSTRAP() });
    // The cloned row carries the bootstrap admin's CURRENT hash; the first
    // sign-in changes it.
    saveAccount(email, { password, role, origin: 'sql-seed' });
  }
  return sessionFor(email);
}

/** Email of bootstrap platform admin `n` (1 or 2), or null without platform admins. */
export const PLATFORM_ADMIN = (n) => env.platformAdmins[n - 1]?.email?.toLowerCase() ?? null;

/**
 * Session of bootstrap platform admin `n` (1 or 2), signed in through the
 * forced password change and MFA enrolment like any first login. Null when
 * the stack was started without platform admins (specs then skip).
 */
export async function platformAdmin(n) {
  const admin = env.platformAdmins[n - 1];
  if (!admin) return null;
  const email = PLATFORM_ADMIN(n);
  if (!getAccount(email)?.password) {
    saveAccount(email, { password: admin.password, role: 'PLATFORM_ADMIN', origin: 'bootstrap' });
  }
  return sessionFor(email);
}

/**
 * The second bank admin (the checker for everything the bootstrap admin
 * submits). Seeded by SQL once, then signed in through the normal forced
 * password change + MFA enrolment.
 */
export async function secondAdmin() {
  return clonedAccount(SECOND_ADMIN(), 'ADMIN', 'E2E Second Admin');
}

/**
 * A READ_WRITE operator (no MFA owed) for the document-flow specs: they then
 * exercise templates/ingestion under RLS without depending on an admin login.
 */
export async function operator() {
  return clonedAccount(OPERATOR(), 'READ_WRITE', 'E2E Operator');
}

let counter = 0;
/** Unique, run-scoped name for anything a spec creates. */
export function uniqueName(prefix) {
  counter += 1;
  return `${prefix}-${env.runId}-${Date.now().toString(36)}-${counter}`;
}

/**
 * Creates a bank user the product way: admin A submits USER_CREATE (202),
 * admin B approves. Returns {email, temporaryPassword, change}. The user has
 * MustChangePassword set; the temporary password is stored in the book.
 */
export async function createUserViaMakerChecker({ role = 'READ_WRITE', displayName } = {}) {
  const maker = await bootstrapAdmin();
  const checker = await secondAdmin();
  const email = `${uniqueName(`e2e-${role.toLowerCase().replace('_', '')}`)}@e2e.modocument.test`.toLowerCase();
  const temporaryPassword = strongPassword();
  const change = await submitUserCreate(maker, {
    email,
    displayName: displayName ?? `E2E ${role}`,
    role,
    temporaryPassword,
  });
  const decided = await approveChange(checker, change.pendingChangeId);
  saveAccount(email, { password: temporaryPassword, role, origin: 'maker-checker' });
  return { email, temporaryPassword, change: decided };
}
