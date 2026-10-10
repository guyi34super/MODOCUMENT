// Bank simulator control for the Wave-3 integration specs
// (e2e/bank-simulator/README.md). The REST stub's control plane and the
// bank database are the "bank side" of every assertion: rows landed in the
// bank's staging table, a signed webhook was verified, a file was dropped.
// Never used to drive MoDocument — that always goes through its API/UI.
import pg from 'pg';
import { env } from './env.js';

function sim() {
  const coordinates = env.bankSim;
  if (!coordinates) {
    throw new Error('bank simulator not started: set E2E_BANK_SIM=1 for run-local.sh (README)');
  }
  return coordinates;
}

async function control(method, path, body) {
  const res = await fetch(sim().restURL + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`bank-sim ${method} ${path} -> ${res.status} ${text}`);
  return text ? JSON.parse(text) : null;
}

export const bankRest = {
  reset: () => control('POST', '/__reset'),
  stats: () => control('GET', '/__stats'),
  /** Webhook deliveries as the receiver judged them: [{at, valid, reason, event, body}]. */
  received: async () => (await control('GET', '/__received')).received,
  /** Records pushed to POST /api/results: [{at, auth, idempotencyKey, records}]. */
  captured: async () => (await control('GET', '/__captured')).captured,
  setCustomers: (count) => control('POST', '/__customers', { count }),
};

/** Runs `fn(client)` as the bank's DBA (bankadmin) — assertions on the bank side only. */
export async function withBankDb(fn) {
  const url = sim().dbURL;
  if (!url) throw new Error('E2E_BANK_SIM_DB_URL is not set');
  const client = new pg.Client({ connectionString: url });
  await client.connect();
  try {
    return await fn(client);
  } finally {
    await client.end();
  }
}

export async function stagingRows(where = 'TRUE', params = []) {
  return withBankDb(async (db) => (await db.query(
    `SELECT * FROM integration.staging_results WHERE ${where} ORDER BY staging_id`, params
  )).rows);
}

/** Moves bank rows forward so a watermark-based inbound pull picks them up again. */
export async function touchCustomers(ids) {
  return withBankDb((db) => db.query(
    'UPDATE core.customer SET updated_at = now() WHERE customer_id = ANY($1)', [ids]
  ));
}

/** Polls `probe()` until it returns a truthy value. */
export async function eventually(probe, { timeoutMs = 60_000, intervalMs = 1000, what = 'condition' } = {}) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await probe();
    if (last) return last;
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  throw new Error(`timed out waiting for ${what} (last: ${JSON.stringify(last)})`);
}
