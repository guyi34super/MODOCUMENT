// node --test e2e/bank-simulator/rest-stub/   (no dependencies)
//
// The REST stub's own contract, so the Wave-3 E2E can trust it: OAuth2
// client credentials, paging, capture with idempotency, and the webhook
// receiver's signature + replay-window enforcement.
import assert from 'node:assert/strict';
import { after, before, describe, test } from 'node:test';
import { createBankSimulator } from './server.mjs';
import { sign } from './signature.mjs';

const CONFIG = {
  clientId: 'test-only-client',
  clientSecret: 'test-only-client-secret',
  apiKey: 'test-only-api-key',
  webhookSecret: 'test-only-webhook-secret',
  customerCount: 23,
};

let sim;
let base;

before(async () => {
  sim = createBankSimulator(CONFIG);
  await new Promise((resolve) => sim.server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${sim.server.address().port}`;
});

after(() => new Promise((resolve) => sim.server.close(resolve)));

const call = async (method, path, { headers = {}, body } = {}) => {
  const res = await fetch(base + path, { method, headers, body });
  const text = await res.text();
  return { status: res.status, headers: res.headers, body: text ? JSON.parse(text) : null };
};

const basic = (id, secret) => `Basic ${Buffer.from(`${id}:${secret}`).toString('base64')}`;

async function bearer() {
  const res = await call('POST', '/oauth/token', {
    headers: {
      Authorization: basic(CONFIG.clientId, CONFIG.clientSecret),
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: 'grant_type=client_credentials',
  });
  assert.equal(res.status, 200);
  return `Bearer ${res.body.access_token}`;
}

describe('OAuth2 client credentials', () => {
  test('issues a bearer token for client_secret_basic and for form credentials', async () => {
    const res = await call('POST', '/oauth/token', {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: `grant_type=client_credentials&client_id=${CONFIG.clientId}&client_secret=${CONFIG.clientSecret}`,
    });
    assert.equal(res.status, 200);
    assert.equal(res.body.token_type, 'Bearer');
    assert.ok(res.body.expires_in > 0);
    assert.ok(await bearer());
  });

  test('refuses a wrong secret (401) and another grant type (400)', async () => {
    const wrong = await call('POST', '/oauth/token', {
      headers: { Authorization: basic(CONFIG.clientId, 'nope'), 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'grant_type=client_credentials',
    });
    assert.equal(wrong.status, 401);
    const grant = await call('POST', '/oauth/token', {
      headers: { Authorization: basic(CONFIG.clientId, CONFIG.clientSecret), 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'grant_type=password',
    });
    assert.equal(grant.status, 400);
  });
});

describe('GET /api/customers', () => {
  test('needs a bearer token or the API key', async () => {
    assert.equal((await call('GET', '/api/customers')).status, 401);
    assert.equal((await call('GET', '/api/customers', { headers: { Authorization: 'Bearer forged' } })).status, 401);
    assert.equal((await call('GET', '/api/customers', { headers: { 'X-Api-Key': CONFIG.apiKey } })).status, 200);
  });

  test('pages by number with next + Link until every record was served once', async () => {
    const auth = await bearer();
    const seen = [];
    let page = 1;
    while (page) {
      const res = await call('GET', `/api/customers?page=${page}&size=10`, { headers: { Authorization: auth } });
      assert.equal(res.status, 200);
      seen.push(...res.body.items.map((c) => c.customerId));
      if (res.body.next) assert.match(res.headers.get('link'), /rel="next"/);
      page = res.body.next;
    }
    assert.equal(seen.length, 23);
    assert.equal(new Set(seen).size, 23);
  });

  test('pages by cursor too', async () => {
    const auth = await bearer();
    let cursor = '0';
    let total = 0;
    while (cursor !== null) {
      const res = await call('GET', `/api/customers?cursor=${cursor}&size=7`, { headers: { Authorization: auth } });
      total += res.body.items.length;
      cursor = res.body.next;
    }
    assert.equal(total, 23);
  });
});

describe('POST /api/results', () => {
  test('captures pushed records and replays an Idempotency-Key; a reused key with another body is 409', async () => {
    const auth = await bearer();
    const body = JSON.stringify({ records: [{ customerId: 'CUST-0001', status: 'VALID' }] });
    const headers = { Authorization: auth, 'Content-Type': 'application/json', 'Idempotency-Key': 'run-1-batch-1' };
    const first = await call('POST', '/api/results', { headers, body });
    assert.equal(first.status, 201);
    const replay = await call('POST', '/api/results', { headers, body });
    assert.equal(replay.status, 200);
    assert.equal(replay.body.replayed, true);
    assert.equal(replay.body.reference, first.body.reference);
    const reused = await call('POST', '/api/results', {
      headers,
      body: JSON.stringify({ records: [{ customerId: 'CUST-0002' }] }),
    });
    assert.equal(reused.status, 409);
    const captured = await call('GET', '/__captured');
    assert.equal(captured.body.captured.filter((c) => c.idempotencyKey === 'run-1-batch-1').length, 1);
  });

  test('refuses an unauthenticated push', async () => {
    const res = await call('POST', '/api/results', { headers: { 'Content-Type': 'application/json' }, body: '[]' });
    assert.equal(res.status, 401);
  });
});

describe('POST /webhooks/modocument', () => {
  const deliver = (body, { timestamp = Math.floor(Date.now() / 1000), secret = CONFIG.webhookSecret, signature } = {}) =>
    call('POST', '/webhooks/modocument', {
      headers: {
        'Content-Type': 'application/json',
        'X-MoDocument-Timestamp': String(timestamp),
        'X-MoDocument-Signature': signature ?? sign(secret, timestamp, body),
      },
      body,
    });

  test('accepts a valid signature (204) and logs the delivery as valid', async () => {
    const body = JSON.stringify({ type: 'INGESTION_VALIDATED', data: { jobId: 7 } });
    assert.equal((await deliver(body)).status, 204);
    const log = (await call('GET', '/__received')).body.received;
    assert.deepEqual(
      { valid: log.at(-1).valid, event: log.at(-1).event },
      { valid: true, event: 'INGESTION_VALIDATED' }
    );
  });

  test('refuses a stale timestamp, a wrong secret and a tampered body (401 with a reason)', async () => {
    const body = JSON.stringify({ type: 'JOB_STATUS' });
    const stale = await deliver(body, { timestamp: Math.floor(Date.now() / 1000) - 301 });
    assert.equal(stale.status, 401);
    assert.equal(stale.body.reason, 'stale-timestamp');
    const wrong = await deliver(body, { secret: 'another' });
    assert.equal(wrong.body.reason, 'signature-mismatch');
    const ts = Math.floor(Date.now() / 1000);
    const tampered = await deliver(body.replace('JOB', 'JAB'), { timestamp: ts, signature: sign(CONFIG.webhookSecret, ts, body) });
    assert.equal(tampered.body.reason, 'signature-mismatch');
    const log = (await call('GET', '/__received')).body.received;
    assert.ok(log.slice(-3).every((entry) => entry.valid === false));
  });

  test('POST /__reset clears the logs', async () => {
    await call('POST', '/__reset');
    assert.deepEqual((await call('GET', '/__received')).body.received, []);
    assert.deepEqual((await call('GET', '/__captured')).body.captured, []);
  });
});
