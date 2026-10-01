package com.duraledger.ledger.tasks;

import com.duraledger.ledger.outbox.OutboxRelay;
import com.duraledger.ledger.reconciliation.ReconciliationJob;
import com.duraledger.ledger.reconciliation.ReconciliationReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * duraledger.tasks.trigger=external (Cloud Run): Cloud Scheduler POSTs here with an OIDC token.
 * Both tasks are idempotent, so a retried or duplicated scheduler call is harmless.
 */
@RestController
@RequestMapping("/internal/tasks")
@ConditionalOnProperty(name = "duraledger.tasks.trigger", havingValue = "external")
class TaskController {

    private final InvokerVerifier invokers;
    private final OutboxRelay relay;
    private final ReconciliationJob reconciliation;

    TaskController(InvokerVerifier invokers, OutboxRelay relay, ReconciliationJob reconciliation) {
        this.invokers = invokers;
        this.relay = relay;
        this.reconciliation = reconciliation;
    }

    /** Safety net: publishes anything publish-after-commit missed (e.g. an instance died mid-publish). */
    @PostMapping("/outbox-sweep")
    ResponseEntity<Map<String, Integer>> sweepOutbox(@RequestHeader(name = "Authorization", required = false) String auth) {
        if (!invokers.isAllowed(auth)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(Map.of("published", relay.drain(50)));
    }

    /** 200 either way: the report says whether it's healthy, and violations are logged + metered. */
    @PostMapping("/reconcile")
    ResponseEntity<ReconciliationReport> reconcile(@RequestHeader(name = "Authorization", required = false) String auth) {
        if (!invokers.isAllowed(auth)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(reconciliation.run());
    }

    @Configuration
    @ConditionalOnProperty(name = "duraledger.tasks.trigger", havingValue = "external")
    static class VerifierConfig {

        @Bean
        InvokerVerifier invokerVerifier(@Value("${duraledger.tasks.audience}") String audience,
                                        @Value("${duraledger.tasks.allowed-invokers}") Set<String> allowedInvokers) {
            return new GoogleOidcInvokerVerifier(audience, allowedInvokers);
        }
    }
}
