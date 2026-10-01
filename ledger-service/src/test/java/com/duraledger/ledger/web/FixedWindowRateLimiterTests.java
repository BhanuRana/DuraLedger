package com.duraledger.ledger.web;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTests {

    private final AtomicLong now = new AtomicLong(Instant.parse("2026-09-24T10:00:10Z").toEpochMilli());
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };

    @Test
    void allows_n_per_minute_per_caller_then_reports_seconds_until_the_window_resets() {
        var limiter = new FixedWindowRateLimiter(2, clock);

        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isEqualTo(50);   // at :10, window ends at :00 of next minute
        assertThat(limiter.tryAcquire("b")).isZero();        // callers don't share a budget

        now.addAndGet(50_000);                               // next minute
        assertThat(limiter.tryAcquire("a")).isZero();
    }

    @Test
    void zero_means_unlimited() {
        var limiter = new FixedWindowRateLimiter(0, clock);
        for (int i = 0; i < 1_000; i++) {
            assertThat(limiter.tryAcquire("a")).isZero();
        }
    }
}
