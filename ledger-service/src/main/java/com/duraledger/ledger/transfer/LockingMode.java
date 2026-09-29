package com.duraledger.ledger.transfer;

/** How concurrent debits of the same account are serialized. */
public enum LockingMode {
    /** {@code SELECT ... FOR UPDATE} the source account; concurrent debits queue on the row lock. */
    PESSIMISTIC,
    /** Read without locking; a version compare-and-set at the end detects conflicts, and the whole transaction retries. */
    OPTIMISTIC
}
