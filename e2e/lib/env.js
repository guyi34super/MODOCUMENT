// Single source of the E2E stack coordinates. Everything comes from the
// environment (run-local.sh and docker-compose.e2e.yml export it); nothing
// secret has a default here — the bootstrap credentials are generated per run.
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const E2E_ROOT = path.resolve(here, '..');

function required(name) {
  const value = process.env[name];
  if (!value) {
    throw new Error(
      `${name} is not set. Run the suite through e2e/run-local.sh (or the compose stack), ` +
        'or export the variables listed in e2e/README.md.'
    );
  }
  return value;
}

export const env = {
  /** The SPA origin (vite preview locally, nginx in compose); API calls go through it too. */
  get baseURL() {
    return process.env.E2E_BASE_URL || 'http://127.0.0.1:4173';
  },
  /** Fake LLM control plane (POST /__mode, GET /__stats). */
  get llmURL() {
    return process.env.E2E_LLM_URL || 'http://127.0.0.1:18089';
  },
  /** Schema-owner connection for seeding (postgres://user:pw@host:port/db). */
  get dbURL() {
    return required('E2E_DB_URL');
  },
  get bootstrapEmail() {
    return required('E2E_BOOTSTRAP_ADMIN_EMAIL');
  },
  get bootstrapPassword() {
    return required('E2E_BOOTSTRAP_ADMIN_PASSWORD');
  },
  /** Run-scoped state (account secrets, cached tokens); wiped per run by run-local.sh. */
  get stateDir() {
    return process.env.E2E_STATE_DIR || path.join(E2E_ROOT, '.state');
  },
  /**
   * Which database role the BACKEND runs as: 'rls' = modocument_app, the
   * production two-role model (RLS enforced); 'owner' = the schema owner
   * (RLS bypassed — a temporary workaround while BUG-01 is open).
   */
  get appRole() {
    return process.env.E2E_DB_APP_ROLE || 'rls';
  },
  /**
   * The two bootstrap PLATFORM_ADMIN accounts (ws21), generated per run by
   * run-local.sh / the compose job: E2E_PLATFORM_ADMIN_{1,2}_{EMAIL,PASSWORD}.
   * Empty when the stack was started without them — platform specs skip.
   */
  get platformAdmins() {
    return [1, 2]
      .map((n) => ({
        email: process.env[`E2E_PLATFORM_ADMIN_${n}_EMAIL`],
        password: process.env[`E2E_PLATFORM_ADMIN_${n}_PASSWORD`],
      }))
      .filter((admin) => admin.email && admin.password);
  },
  /** Bank simulator coordinates (Wave 3, e2e/bank-simulator); null when not started. */
  get bankSim() {
    const rest = process.env.E2E_BANK_SIM_REST_URL;
    if (!rest) return null;
    return {
      restURL: rest.replace(/\/$/, ''),
      dbURL: process.env.E2E_BANK_SIM_DB_URL || null,
      sftpHost: process.env.E2E_BANK_SIM_SFTP_HOST || '127.0.0.1',
      sftpPort: Number(process.env.E2E_BANK_SIM_SFTP_PORT || 2222),
      webhookSecret: process.env.E2E_BANK_SIM_WEBHOOK_SECRET || null,
    };
  },
  /** Short unique suffix so names never collide when the suite re-runs on one database. */
  get runId() {
    return process.env.E2E_RUN_ID || 'local';
  },
};
