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

/** Bootstrap admin API session (enrols MFA on first use). */
export async function bootstrapAdmin() {
  return sessionFor(registerBootstrapAdmin());
}

async function clonedAccount(email, role, displayName) {
  if (!getAccount(email)?.password) {
    registerBootstrapAdmin();
    await seedClonedUser({ email, displayName, role, cloneFromEmail: BOOTSTRAP() });
    // The cloned row carries the bootstrap password hash; the first sign-in changes it.
    saveAccount(email, { password: env.bootstrapPassword, role, origin: 'sql-seed' });
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
