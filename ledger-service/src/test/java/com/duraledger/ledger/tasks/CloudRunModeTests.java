package com.duraledger.ledger.tasks;

import com.duraledger.ledger.TestcontainersConfiguration;
import com.duraledger.ledger.outbox.OutboxProperties;
import com.google.cloud.spring.pubsub.PubSubAdmin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The Cloud Run configuration: no in-process timers at all. The scheduler-called sweep must publish
 * what's pending, and task endpoints must refuse callers without a valid invoker token.
 */
@Import({TestcontainersConfiguration.class, CloudRunModeTests.StubInvokers.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "duraledger.tasks.trigger=external",
        "duraledger.tasks.audience=https://ledger.example",
        "duraledger.tasks.allowed-invokers=scheduler@example.iam.gserviceaccount.com"})
class CloudRunModeTests {

    @TestConfiguration
    static class StubInvokers {
        /** Real OIDC needs Google-signed tokens; its rejection paths are unit-tested separately. */
        @Bean
        @Primary
        InvokerVerifier stubInvokerVerifier() {
            return header -> "Bearer good-token".equals(header);
        }
    }

    @Value("${local.server.port}") int port;
    @Autowired ApplicationContext context;
    @Autowired PubSubAdmin admin;
    @Autowired OutboxProperties properties;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(s -> true, (req, res) -> { }).build();
        await().until(() -> admin.getTopic(properties.topic()) != null);
    }

    @Test
    void no_in_process_scheduler_exists() {
        assertThat(context.getBeanNamesForType(InternalTaskScheduler.class)).isEmpty();
    }

    @Test
    void the_sweep_publishes_pending_rows() {
        // With no timer in this context, only the sweep can publish this row.
        long eventId = jdbc.sql("""
                INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload, created_at)
                VALUES ('transaction', ?, 'DepositCompleted', '{"missed": true}'::jsonb, ?) RETURNING id""")
                .params(UUID.randomUUID(), OffsetDateTime.now()).query(Long.class).single();

        var response = http.post().uri("/internal/tasks/outbox-sweep").header("Authorization", "Bearer good-token")
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(response.getBody()).get("published").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT published_at IS NOT NULL FROM outbox WHERE id = ?").param(eventId)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void task_endpoints_refuse_callers_without_a_valid_token() {
        for (var path : new String[] {"/internal/tasks/outbox-sweep", "/internal/tasks/reconcile"}) {
            assertThat(status(path, null)).as(path + " without token").isEqualTo(403);
            assertThat(status(path, "Bearer forged")).as(path + " with bad token").isEqualTo(403);
        }
        assertThat(status("/internal/tasks/reconcile", "Bearer good-token")).isEqualTo(200);
    }

    // --- helpers -------------------------------------------------------------

    private int status(String path, String auth) {
        var request = http.post().uri(path);
        if (auth != null) {
            request = request.header("Authorization", auth);
        }
        return request.retrieve().toBodilessEntity().getStatusCode().value();
    }
}
