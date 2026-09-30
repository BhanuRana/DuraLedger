package com.duraledger.ledger.reconciliation;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** One reconciliation run: both checks, evaluated against the same database snapshot. */
public record ReconciliationReport(
        OffsetDateTime startedAt,
        Duration duration,
        long entriesChecked,
        List<CurrencyImbalance> currencyImbalances,
        List<BalanceMismatch> balanceMismatches) {

    public boolean healthy() {
        return currencyImbalances.isEmpty() && balanceMismatches.isEmpty();
    }

    /** A currency whose entries, across ALL accounts (clearing + FX pools included), don't net to zero. */
    public record CurrencyImbalance(String currency, long netMinor) {}

    /** A user account whose materialized balance disagrees with the SUM of its ledger entries. */
    public record BalanceMismatch(UUID accountId, String currency, long materializedMinor, long ledgerMinor) {}
}
