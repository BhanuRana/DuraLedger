package com.duraledger.notification.web;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-caller fixed-window counter (N requests per wall-clock minute), in memory.
 * Good enough to stop a leaked key from hammering the free-tier database: with Cloud Run capped at
 * 2 instances the real ceiling is at most 2N a minute. A cluster-wide limit would need shared state
 * (a token bucket in Redis or Postgres).
 */
class FixedWindowRateLimiter {

    private final int limitPerMinute;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    FixedWindowRateLimiter(int limitPerMinute, Clock clock) {
        this.limitPerMinute = limitPerMinute;
        this.clock = clock;
    }

    /** @return 0 if allowed, otherwise seconds until the current window ends (for Retry-After) */
    long tryAcquire(String caller) {
        if (limitPerMinute <= 0) {
            return 0;
        }
        long nowMillis = clock.millis();
        long minute = nowMillis / 60_000;
        var window = windows.compute(caller, (k, w) -> w == null || w.minute != minute ? new Window(minute) : w);
        if (window.count.incrementAndGet() <= limitPerMinute) {
            return 0;
        }
        return Math.max(1, ((minute + 1) * 60_000 - nowMillis + 999) / 1000);
    }

    private static final class Window {
        final long minute;
        final AtomicInteger count = new AtomicInteger();

        Window(long minute) {
            this.minute = minute;
        }
    }
}
