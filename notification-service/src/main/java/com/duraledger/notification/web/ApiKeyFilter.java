package com.duraledger.notification.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

/**
 * Every business endpoint needs {@code X-Api-Key}, then a per-key rate limit applies. Runs before
 * anything touches the database, so a stranger with the URL costs one cheap 401 and no database work.
 * Not covered here: /actuator/** (closed down to health/info in gcp) and /pubsub/push (Google OIDC).
 * The same filter as ledger-service, duplicated rather than shared until a third service needs it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);
    static final String HEADER = "X-Api-Key";

    private final byte[][] keys;
    private final FixedWindowRateLimiter limiter;
    private final Counter unauthorized;
    private final Counter rateLimited;

    ApiKeyFilter(ApiProperties properties, MeterRegistry meters) {
        this.keys = properties.keys().stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
        this.limiter = new FixedWindowRateLimiter(properties.rateLimitPerMinute(), Clock.systemUTC());
        // Own counter: this filter runs ahead of Spring's HTTP observation filter, so these rejections
        // never show up in http.server.requests. A 401 spike means someone is probing; 429s, a client to talk to.
        this.unauthorized = meters.counter("duraledger.api.rejected", "reason", "unauthorized");
        this.rateLimited = meters.counter("duraledger.api.rejected", "reason", "rate_limited");
        if (keys.length == 0) {
            log.error("No duraledger.api.keys configured: ALL business requests will be refused (fail closed)");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI();
        return path.startsWith("/actuator/") || path.equals("/pubsub/push") || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var presented = request.getHeader(HEADER);
        if (presented == null || !matchesAnyKey(presented.getBytes(StandardCharsets.UTF_8))) {
            unauthorized.increment();
            problem(response, 401, "unauthorized", "Missing or invalid " + HEADER);
            return;
        }
        long retryAfter = limiter.tryAcquire(fingerprint(presented));
        if (retryAfter > 0) {
            rateLimited.increment();
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            problem(response, 429, "rate-limited", "Too many requests for this API key; retry in " + retryAfter + " s");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Constant-time compare against every key, so response timing doesn't leak how much matched. */
    private boolean matchesAnyKey(byte[] presented) {
        boolean match = false;
        for (byte[] key : keys) {
            match |= MessageDigest.isEqual(key, presented);
        }
        return match;
    }

    /** The limiter's map is keyed by a hash, so raw keys don't sit in yet another structure. */
    private static String fingerprint(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void problem(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("""
                {"type":"urn:duraledger:problem:%s","title":"%s","status":%d,"detail":"%s"}"""
                .formatted(code, status == 401 ? "Unauthorized" : "Too Many Requests", status, detail));
    }
}
