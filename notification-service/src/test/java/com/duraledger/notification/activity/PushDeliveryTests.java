package com.duraledger.notification.activity;

import com.duraledger.notification.TestcontainersConfiguration;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Cloud Run mode: events arrive as Pub/Sub push HTTP requests; the response status is the ack. */
@Import({TestcontainersConfiguration.class, PushDeliveryTests.StubInvokers.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "duraledger.events.delivery=push",
        "duraledger.events.push-audience=https://notify.example",
        "duraledger.events.push-invokers=pubsub-push@example.iam.gserviceaccount.com"})
class PushDeliveryTests {

    @TestConfiguration
    static class StubInvokers {
        @Bean
        @Primary
        InvokerVerifier stubInvokerVerifier() {
            return header -> "Bearer good-token".equals(header);
        }
    }

    static long nextEventId = 50_000;

    @Value("${local.server.port}") int port;
    @Autowired ApplicationContext context;
    @Autowired JsonMapper json;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(s -> true, (req, res) -> { }).build();
    }

    @Test
    void no_streaming_pull_subscriber_exists() {
        assertThat(context.getBeanNamesForType(LedgerEventSubscriber.class)).isEmpty();
    }

    @Test
    void a_pushed_transfer_lands_in_both_feeds_and_is_acked_with_204() {
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();

        int status = push("Bearer good-token", nextEventId++, transfer(alice, bob, 1_500));

        assertThat(status).isEqualTo(204);
        assertThat(feed(alice).get(0).get("direction").asString()).isEqualTo("DEBIT");
        assertThat(feed(bob).get(0).get("direction").asString()).isEqualTo("CREDIT");
    }

    @Test
    void a_redelivered_push_is_acked_and_applied_once() {
        var alice = UUID.randomUUID();
        long eventId = nextEventId++;

        assertThat(push("Bearer good-token", eventId, transfer(alice, UUID.randomUUID(), 10))).isEqualTo(204);
        assertThat(push("Bearer good-token", eventId, transfer(alice, UUID.randomUUID(), 10))).isEqualTo(204);

        assertThat(feed(alice).size()).isEqualTo(1);
    }

    @Test
    void pushes_without_a_valid_token_are_refused_and_not_applied() {
        var alice = UUID.randomUUID();

        assertThat(push(null, nextEventId++, transfer(alice, UUID.randomUUID(), 10))).isEqualTo(403);
        assertThat(push("Bearer forged", nextEventId++, transfer(alice, UUID.randomUUID(), 10))).isEqualTo(403);

        assertThat(feed(alice).size()).isZero();
    }

    // --- helpers -------------------------------------------------------------

    private Map<String, Object> transfer(UUID from, UUID to, long amount) {
        return Map.of("transactionId", UUID.randomUUID().toString(), "type", "TRANSFER", "status", "COMPLETED",
                "fromAccountId", from.toString(), "toAccountId", to.toString(), "amountMinor", amount,
                "currency", "USD", "createdAt", OffsetDateTime.now().toString());
    }

    /** Builds the exact envelope Pub/Sub push sends. */
    private int push(String auth, long eventId, Map<String, Object> payload) {
        var data = Base64.getEncoder().encodeToString(json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
        var envelope = Map.of(
                "message", Map.of("attributes", Map.of("eventId", String.valueOf(eventId), "eventType", "TransferCompleted"),
                        "data", data, "messageId", UUID.randomUUID().toString()),
                "subscription", "projects/p/subscriptions/notification-service.ledger-events");
        var request = http.post().uri("/pubsub/push").contentType(MediaType.APPLICATION_JSON).body(envelope);
        if (auth != null) {
            request = request.header("Authorization", auth);
        }
        return request.retrieve().toBodilessEntity().getStatusCode().value();
    }

    private java.util.List<JsonNode> feed(UUID account) {
        var items = new java.util.ArrayList<JsonNode>();
        http.get().uri("/accounts/{id}/activity", account).retrieve().body(JsonNode.class).forEach(items::add);
        return items;
    }
}
