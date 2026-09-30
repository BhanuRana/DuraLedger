package com.duraledger.ledger.reconciliation;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * {@code GET /actuator/reconciliation}: the latest report; {@code POST}: run one now.
 * Deliberately not a HealthIndicator: a ledger inconsistency needs a human, and failing the
 * readiness probe would pull every replica out of the load balancer and stop all payments.
 */
@Component
@Endpoint(id = "reconciliation")
class ReconciliationEndpoint {

    private final ReconciliationJob job;

    ReconciliationEndpoint(ReconciliationJob job) {
        this.job = job;
    }

    @ReadOperation
    ReconciliationReport latest() {
        return job.latest();
    }

    @WriteOperation
    ReconciliationReport runNow() {
        return job.run();
    }
}
