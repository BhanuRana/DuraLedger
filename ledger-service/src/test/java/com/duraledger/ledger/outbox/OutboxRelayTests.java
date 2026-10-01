package com.duraledger.ledger.outbox;

import com.duraledger.ledger.TestcontainersConfiguration;
import com.google.cloud.spring.pubsub.PubSubAdmin;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import com.google.pubsub.v1.PubsubMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Ledger write -> outbox row -> relay -> Pub/Sub topic, against real Postgres and the Pub/Sub emulator. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxRelayTests {

    @Value("${local.server.port}") int port;
    @Autowired PubSubAdmin admin;
    @Autowired PubSubTemplate pubsub;
    @Autowired OutboxProperties properties;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired OutboxRelay relay;

    RestClient http;
    String subscription;

    @BeforeEach
    void setUp() {
        http = RestClient.builder().defaultHeader("X-Api-Key", "local-dev-key").baseUrl("http://localhost:" + port).build();
        await().until(() -> admin.getTopic(properties.topic()) != null); // created on ApplicationReady
        subscription = "relay-test-" + UUID.randomUUID();
        admin.createSubscription(subscription, properties.topic()); // sees only messages published from now on
    }

    /** Several pods start at once: all try to create the missing topic, and none may crash. */
    @Test
    void concurrent_topic_creation_on_startup_does_not_crash_any_pod() throws Exception {
        admin.deleteTopic(properties.topic());   // back to a fresh cluster: nobody has created it yet

        var failures = new ConcurrentLinkedQueue<Throwable>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            for (int i = 0; i < 8; i++) {
                pool.submit(() -> {
                    gate.await();
                    try {
                        relay.createTopologyIfConfigured();
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                    return null;
                });
            }
            gate.countDown();
        }

        assertThat(failures).as("pods that crashed on startup").isEmpty();
        assertThat(admin.getTopic(properties.topic())).isNotNull();
    }

    @Test
    void a_committed_deposit_is_published_with_its_outbox_id_and_marked_published() {
        var account = createAccount();
        var transactionId = deposit(account, 5_000);

        var seen = new ArrayList<PubsubMessage>();
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            pubsub.pullAndAck(subscription, 10, true).forEach(seen::add);
            return seen.stream().anyMatch(m -> transactionId.equals(m.getAttributesMap().get("aggregateId")));
        });
        var message = seen.stream()
                .filter(m -> transactionId.equals(m.getAttributesMap().get("aggregateId"))).findFirst().orElseThrow();

        assertThat(message.getAttributesMap())
                .containsEntry("eventType", "DepositCompleted")
                .containsEntry("aggregateType", "transaction")
                .containsKey("eventId");
        var payload = json.readTree(message.getData().toStringUtf8());
        assertThat(payload.get("accountId").asString()).isEqualTo(account.toString());
        assertThat(payload.get("amountMinor").asLong()).isEqualTo(5_000);

        long eventId = Long.parseLong(message.getAttributesMap().get("eventId"));
        await().untilAsserted(() -> assertThat(jdbc.sql("SELECT published_at FROM outbox WHERE id = ?")
                .param(eventId).query(OffsetDateTime.class).single()).isNotNull());
    }

    // --- helpers -------------------------------------------------------------

    private UUID createAccount() {
        var body = http.post().uri("/accounts").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("userId", UUID.randomUUID(), "currency", "USD")).retrieve().body(String.class);
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    private String deposit(UUID account, long amount) {
        var body = http.post().uri("/deposits").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("accountId", account, "amountMinor", amount, "currency", "USD"))
                .retrieve().body(String.class);
        return json.readTree(body).get("transactionId").asString();
    }
}
