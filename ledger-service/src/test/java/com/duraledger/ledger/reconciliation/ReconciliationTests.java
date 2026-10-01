package com.duraledger.ledger.reconciliation;

import com.duraledger.ledger.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Corrupts the ledger on purpose and asserts the job notices. Own Spring context (and so its own
 * Postgres), because it writes corrupt data other test classes must never see.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "duraledger.reconciliation.initial-delay=1h") // tests trigger runs themselves
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReconciliationTests {

    @Value("${local.server.port}") int port;
    @Autowired ReconciliationJob job;
    @Autowired MeterRegistry meters;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired JsonMapper json;

    RestClient http;
    UUID alice;
    UUID bob;

    @BeforeEach
    void realTraffic() {
        http = RestClient.builder().defaultHeader("X-Api-Key", "local-dev-key").baseUrl("http://localhost:" + port).build();
        alice = account();
        bob = account();
        post("/deposits", Map.of("accountId", alice, "amountMinor", 10_000, "currency", "USD"));
        post("/transfers", Map.of("fromAccountId", alice, "toAccountId", bob, "amountMinor", 2_500, "currency", "USD"));
    }

    @Test
    void a_ledger_built_through_the_api_reconciles() {
        var report = job.run();

        assertThat(report.healthy()).isTrue();
        assertThat(report.entriesChecked()).isGreaterThanOrEqualTo(4);
        assertThat(violations("currency_zero_sum")).isZero();
        assertThat(violations("materialized_balance")).isZero();
    }

    @Test
    void a_drifted_stored_balance_is_reported_with_both_values() {
        // e.g. a hand-written "fix", or a future code path that forgets LedgerPoster
        jdbc.sql("UPDATE accounts SET balance_minor = balance_minor + 1 WHERE id = ?").param(bob).update();
        try {
            var report = job.run();

            assertThat(report.healthy()).isFalse();
            assertThat(report.balanceMismatches()).singleElement().satisfies(m -> {
                assertThat(m.accountId()).isEqualTo(bob);
                assertThat(m.materializedMinor()).isEqualTo(2_501);
                assertThat(m.ledgerMinor()).isEqualTo(2_500);
            });
            assertThat(violations("materialized_balance")).isEqualTo(1);
        } finally {
            jdbc.sql("UPDATE accounts SET balance_minor = balance_minor - 1 WHERE id = ?").param(bob).update();
        }
    }

    /**
     * The triggers make a one-sided entry impossible through normal SQL, so this simulates the case they
     * can't cover: something writing with triggers disabled. `session_replication_role = replica` is what
     * logical replication and some restore tools use, and it silently skips ordinary triggers.
     */
    @Test
    void money_created_behind_the_triggers_back_is_caught_by_the_whole_ledger_check() {
        var corruptTx = UUID.randomUUID();
        withTriggersDisabled("""
                INSERT INTO transactions (id, type, status) VALUES ('%1$s', 'DEPOSIT', 'COMPLETED');
                INSERT INTO ledger_entries (transaction_id, account_id, currency, amount_minor, direction)
                VALUES ('%1$s', '%2$s', 'USD', 999, 'CREDIT');
                UPDATE accounts SET balance_minor = balance_minor + 999 WHERE id = '%2$s';
                """.formatted(corruptTx, alice));
        try {
            var report = job.run();

            assertThat(report.currencyImbalances()).singleElement().satisfies(i -> {
                assertThat(i.currency()).isEqualTo("USD");
                assertThat(i.netMinor()).isEqualTo(999);   // exactly the money that appeared from nowhere
            });
            assertThat(report.balanceMismatches()).isEmpty(); // the stored balance was kept consistent: only check 1 sees it
            assertThat(violations("currency_zero_sum")).isEqualTo(1);
        } finally {
            withTriggersDisabled("""
                    DELETE FROM ledger_entries WHERE transaction_id = '%1$s';
                    DELETE FROM transactions WHERE id = '%1$s';
                    UPDATE accounts SET balance_minor = balance_minor - 999 WHERE id = '%2$s';
                    """.formatted(corruptTx, alice));
        }
    }

    @Test
    void the_actuator_endpoint_runs_a_reconciliation_on_demand() {
        var body = json.readTree(http.post().uri("/actuator/reconciliation").retrieve().body(String.class));

        assertThat(body.get("currencyImbalances")).isEmpty();
        assertThat(body.get("balanceMismatches")).isEmpty();
        assertThat(body.get("entriesChecked").asLong()).isGreaterThanOrEqualTo(4);
        assertThat(job.latest()).isNotNull();
    }

    // --- helpers -------------------------------------------------------------

    private double violations(String check) {
        return meters.get("duraledger.reconciliation.violations").tag("check", check).gauge().value();
    }

    private void withTriggersDisabled(String statements) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            for (var statement : statements.split(";")) {
                if (!statement.isBlank()) {
                    jdbc.sql(statement).update();
                }
            }
        });
    }

    private UUID account() {
        var body = http.post().uri("/accounts").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("userId", UUID.randomUUID(), "currency", "USD")).retrieve().body(String.class);
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    private void post(String path, Map<String, Object> body) {
        http.post().uri(path).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
    }
}
