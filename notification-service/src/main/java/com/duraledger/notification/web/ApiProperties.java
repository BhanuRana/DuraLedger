package com.duraledger.notification.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Set;

/**
 * @param keys               accepted X-Api-Key values; several at once allows rotation without downtime.
 *                           Empty means every business request is refused (fail closed, never open).
 * @param rateLimitPerMinute requests per key per minute per instance; 0 means no limit (local, tests)
 */
@ConfigurationProperties("duraledger.api")
public record ApiProperties(
        @DefaultValue Set<String> keys,
        @DefaultValue("0") int rateLimitPerMinute) {}
