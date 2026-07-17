package com.modocument.infrastructure.extraction.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PerHostRateLimiterTest {

    @Test
    void enforcesMinimumIntervalPerHost() {
        PerHostRateLimiter limiter = new PerHostRateLimiter();
        long start = System.currentTimeMillis();
        limiter.acquire("example.com", 80);
        limiter.acquire("example.com", 80);

        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(80);
    }

    @Test
    void differentHostsAreIndependent() {
        PerHostRateLimiter limiter = new PerHostRateLimiter();
        long start = System.currentTimeMillis();
        limiter.acquire("a.example.com", 500);
        limiter.acquire("b.example.com", 500);

        // The second host is not delayed by the first host's interval.
        assertThat(System.currentTimeMillis() - start).isLessThan(400);
    }

    @Test
    void zeroIntervalDoesNotBlock() {
        PerHostRateLimiter limiter = new PerHostRateLimiter();
        long start = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            limiter.acquire("example.com", 0);
        }
        assertThat(System.currentTimeMillis() - start).isLessThan(200);
    }
}
