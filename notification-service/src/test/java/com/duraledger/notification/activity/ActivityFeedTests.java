package com.duraledger.notification.activity;

import com.duraledger.notification.TestcontainersConfiguration;
import com.google.cloud.spring.pubsub.PubSubAdmin;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Publishes events shaped exactly like ledger-service's outbox rows (payload = the API response,
 * attributes = eventId/eventType/...) and checks the read model, over the real Pub/Sub emulator.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActivityFeedTests {

    static final AtomicLong EVENT_IDS = new AtomicLong(1_000);

    @Value("${local.server.port}") int port;
    @Autowired PubSubTemplate pubsub;
    @Autowired EventsProperties properties;
    @Autowired JsonMapper json;
    @Autowired PubSubAdmin admin;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder().defaultHeader("X-Api-Key", "local-dev-key").baseUrl("http://localhost:" + port).build();
    }


    /** Both services and several pods start at once, each making sure the topic and subscription exist. */
    @Test
    void concurrent_topology_creation_on_startup_does_not_crash_any_pod() throws Exception {
        var topic = "race-" + UUID.randomUUID();   // a fresh pair, so the live subscription stays untouched
        var probe = new EventsProperties(topic, topic + ".sub", true);
        var failures = new ConcurrentLinkedQueue<Throwable>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            for (int i = 0; i < 8; i++) {
                pool.submit(() -> {
                    gate.await();
                    try {
                        LedgerEventSubscriber.ensureTopology(admin, probe);
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                    return null;
                });
            }
            gate.countDown();
        }

        assertThat(failures).as("pods that crashed on startup").isEmpty();
        assertThat(admin.getSubscription(topic + ".sub")).isNotNull();
    }

    @Test
    void a_transfer_shows_up_in_both_accounts_feeds_with_opposite_directions() {
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();

        publishTransfer(EVENT_IDS.incrementAndGet(), alice, bob, 2_500);

        await().until(() -> feed(alice).size() == 1 && feed(bob).size() == 1);
        var aliceItem = feed(alice).get(0);
        var bobItem = feed(bob).get(0);
        assertThat(aliceItem.get("direction").asString()).isEqualTo("DEBIT");
        assertThat(aliceItem.get("counterpartyAccountId").asString()).isEqualTo(bob.toString());
        assertThat(bobItem.get("direction").asString()).isEqualTo("CREDIT");
        assertThat(bobItem.get("amountMinor").asLong()).isEqualTo(2_500);
    }

    @Test
    void a_redelivered_event_is_applied_once() {
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();
        long eventId = EVENT_IDS.incrementAndGet();

        publishTransfer(eventId, alice, bob, 100);
        publishTransfer(eventId, alice, bob, 100); // what at-least-once delivery looks like
        publishDeposit(EVENT_IDS.incrementAndGet(), alice, 7);   // a later, distinct event as a marker

        await().until(() -> feed(alice).size() == 2); // marker arrived -> the duplicate has been processed too
        assertThat(feed(alice)).extracting(n -> n.get("eventId").asLong()).containsOnlyOnce(eventId);
    }

    @Test
    void unknown_event_types_are_skipped_without_blocking_later_events() {
        var alice = UUID.randomUUID();

        publish(EVENT_IDS.incrementAndGet(), "AccountFrozen", Map.of("accountId", alice.toString()));
        publishDeposit(EVENT_IDS.incrementAndGet(), alice, 500);

        await().until(() -> feed(alice).size() == 1);
        assertThat(feed(alice).get(0).get("type").asString()).isEqualTo("DEPOSIT");
    }


    @Test
    void withdrawals_and_fx_conversions_project_onto_the_right_wallets() {
        var usd = UUID.randomUUID();
        var hkd = UUID.randomUUID();

        publish(EVENT_IDS.incrementAndGet(), "WithdrawalCompleted", Map.of(
                "transactionId", UUID.randomUUID().toString(), "type", "WITHDRAWAL", "status", "COMPLETED",
                "accountId", usd.toString(), "amountMinor", 300, "currency", "USD", "createdAt", OffsetDateTime.now().toString()));
        publish(EVENT_IDS.incrementAndGet(), "FxConverted", Map.ofEntries(
                Map.entry("transactionId", UUID.randomUUID().toString()), Map.entry("type", "FX_CONVERT"),
                Map.entry("status", "COMPLETED"), Map.entry("fromAccountId", usd.toString()),
                Map.entry("fromAmountMinor", 10_000), Map.entry("fromCurrency", "USD"),
                Map.entry("toAccountId", hkd.toString()), Map.entry("toAmountMinor", 78_000),
                Map.entry("toCurrency", "HKD"), Map.entry("rate", "7.800000"),
                Map.entry("createdAt", OffsetDateTime.now().plusSeconds(1).toString())));

        await().until(() -> feed(usd).size() == 2 && feed(hkd).size() == 1);
        assertThat(feed(usd)).extracting(n -> n.get("direction").asString() + " " + n.get("amountMinor").asLong() + " " + n.get("currency").asString())
                .containsExactly("DEBIT 10000 USD", "DEBIT 300 USD");
        var credit = feed(hkd).get(0);
        assertThat(credit.get("amountMinor").asLong()).isEqualTo(78_000);
        assertThat(credit.get("currency").asString()).isEqualTo("HKD");
        assertThat(credit.get("counterpartyAccountId").asString()).isEqualTo(usd.toString());
    }

    @Test
    void feed_is_newest_first_and_respects_limit() {
        var alice = UUID.randomUUID();
        for (int i = 1; i <= 5; i++) {
            publishDeposit(EVENT_IDS.incrementAndGet(), alice, i, OffsetDateTime.now().plusSeconds(i));
        }

        await().until(() -> feed(alice).size() == 5);
        var latestTwo = http.get().uri("/accounts/{id}/activity?limit=2", alice).retrieve().body(JsonNode.class);
        assertThat(latestTwo).extracting(n -> n.get("amountMinor").asLong()).containsExactly(5L, 4L);
    }

    // --- helpers -------------------------------------------------------------

    private void publishTransfer(long eventId, UUID from, UUID to, long amount) {
        publish(eventId, "TransferCompleted", Map.of(
                "transactionId", UUID.randomUUID().toString(), "type", "TRANSFER", "status", "COMPLETED",
                "fromAccountId", from.toString(), "toAccountId", to.toString(),
                "amountMinor", amount, "currency", "USD", "createdAt", OffsetDateTime.now().toString()));
    }

    private void publishDeposit(long eventId, UUID account, long amount) {
        publishDeposit(eventId, account, amount, OffsetDateTime.now());
    }

    private void publishDeposit(long eventId, UUID account, long amount, OffsetDateTime at) {
        publish(eventId, "DepositCompleted", Map.of(
                "transactionId", UUID.randomUUID().toString(), "type", "DEPOSIT", "status", "COMPLETED",
                "accountId", account.toString(), "amountMinor", amount, "currency", "USD", "createdAt", at.toString()));
    }

    private void publish(long eventId, String eventType, Map<String, Object> payload) {
        pubsub.publish(properties.topic(), json.writeValueAsString(payload), Map.of(
                "eventId", String.valueOf(eventId), "eventType", eventType, "aggregateType", "transaction")).join();
    }

    private java.util.List<JsonNode> feed(UUID account) {
        var body = http.get().uri("/accounts/{id}/activity", account).retrieve().body(JsonNode.class);
        var items = new java.util.ArrayList<JsonNode>();
        body.forEach(items::add);
        return items;
    }
}
