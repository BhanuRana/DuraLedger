package com.duraledger.ledger.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Set;

/**
 * @param keys accepted X-Api-Key values; several at once allows rotation without downtime.
 *             Empty means every business request is refused (fail closed, never open).
 */
@ConfigurationProperties("duraledger.api")
public record ApiProperties(
        @DefaultValue Set<String> keys) {}
