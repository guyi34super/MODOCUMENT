// Direct database access for SEEDING ONLY, as the schema owner (bypasses
// RLS by design). The suite never asserts through this door — assertions go
// through the API/UI like a user would. Kept to what the product cannot do
// for itself on a fresh install (see seedClonedUser).
import pg from 'pg';
import { env } from './env.js';

export async function withDb(fn) {
  const client = new pg.Client({ connectionString: env.dbURL });
  await client.connect();
  try {
    return await fn(client);
  } finally {
    await client.end();
  }
}

/**
 * A fresh install has exactly ONE bank admin (BootstrapAdminRunner), but
 * every user-security change (USER_CREATE, role change, disable) needs a
 * DIFFERENT approver (maker/checker, gate B7) — so the product alone can
 * never add a second user. The suite breaks that deadlock the only way
 * there is: it clones the bootstrap admin's row (same bank, same password
 * hash) into a new user flagged MustChangePassword, so the first sign-in
 * goes through the normal forced password change (+ MFA enrolment for an
 * ADMIN). Used for the checker admin and for the operator account the
 * ingestion specs run as. Idempotent; returns the user's id.
 */
export async function seedClonedUser({ email, displayName, role, cloneFromEmail }) {
  return withDb(async (db) => {
    await db.query(
      `INSERT INTO AppUsers (Email, PasswordHash, DisplayName, SettingsJson, CreatedDate,
                             UpdatedDate, BankId, Role, MustChangePassword)
       SELECT $1, PasswordHash, $2, '{}', now(), now(), BankId, $4, true
         FROM AppUsers WHERE lower(Email) = lower($3)
       ON CONFLICT (Email) DO NOTHING`,
      [email.toLowerCase(), displayName, cloneFromEmail, role]
    );
    const { rows } = await db.query('SELECT AppUserId FROM AppUsers WHERE lower(Email) = lower($1)', [email]);
    if (rows.length === 0) {
      throw new Error(`seedClonedUser: bootstrap admin ${cloneFromEmail} not found — did the app start?`);
    }
    return rows[0].appuserid;
  });
}

/** Flyway's view of the schema (for version/migration assertions later). */
export async function schemaVersion() {
  return withDb(async (db) => {
    const { rows } = await db.query(
      `SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1`
    );
    return rows[0]?.version ?? null;
  });
}
