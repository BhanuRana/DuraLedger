package com.duraledger.ledger.reconciliation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs reconciliation on a timer. A separate bean on purpose: calling {@code job.run()} goes through
 * Spring's proxy, so its read-only REPEATABLE READ transaction applies. A @Scheduled method inside
 * ReconciliationJob calling run() on itself would bypass the proxy and silently lose the snapshot.
 */
@Component
class ReconciliationScheduler {

    private final ReconciliationJob job;

    ReconciliationScheduler(ReconciliationJob job) {
        this.job = job;
    }

    @Scheduled(fixedDelayString = "${duraledger.reconciliation.interval:60s}",
            initialDelayString = "${duraledger.reconciliation.initial-delay:30s}")
    void reconcile() {
        job.run();
    }
}
