package com.duraledger.ledger.ledger;

import java.util.UUID;

/**
 * The source account was debited by someone else between our read and our write. Not a business
 * rejection: it rolls the whole transaction back (idempotency claim included) so it can be retried.
 */
public class OptimisticConflictException extends RuntimeException {

    public OptimisticConflictException(UUID accountId) {
        super("Account " + accountId + " was modified concurrently");
    }
}
