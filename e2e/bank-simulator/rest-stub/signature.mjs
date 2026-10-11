// Webhook signature scheme of the Bank Integration Hub (contracts §6 plan,
// SE4 webhooks): MoDocument signs every outbound webhook with
//
//   X-MoDocument-Timestamp: <unix seconds>
//   X-MoDocument-Signature: sha256=<hex(HMAC_SHA256(secret, timestamp + "." + rawBody))>
//
// and the receiver refuses anything outside a 5-minute replay window. This is
// the bank side of that contract — the verifier a bank's IT would write, as
// the developer guide documents it. Pure functions, no dependencies, so the
// REST stub and its node:test suite share one implementation.
import crypto from 'node:crypto';

export const SIGNATURE_HEADER = 'x-modocument-signature';
export const TIMESTAMP_HEADER = 'x-modocument-timestamp';
export const DEFAULT_WINDOW_SECONDS = 300;

/** `sha256=<hex>` for a timestamp (string or number) and the raw body (string or Buffer). */
export function sign(secret, timestamp, body) {
  const mac = crypto.createHmac('sha256', secret);
  mac.update(`${timestamp}.`);
  mac.update(Buffer.isBuffer(body) ? body : Buffer.from(String(body ?? ''), 'utf8'));
  return `sha256=${mac.digest('hex')}`;
}

/**
 * Verifies one delivery. Never throws; returns `{ ok: true }` or
 * `{ ok: false, reason }` with reason one of: missing-secret,
 * missing-signature, missing-timestamp, bad-timestamp, stale-timestamp,
 * future-timestamp, bad-signature-format, signature-mismatch.
 *
 * @param {object} p
 * @param {string} p.secret shared webhook secret
 * @param {string|undefined} p.signature the X-MoDocument-Signature header
 * @param {string|undefined} p.timestamp the X-MoDocument-Timestamp header (unix seconds)
 * @param {string|Buffer} p.body the RAW request body, byte for byte
 * @param {number} [p.nowSeconds] clock (tests)
 * @param {number} [p.windowSeconds] replay window, default 300
 */
export function verify({ secret, signature, timestamp, body, nowSeconds, windowSeconds = DEFAULT_WINDOW_SECONDS }) {
  if (!secret) return { ok: false, reason: 'missing-secret' };
  if (!signature) return { ok: false, reason: 'missing-signature' };
  if (timestamp === undefined || timestamp === null || timestamp === '') {
    return { ok: false, reason: 'missing-timestamp' };
  }
  const ts = String(timestamp).trim();
  if (!/^\d{1,12}$/.test(ts)) return { ok: false, reason: 'bad-timestamp' };
  const now = nowSeconds ?? Math.floor(Date.now() / 1000);
  const age = now - Number(ts);
  if (age > windowSeconds) return { ok: false, reason: 'stale-timestamp' };
  if (age < -windowSeconds) return { ok: false, reason: 'future-timestamp' };

  const match = /^sha256=([0-9a-f]{64})$/i.exec(String(signature).trim());
  if (!match) return { ok: false, reason: 'bad-signature-format' };
  const expected = Buffer.from(sign(secret, ts, body).slice('sha256='.length), 'hex');
  const given = Buffer.from(match[1].toLowerCase(), 'hex');
  // Constant time: same length is guaranteed by the regex (32 bytes).
  if (!crypto.timingSafeEqual(expected, given)) return { ok: false, reason: 'signature-mismatch' };
  return { ok: true };
}
