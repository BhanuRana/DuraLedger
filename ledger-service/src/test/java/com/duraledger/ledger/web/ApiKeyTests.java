package com.duraledger.ledger.web;

import com.duraledger.ledger.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The public API refuses strangers before any database work. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "duraledger.api.keys=key-a,key-b")
class ApiKeyTests {

    @Value("${local.server.port}") int port;
    @Autowired MeterRegistry meters;
    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port).defaultStatusHandler(s -> true, (q, r) -> { }).build();
    }

    @Test
    void business_endpoints_refuse_missing_and_wrong_keys_with_a_problem_body() {
        double before = rejected("unauthorized");
        var missing = get("/accounts/" + UUID.randomUUID(), null);
        var wrong = get("/accounts/" + UUID.randomUUID(), "not-a-key");

        assertThat(missing.getStatusCode().value()).isEqualTo(401);
        assertThat(missing.getBody()).contains("urn:duraledger:problem:unauthorized");
        assertThat(wrong.getStatusCode().value()).isEqualTo(401);
        assertThat(rejected("unauthorized") - before).isEqualTo(2); // invisible to http.server.requests otherwise
    }

    @Test
    void health_stays_public_for_probes() {
        assertThat(get("/actuator/health", null).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void either_configured_key_works_so_keys_can_be_rotated_without_downtime() {
        assertThat(get("/accounts/" + UUID.randomUUID(), "key-b").getStatusCode().value()).isEqualTo(404); // authed, just unknown
    }

    private double rejected(String reason) {
        return meters.get("duraledger.api.rejected").tag("reason", reason).counter().count();
    }

    private ResponseEntity<String> get(String path, String key) {
        var request = http.get().uri(path);
        if (key != null) {
            request = request.header("X-Api-Key", key);
        }
        return request.retrieve().toEntity(String.class);
    }
}
