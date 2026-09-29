package com.duraledger.ledger.ledger;

import java.util.UUID;

/**
 * Optimistic-concurrency guard for the debited account: its balance update only applies if the
 * account's version is still the one read before the funds check. {@link LedgerPoster} applies it
 * inside its id-ordered balance updates as ONE statement, so it can't break lock ordering.
 */
public record VersionGuard(UUID accountId, long expectedVersion) {}
