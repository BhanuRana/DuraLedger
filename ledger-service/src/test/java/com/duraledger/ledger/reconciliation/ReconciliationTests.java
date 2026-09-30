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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Own Spring context (and so its own Postgres), because these tests will write corrupt data that
 * other test classes must never see.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "duraledger.reconciliation.initial-delay=1h") // tests trigger runs themselves
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReconciliationTests {

    @Value("${local.server.port}") int port;
    @Autowired ReconciliationJob job;
    @Autowired MeterRegistry meters;
    @Autowired JsonMapper json;

    RestClient http;
    UUID alice;
    UUID bob;

    @BeforeEach
    void realTraffic() {
        http = RestClient.builder().baseUrl("http://localhost:" + port).build();
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
