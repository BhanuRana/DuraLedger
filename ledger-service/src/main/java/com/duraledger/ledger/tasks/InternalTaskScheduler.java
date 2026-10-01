package com.duraledger.ledger.tasks;

import com.duraledger.ledger.outbox.OutboxRelay;
import com.duraledger.ledger.reconciliation.ReconciliationJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * duraledger.tasks.trigger=internal (default; local dev, tests, any always-on host): in-process timers.
 * On Cloud Run the CPU is throttled between requests and instances scale to zero, so background
 * timers can't be relied on; there, {@link TaskController} is used instead (docs/decisions/0006).
 *
 * <p>Calling the relay and the job through their beans also keeps the job's read-only REPEATABLE READ
 * transaction: the call goes through Spring's proxy.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "duraledger.tasks.trigger", havingValue = "internal", matchIfMissing = true)
class InternalTaskScheduler {

    private final OutboxRelay relay;
    private final ReconciliationJob reconciliation;

    InternalTaskScheduler(OutboxRelay relay, ReconciliationJob reconciliation) {
        this.relay = relay;
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${duraledger.outbox.poll-interval:200ms}")
    void relayOutbox() {
        relay.publishPending();
    }

    @Scheduled(fixedDelayString = "${duraledger.reconciliation.interval:60s}",
            initialDelayString = "${duraledger.reconciliation.initial-delay:30s}")
    void reconcile() {
        reconciliation.run();
    }
}
