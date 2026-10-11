// RFC 6238 TOTP (HMAC-SHA1, 30 s, 6 digits) — the exact parameters of the
// backend's infrastructure/security/totp/Totp.java — implemented on
// node:crypto so the suite has no OTP dependency.
//
// Replay rule the backend enforces (MfaUseCase.verifyTotp): a verify only
// succeeds for a step counter strictly greater than the last one used, and
// the server accepts the current step ±1. So right after a login that used
// step N, step N+1 is already acceptable (it is "now + 1"); step N+2 needs
// the clock to reach N+1. nextCode() picks the lowest acceptable step and
// waits only when it must (so two logins per 30 s window never wait).
import crypto from 'node:crypto';

export const STEP_SECONDS = 30;
export const DIGITS = 6;

const BASE32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

export function base32Decode(input) {
  const clean = String(input).toUpperCase().replace(/=+$/g, '').replace(/[\s-]/g, '');
  let bits = 0;
  let value = 0;
  const out = [];
  for (const char of clean) {
    const index = BASE32.indexOf(char);
    if (index < 0) throw new Error(`invalid base32 character '${char}'`);
    value = (value << 5) | index;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

/** RFC 4226 HOTP with dynamic truncation. `key` is a Buffer. */
export function hotp(key, counter, digits = DIGITS, algorithm = 'sha1') {
  const message = Buffer.alloc(8);
  message.writeBigUInt64BE(BigInt(counter));
  const hash = crypto.createHmac(algorithm, key).update(message).digest();
  const offset = hash[hash.length - 1] & 0x0f;
  const binary =
    ((hash[offset] & 0x7f) << 24) |
    ((hash[offset + 1] & 0xff) << 16) |
    ((hash[offset + 2] & 0xff) << 8) |
    (hash[offset + 3] & 0xff);
  return String(binary % 10 ** digits).padStart(digits, '0');
}

export const counterAt = (epochMillis = Date.now()) => Math.floor(epochMillis / 1000 / STEP_SECONDS);

/** The code for a Base32 secret at a given step counter. */
export function codeAt(base32Secret, counter) {
  return hotp(base32Decode(base32Secret), counter);
}

/** The code for "now". */
export function totp(base32Secret, epochMillis = Date.now()) {
  return codeAt(base32Secret, counterAt(epochMillis));
}

/**
 * The next code the server will accept given the last step it consumed.
 * @returns {Promise<{code: string, counter: number}>}
 */
export async function nextCode(base32Secret, lastUsedCounter = null) {
  for (;;) {
    const now = counterAt();
    // Never below "now": a now-1 code goes stale at the next step boundary.
    const candidate = lastUsedCounter == null ? now : Math.max(now, lastUsedCounter + 1);
    if (candidate <= now + 1) {
      return { code: codeAt(base32Secret, candidate), counter: candidate };
    }
    // candidate is now+2 or later: sleep until the clock moves one step on.
    const msIntoStep = Date.now() % (STEP_SECONDS * 1000);
    await new Promise((resolve) => setTimeout(resolve, STEP_SECONDS * 1000 - msIntoStep + 250));
  }
}
