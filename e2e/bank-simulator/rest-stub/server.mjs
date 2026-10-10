// Bank simulator — the bank's REST side, for the Wave-3 integration E2E.
// Plain Node (no dependencies). TEST ONLY: credentials come from the
// environment of a throwaway stack; nothing here is a real bank API.
//
// Bank API (what MoDocument's REST connector calls):
//   POST /oauth/token                  OAuth2 client credentials (client_secret_basic
//                                      or form client_id/client_secret) -> bearer token
//   GET  /api/customers?page=&size=    paginated records {items, page, size, total, next}
//                                      (also cursor paging: ?cursor=<n>; Link rel="next")
//   POST /api/results                  capture pushed records (bearer token OR X-Api-Key);
//                                      Idempotency-Key replays answer the first result
//   POST /webhooks/modocument          webhook receiver: verifies X-MoDocument-Signature
//                                      and the 5-minute X-MoDocument-Timestamp window
// Control plane (for the specs, never for MoDocument):
//   GET  /__captured   GET /__received   GET /__stats   POST /__reset   GET /health
//   POST /__customers  {count} -> regenerate the customer list
//
// Env: BANK_SIM_PORT (18090), BANK_SIM_HOST (127.0.0.1), BANK_SIM_CLIENT_ID,
// BANK_SIM_CLIENT_SECRET, BANK_SIM_API_KEY, BANK_SIM_WEBHOOK_SECRET,
// BANK_SIM_TOKEN_TTL (seconds, 300), BANK_SIM_CUSTOMERS (25).
import crypto from 'node:crypto';
import http from 'node:http';
import { pathToFileURL } from 'node:url';
import { SIGNATURE_HEADER, TIMESTAMP_HEADER, verify } from './signature.mjs';

const MAX_BODY = 5 * 1024 * 1024;

export function customers(count) {
  const segments = ['RETAIL', 'SME', 'CORPORATE'];
  return Array.from({ length: count }, (_, i) => {
    const n = i + 1;
    return {
      customerId: `CUST-${String(n).padStart(4, '0')}`,
      fullName: `Customer ${n}`,
      email: `customer${n}@bank-sim.test`,
      segment: segments[i % segments.length],
      balance: (1000 + n * 137.25).toFixed(2),
      updatedAt: new Date(Date.UTC(2024, 0, 1 + n)).toISOString(),
    };
  });
}

/** Creates the simulator (not listening). `config` overrides the env. */
export function createBankSimulator(config = {}) {
  const cfg = {
    clientId: config.clientId ?? process.env.BANK_SIM_CLIENT_ID ?? '',
    clientSecret: config.clientSecret ?? process.env.BANK_SIM_CLIENT_SECRET ?? '',
    apiKey: config.apiKey ?? process.env.BANK_SIM_API_KEY ?? '',
    webhookSecret: config.webhookSecret ?? process.env.BANK_SIM_WEBHOOK_SECRET ?? '',
    tokenTtl: Number(config.tokenTtl ?? process.env.BANK_SIM_TOKEN_TTL ?? 300),
    customerCount: Number(config.customerCount ?? process.env.BANK_SIM_CUSTOMERS ?? 25),
    now: config.now ?? (() => Date.now()),
  };

  const state = {
    customers: customers(cfg.customerCount),
    tokens: new Map(), // token -> expiresAtMillis
    captured: [], // {at, auth, idempotencyKey, body}
    idempotency: new Map(), // key -> {hash, response}
    received: [], // {at, valid, reason, event, timestamp, body}
    stats: { token: 0, tokenRefused: 0, reads: 0, writes: 0, writesRefused: 0, webhooks: 0, webhooksRefused: 0 },
  };

  function send(res, status, body, headers = {}) {
    const text = body === undefined ? '' : JSON.stringify(body);
    res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...headers });
    res.end(text);
  }

  function readBody(req) {
    return new Promise((resolve, reject) => {
      const chunks = [];
      let size = 0;
      req.on('data', (chunk) => {
        size += chunk.length;
        if (size > MAX_BODY) {
          reject(Object.assign(new Error('body too large'), { status: 413 }));
          req.destroy();
          return;
        }
        chunks.push(chunk);
      });
      req.on('end', () => resolve(Buffer.concat(chunks)));
      req.on('error', reject);
    });
  }

  const safeEqual = (a, b) => {
    const left = Buffer.from(String(a));
    const right = Buffer.from(String(b));
    return left.length === right.length && crypto.timingSafeEqual(left, right);
  };

  /** Which credential authorised a bank-API call: 'bearer' | 'api-key' | null. */
  function authorised(req) {
    const authz = req.headers.authorization || '';
    if (authz.startsWith('Bearer ')) {
      const expires = state.tokens.get(authz.slice(7).trim());
      if (expires && expires > cfg.now()) return 'bearer';
    }
    const key = req.headers['x-api-key'];
    if (cfg.apiKey && key && safeEqual(key, cfg.apiKey)) return 'api-key';
    return null;
  }

  async function token(req, res) {
    const raw = (await readBody(req)).toString('utf8');
    const form = new URLSearchParams(raw);
    let id = form.get('client_id');
    let secret = form.get('client_secret');
    const authz = req.headers.authorization || '';
    if (authz.startsWith('Basic ')) {
      const decoded = Buffer.from(authz.slice(6), 'base64').toString('utf8');
      const at = decoded.indexOf(':');
      id = decodeURIComponent(decoded.slice(0, at));
      secret = decodeURIComponent(decoded.slice(at + 1));
    }
    if (form.get('grant_type') !== 'client_credentials') {
      state.stats.tokenRefused += 1;
      return send(res, 400, { error: 'unsupported_grant_type' });
    }
    if (!cfg.clientId || !id || !secret || !safeEqual(id, cfg.clientId) || !safeEqual(secret, cfg.clientSecret)) {
      state.stats.tokenRefused += 1;
      return send(res, 401, { error: 'invalid_client' }, { 'WWW-Authenticate': 'Basic realm="bank-sim"' });
    }
    const accessToken = crypto.randomBytes(24).toString('base64url');
    state.tokens.set(accessToken, cfg.now() + cfg.tokenTtl * 1000);
    state.stats.token += 1;
    return send(res, 200, { access_token: accessToken, token_type: 'Bearer', expires_in: cfg.tokenTtl });
  }

  function listCustomers(req, res, url) {
    if (!authorised(req)) return send(res, 401, { error: 'unauthorized' });
    state.stats.reads += 1;
    const size = Math.min(Math.max(Number(url.searchParams.get('size') ?? 10) || 10, 1), 100);
    const since = url.searchParams.get('updatedSince');
    const all = since ? state.customers.filter((c) => c.updatedAt > since) : state.customers;
    if (url.searchParams.has('cursor')) {
      const start = Math.max(Number(url.searchParams.get('cursor')) || 0, 0);
      const items = all.slice(start, start + size);
      const next = start + size < all.length ? String(start + size) : null;
      const headers = next ? { Link: `</api/customers?cursor=${next}&size=${size}>; rel="next"` } : {};
      return send(res, 200, { items, cursor: String(start), next, total: all.length }, headers);
    }
    const page = Math.max(Number(url.searchParams.get('page') ?? 1) || 1, 1);
    const items = all.slice((page - 1) * size, page * size);
    const next = page * size < all.length ? page + 1 : null;
    const headers = next ? { Link: `</api/customers?page=${next}&size=${size}>; rel="next"` } : {};
    return send(res, 200, { items, page, size, total: all.length, next }, headers);
  }

  async function captureResults(req, res) {
    const auth = authorised(req);
    const raw = await readBody(req);
    if (!auth) {
      state.stats.writesRefused += 1;
      return send(res, 401, { error: 'unauthorized' });
    }
    let body;
    try {
      body = JSON.parse(raw.toString('utf8') || 'null');
    } catch {
      state.stats.writesRefused += 1;
      return send(res, 400, { error: 'invalid_json' });
    }
    const key = req.headers['idempotency-key'];
    const hash = crypto.createHash('sha256').update(raw).digest('hex');
    if (key && state.idempotency.has(key)) {
      const first = state.idempotency.get(key);
      if (first.hash !== hash) return send(res, 409, { error: 'idempotency_key_reused' });
      return send(res, 200, { ...first.response, replayed: true });
    }
    const records = Array.isArray(body) ? body : Array.isArray(body?.records) ? body.records : [body];
    const entry = { at: new Date(cfg.now()).toISOString(), auth, idempotencyKey: key ?? null, records };
    state.captured.push(entry);
    state.stats.writes += 1;
    const response = { accepted: records.length, reference: `BANK-${state.captured.length}` };
    if (key) state.idempotency.set(key, { hash, response });
    return send(res, 201, response);
  }

  async function webhook(req, res) {
    const raw = await readBody(req);
    const timestamp = req.headers[TIMESTAMP_HEADER];
    const result = verify({
      secret: cfg.webhookSecret,
      signature: req.headers[SIGNATURE_HEADER],
      timestamp,
      body: raw,
      nowSeconds: Math.floor(cfg.now() / 1000),
    });
    let parsed = null;
    try {
      parsed = JSON.parse(raw.toString('utf8'));
    } catch {
      parsed = null;
    }
    state.received.push({
      at: new Date(cfg.now()).toISOString(),
      valid: result.ok,
      reason: result.ok ? null : result.reason,
      timestamp: timestamp ?? null,
      event: parsed?.type ?? parsed?.event ?? null,
      body: parsed,
    });
    if (!result.ok) {
      state.stats.webhooksRefused += 1;
      return send(res, 401, { error: 'invalid_signature', reason: result.reason });
    }
    state.stats.webhooks += 1;
    return send(res, 204);
  }

  async function handle(req, res) {
    const url = new URL(req.url, 'http://bank-sim');
    const route = `${req.method} ${url.pathname}`;
    try {
      switch (route) {
        case 'GET /health':
          return send(res, 200, { status: 'UP' });
        case 'POST /oauth/token':
          return await token(req, res);
        case 'GET /api/customers':
          return listCustomers(req, res, url);
        case 'POST /api/results':
          return await captureResults(req, res);
        case 'POST /webhooks/modocument':
          return await webhook(req, res);
        case 'GET /__captured':
          return send(res, 200, { captured: state.captured });
        case 'GET /__received':
          return send(res, 200, { received: state.received });
        case 'GET /__stats':
          return send(res, 200, state.stats);
        case 'POST /__customers': {
          const body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
          state.customers = customers(Number(body.count ?? cfg.customerCount));
          return send(res, 200, { total: state.customers.length });
        }
        case 'POST /__reset':
          state.tokens.clear();
          state.captured.length = 0;
          state.received.length = 0;
          state.idempotency.clear();
          for (const k of Object.keys(state.stats)) state.stats[k] = 0;
          return send(res, 200, { reset: true });
        default:
          return send(res, 404, { error: 'not_found' });
      }
    } catch (error) {
      return send(res, error.status ?? 500, { error: error.status === 413 ? 'too_large' : 'internal' });
    }
  }

  const server = http.createServer((req, res) => {
    handle(req, res);
  });
  return { server, state, config: cfg };
}

// Run as a process: node server.mjs
if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const { server, config } = createBankSimulator();
  for (const name of ['clientId', 'clientSecret', 'apiKey', 'webhookSecret']) {
    if (!config[name]) console.warn(`[bank-sim] ${name} is not set; that auth path will refuse everything`);
  }
  const port = Number(process.env.BANK_SIM_PORT ?? 18090);
  const host = process.env.BANK_SIM_HOST ?? '127.0.0.1';
  server.listen(port, host, () => console.log(`[bank-sim] REST stub on http://${host}:${port}`));
  const stop = () => server.close(() => process.exit(0));
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
}
