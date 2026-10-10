package com.modocument.domain.model;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** RFC 9562 shape of the dependency-free UUIDv7 generator. */
class Uuid7Test {

    @Test
    void generatesVersion7Variant2() {
        UUID uuid = Uuid7.generate();
        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2); // IETF variant (10xx)
    }

    @Test
    void embedsTheCurrentUnixMillisecondTimestamp() {
        long before = System.currentTimeMillis();
        UUID uuid = Uuid7.generate();
        long after = System.currentTimeMillis();
        long embedded = uuid.getMostSignificantBits() >>> 16;
        assertThat(embedded).isBetween(before, after);
    }

    @Test
    void laterValuesSortAfterEarlierOnesAcrossMilliseconds() throws InterruptedException {
        UUID first = Uuid7.generate();
        Thread.sleep(2);
        UUID second = Uuid7.generate();
        // Compare the unsigned timestamp prefix (UUID.compareTo is signed).
        assertThat(first.getMostSignificantBits() >>> 16)
                .isLessThan(second.getMostSignificantBits() >>> 16);
    }

    @Test
    void doesNotCollideWithinOneBurst() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(Uuid7.generate())).isTrue();
        }
    }
}
