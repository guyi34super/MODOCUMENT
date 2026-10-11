package com.modocument.infrastructure.audit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ws28 flood control: ACCESS_DENIED aggregation (at most one row per key per
 * window, counts add up to every denial) and read-audit deduplication.
 */
class AuditThrottleTest {

    private static final Instant T0 = Instant.parse("2026-10-11T10:00:00Z");

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        Instant now = T0;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final AuditThrottle throttle = new AuditThrottle(clock, Duration.ofMinutes(1));

    private static final AuditThrottle.DenialKey KEY =
            new AuditThrottle.DenialKey(7, "user:42", "IngestionJob");

    private static AuditThrottle.DenialContext context(String entityId) {
        return new AuditThrottle.DenialContext(42, null, "READ_WRITE", "10.0.0.1",
                "IngestionJob", entityId, Map.of("kind", "NOT_FOUND_PROBE"));
    }

    @Test
    void aBurstIsOneRowNowAndOneRowWithTheCountWhenTheWindowEnds() {
        AuditThrottle.DenialRow first = throttle.offerDenial(KEY, context("1"));
        assertThat(first).isNotNull();
        assertThat(first.count()).isOne();

        for (int i = 2; i <= 100; i++) {
            clock.now = T0.plusMillis(i * 100L);
            assertThat(throttle.offerDenial(KEY, context(String.valueOf(i))))
                    .as("denial %d inside the window is only counted", i).isNull();
        }
        assertThat(throttle.drainExpired(T0.plusSeconds(59))).as("window still open").isEmpty();

        List<AuditThrottle.DenialRow> tail = throttle.drainExpired(T0.plusSeconds(61));
        assertThat(tail).singleElement().satisfies(row -> {
            assertThat(row.count()).isEqualTo(99);
            assertThat(row.windowStart()).isEqualTo(T0);
            assertThat(row.sampleEntityIds()).hasSizeLessThanOrEqualTo(AuditThrottle.MAX_SAMPLE_IDS);
        });
        // first (1) + tail (99) = every denial
        assertThat(first.count() + tail.get(0).count()).isEqualTo(100);

        // The flush opened the next window: a denial right after it is counted, not written.
        clock.now = T0.plusSeconds(62);
        assertThat(throttle.offerDenial(KEY, context("x"))).isNull();
    }

    @Test
    void aDenialAfterTheWindowCarriesTheSuppressedCount() {
        throttle.offerDenial(KEY, context("1"));
        clock.now = T0.plusSeconds(10);
        throttle.offerDenial(KEY, context("2"));
        throttle.offerDenial(KEY, context("3"));

        clock.now = T0.plusSeconds(75);
        AuditThrottle.DenialRow row = throttle.offerDenial(KEY, context("4"));
        assertThat(row).isNotNull();
        assertThat(row.count()).as("2 suppressed + this one").isEqualTo(3);
        assertThat(throttle.drainExpired(T0.plusSeconds(200))).isEmpty();
    }

    @Test
    void keysAreIndependentAndQuietKeysAreForgotten() {
        AuditThrottle.DenialKey other = new AuditThrottle.DenialKey(7, "ip:10.9.9.9", "BearerToken");
        assertThat(throttle.offerDenial(KEY, context("1"))).isNotNull();
        assertThat(throttle.offerDenial(other, context("1"))).isNotNull();
        assertThat(throttle.drainExpired(T0.plusSeconds(120))).as("nothing suppressed").isEmpty();
        clock.now = T0.plusSeconds(121);
        assertThat(throttle.offerDenial(KEY, context("1")).count()).isOne();
    }

    @Test
    void manyKeysShareAnOverflowKeyInsteadOfGrowingWithoutBound() {
        int written = 0;
        for (int i = 0; i < AuditThrottle.MAX_DENIAL_KEYS + 500; i++) {
            AuditThrottle.DenialKey key = new AuditThrottle.DenialKey(1, "ip:10.0." + i, "BearerToken");
            if (throttle.offerDenial(key, context(null)) != null) {
                written++;
            }
        }
        assertThat(written).as("one row per distinct key, then ONE for the overflow key")
                .isEqualTo(AuditThrottle.MAX_DENIAL_KEYS + 1);
    }

    @Test
    void dedupeWritesTheFirstReadPerWindowAndReleaseRetries() {
        assertThat(throttle.tryAcquire("k", Duration.ofHours(1))).isTrue();
        assertThat(throttle.tryAcquire("k", Duration.ofHours(1))).isFalse();
        clock.now = T0.plus(Duration.ofMinutes(59));
        assertThat(throttle.tryAcquire("k", Duration.ofHours(1))).isFalse();
        clock.now = T0.plus(Duration.ofMinutes(61));
        assertThat(throttle.tryAcquire("k", Duration.ofHours(1))).isTrue();

        throttle.release("k");
        assertThat(throttle.tryAcquire("k", Duration.ofHours(1))).as("released after a failed write")
                .isTrue();
        assertThat(throttle.tryAcquire("other", Duration.ofHours(1))).isTrue();
    }
}
