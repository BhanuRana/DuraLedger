package com.duraledger.ledger.tasks;

import com.duraledger.ledger.TestcontainersConfiguration;
import com.duraledger.ledger.outbox.OutboxProperties;
import com.google.cloud.spring.pubsub.PubSubAdmin;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The Cloud Run configuration: no in-process timers at all. Events must still flow (publish-after-
 * commit), the scheduler-called sweep must catch anything missed, and task endpoints must refuse
 * callers without a valid invoker token.
 */
@Import({TestcontainersConfiguration.class, CloudRunModeTests.StubInvokers.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "duraledger.tasks.trigger=external",
        "duraledger.outbox.publish-after-commit=true",
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
    @Autowired PubSubTemplate pubsub;
    @Autowired OutboxProperties properties;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    RestClient http;
    String subscription;

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(s -> true, (req, res) -> { }).build();
        await().until(() -> admin.getTopic(properties.topic()) != null);
        subscription = "cloudrun-test-" + UUID.randomUUID();
        admin.createSubscription(subscription, properties.topic());
    }

    @Test
    void no_in_process_scheduler_exists() {
        assertThat(context.getBeanNamesForType(InternalTaskScheduler.class)).isEmpty();
    }

    @Test
    void events_are_published_right_after_commit_without_any_poller() {
        var account = createAccount();

        var body = post("/deposits", Map.of("accountId", account, "amountMinor", 700, "currency", "USD"));
        var transactionId = json.readTree(body).get("transactionId").asString();

        // Only publish-after-commit can have done this: there is no scheduler in this context.
        await().atMost(Duration.ofSeconds(10)).until(() -> pubsub.pullAndAck(subscription, 10, true).stream()
                .anyMatch(m -> transactionId.equals(m.getAttributesMap().get("aggregateId"))));
        // ...and the rows are marked, so the next sweep doesn't publish them a second time.
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = ?::uuid AND published_at IS NULL")
                .param(transactionId).query(Long.class).single()).isZero();
    }

    @Test
    void the_sweep_publishes_rows_that_publish_after_commit_missed() {
        // Simulates an instance that committed but died before its after-commit publish.
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

    private UUID createAccount() {
        var body = http.post().uri("/accounts").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("userId", UUID.randomUUID(), "currency", "USD")).retrieve().body(String.class);
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    private String post(String path, Map<String, Object> body) {
        return http.post().uri(path).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
    }

    private int status(String path, String auth) {
        var request = http.post().uri(path);
        if (auth != null) {
            request = request.header("Authorization", auth);
        }
        return request.retrieve().toBodilessEntity().getStatusCode().value();
    }
}
