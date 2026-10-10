// node --test e2e/bank-simulator/rest-stub/   (no dependencies)
//
// The bank-side webhook verifier against the contract:
//   X-MoDocument-Signature: sha256=hex(HMAC_SHA256(secret, timestamp + "." + body))
//   X-MoDocument-Timestamp within 5 minutes.
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { describe, test } from 'node:test';
import { DEFAULT_WINDOW_SECONDS, sign, verify } from './signature.mjs';

const SECRET = 'test-only-webhook-secret';
const NOW = 1_717_171_717;
const BODY = '{"type":"INGESTION_VALIDATED","data":{"jobId":42}}';

/** Independent re-implementation of the formula (not via sign()). */
function reference(secret, timestamp, body) {
  return `sha256=${crypto.createHmac('sha256', secret).update(`${timestamp}.${body}`, 'utf8').digest('hex')}`;
}

describe('sign', () => {
  test('matches the documented formula, hex lowercase with the sha256= prefix', () => {
    const value = sign(SECRET, NOW, BODY);
    assert.equal(value, reference(SECRET, NOW, BODY));
    assert.match(value, /^sha256=[0-9a-f]{64}$/);
  });

  test('signs the raw bytes: a Buffer and the same string agree; non-ASCII is UTF-8', () => {
    const body = '{"name":"Élodie Moreau — 東京"}';
    assert.equal(sign(SECRET, NOW, Buffer.from(body, 'utf8')), sign(SECRET, NOW, body));
    assert.equal(sign(SECRET, NOW, body), reference(SECRET, NOW, body));
  });

  test('binds the timestamp: the same body at another second signs differently', () => {
    assert.notEqual(sign(SECRET, NOW, BODY), sign(SECRET, NOW + 1, BODY));
  });
});

describe('verify', () => {
  const good = () => ({
    secret: SECRET,
    timestamp: String(NOW),
    body: BODY,
    signature: reference(SECRET, NOW, BODY),
    nowSeconds: NOW,
  });

  test('accepts a correctly signed, fresh delivery', () => {
    assert.deepEqual(verify(good()), { ok: true });
  });

  test('accepts an upper-case hex digest and surrounding whitespace', () => {
    const p = good();
    p.signature = ` sha256=${p.signature.slice(7).toUpperCase()} `;
    assert.deepEqual(verify(p), { ok: true });
  });

  test('accepts the edges of the 5-minute window, refuses one second beyond', () => {
    assert.equal(DEFAULT_WINDOW_SECONDS, 300);
    for (const [delta, expected] of [
      [300, { ok: true }],
      [-300, { ok: true }],
      [301, { ok: false, reason: 'stale-timestamp' }],
      [-301, { ok: false, reason: 'future-timestamp' }],
    ]) {
      assert.deepEqual(verify({ ...good(), nowSeconds: NOW + delta }), expected, `delta ${delta}`);
    }
  });

  test('refuses a replay of a valid delivery after the window (stale timestamp)', () => {
    assert.deepEqual(verify({ ...good(), nowSeconds: NOW + 3600 }), { ok: false, reason: 'stale-timestamp' });
  });

  test('refuses a tampered body, a re-signed timestamp and a wrong secret', () => {
    assert.equal(verify({ ...good(), body: BODY.replace('42', '43') }).reason, 'signature-mismatch');
    assert.equal(verify({ ...good(), timestamp: String(NOW - 10) }).reason, 'signature-mismatch');
    assert.equal(verify({ ...good(), secret: 'another-secret' }).reason, 'signature-mismatch');
  });

  test('refuses missing or malformed inputs with a specific reason, never throwing', () => {
    assert.equal(verify({ ...good(), secret: '' }).reason, 'missing-secret');
    assert.equal(verify({ ...good(), signature: undefined }).reason, 'missing-signature');
    assert.equal(verify({ ...good(), timestamp: undefined }).reason, 'missing-timestamp');
    assert.equal(verify({ ...good(), timestamp: '17e8' }).reason, 'bad-timestamp');
    assert.equal(verify({ ...good(), timestamp: '-5' }).reason, 'bad-timestamp');
    assert.equal(verify({ ...good(), signature: 'sha1=abc' }).reason, 'bad-signature-format');
    assert.equal(verify({ ...good(), signature: 'sha256=' + 'z'.repeat(64) }).reason, 'bad-signature-format');
    assert.equal(verify({ ...good(), signature: reference(SECRET, NOW, BODY).slice(0, 70) }).reason, 'bad-signature-format');
  });

  test('uses a constant-time comparison (timingSafeEqual) on equal-length digests', (t) => {
    const spy = t.mock.method(crypto, 'timingSafeEqual');
    verify(good());
    assert.equal(spy.mock.callCount(), 1);
  });
});
