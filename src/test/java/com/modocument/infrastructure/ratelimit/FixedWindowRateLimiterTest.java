package com.modocument.infrastructure.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    @Test
    void grantsUpToTheLimitThenRefuses() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(3, 1000, 100);

        assertThat(limiter.tryAcquire("k", 0)).isTrue();
        assertThat(limiter.tryAcquire("k", 100)).isTrue();
        assertThat(limiter.tryAcquire("k", 200)).isTrue();
        assertThat(limiter.tryAcquire("k", 300)).isFalse();
    }

    @Test
    void countResetsWhenTheWindowRollsOver() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, 1000, 100);

        assertThat(limiter.tryAcquire("k", 0)).isTrue();
        assertThat(limiter.tryAcquire("k", 500)).isTrue();
        assertThat(limiter.tryAcquire("k", 900)).isFalse();

        // A full window later the counter starts fresh.
        assertThat(limiter.tryAcquire("k", 1000)).isTrue();
        assertThat(limiter.tryAcquire("k", 1200)).isTrue();
        assertThat(limiter.tryAcquire("k", 1400)).isFalse();
    }

    @Test
    void keysAreCountedIndependently() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, 1000, 100);

        assertThat(limiter.tryAcquire("a", 0)).isTrue();
        assertThat(limiter.tryAcquire("b", 0)).isTrue();
        assertThat(limiter.tryAcquire("a", 10)).isFalse();
        assertThat(limiter.tryAcquire("b", 10)).isFalse();
    }
}
