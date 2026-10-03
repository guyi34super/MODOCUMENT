package com.modocument.domain.model;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Dependency-free UUIDv7 generator (RFC 9562): 48-bit Unix millisecond
 * timestamp, 4-bit version (7), 12 random bits, 2-bit variant (10), 62
 * random bits. Used for identifiers that are generated app-side but should
 * still index and sort roughly by creation time (AuditEvent primary keys,
 * minted request ids) — unlike a random UUIDv4, consecutive values land on
 * neighbouring B-tree pages.
 *
 * <p>Randomness comes from a single shared {@link SecureRandom} (thread-safe;
 * 74 random bits per value make a same-millisecond collision negligible).
 */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuid7() {
    }

    public static UUID generate() {
        long now = System.currentTimeMillis();
        long randA = RANDOM.nextLong();
        long randB = RANDOM.nextLong();
        // unix_ts_ms(48) | ver=7(4) | rand_a(12)
        long msb = (now << 16) | 0x7000L | (randA & 0x0FFFL);
        // variant=10(2) | rand_b(62)
        long lsb = 0x8000000000000000L | (randB & 0x3FFFFFFFFFFFFFFFL);
        return new UUID(msb, lsb);
    }
}
