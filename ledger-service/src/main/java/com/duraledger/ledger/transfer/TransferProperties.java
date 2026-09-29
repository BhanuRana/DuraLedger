package com.duraledger.ledger.transfer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param locking               concurrency strategy for debits
 * @param maxOptimisticAttempts attempts per request before giving up with a 409 (OPTIMISTIC only)
 */
@ConfigurationProperties("duraledger.transfer")
public record TransferProperties(
        @DefaultValue("PESSIMISTIC") LockingMode locking,
        @DefaultValue("16") int maxOptimisticAttempts) {}
