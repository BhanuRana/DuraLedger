package com.duraledger.ledger.transfer;

import com.duraledger.ledger.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end over real HTTP against real Postgres. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MoneyMovementApiTests {

    @Value("${local.server.port}") int port;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }) // assert on 4xx, don't throw
                .build();
    }

    @Test
    void deposit_then_transfer_moves_money_and_balances_are_derived_from_the_ledger() {
        var alice = account("HKD");
        var bob = account("HKD");
        deposit(alice, 10_000, "HKD");

        var response = transfer(key(), alice, bob, 2_500, "HKD");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(balance(alice)).isEqualTo(7_500);
        assertThat(balance(bob)).isEqualTo(2_500);

        var tx = get("/transactions/" + response.body().get("transactionId").asString());
        assertThat(tx.body().get("entries")).hasSize(2);
    }

    /** A client timed out and retried: same Idempotency-Key, same body. Money must move once. */
    @Test
    void retry_with_the_same_key_moves_money_once_and_replays_the_original_response() {
        var alice = account("USD");
        var bob = account("USD");
        deposit(alice, 1_000, "USD");
        var key = key();

        var first = transfer(key, alice, bob, 300, "USD");
        var retry = transfer(key, alice, bob, 300, "USD");

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(first.replayed()).isNull();
        assertThat(retry.replayed()).isEqualTo("true");
        assertThat(balance(alice)).isEqualTo(700);
        assertThat(balance(bob)).isEqualTo(300);
    }

    @Test
    void a_money_movement_without_an_idempotency_key_is_a_bad_request() {
        var response = call(http.post().uri("/transfers").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fromAccountId", UUID.randomUUID(), "toAccountId", UUID.randomUUID(),
                        "amountMinor", 100, "currency", "USD")));

        assertThat(response.status()).isEqualTo(400);
    }

    /** The double-submit race: N identical requests at the same instant must create exactly one transaction. */
    @Test
    void concurrent_duplicates_with_the_same_key_execute_exactly_once() throws Exception {
        var alice = account("GBP");
        var bob = account("GBP");
        deposit(alice, 10_000, "GBP");
        var key = key();

        var responses = runConcurrently(20, () -> transfer(key, alice, bob, 1_000, "GBP"));

        assertThat(responses).extracting(Response::status).containsOnly(201);
        assertThat(responses).extracting(Response::body).containsOnly(responses.getFirst().body());
        assertThat(jdbc.sql("SELECT count(*) FROM transactions WHERE idempotency_key = ?").param(key)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(balance(alice)).isEqualTo(9_000);
        assertMaterializedMatchesLedger(alice, bob);
    }

    @Test
    void insufficient_funds_is_rejected_and_nothing_moves() {
        var alice = account("EUR");
        var bob = account("EUR");
        deposit(alice, 100, "EUR");

        var rejected = transfer(key(), alice, bob, 101, "EUR");

        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.contentType()).isEqualTo("application/problem+json");
        assertThat(problemType(rejected)).isEqualTo("urn:duraledger:problem:insufficient-funds");
        assertThat(balance(alice)).isEqualTo(100);
        assertThat(balance(bob)).isZero();
    }

    /** A client bug reusing a key for a different transfer must not get the old transfer's response. */
    @Test
    void reusing_a_key_for_a_different_request_is_rejected() {
        var alice = account("USD");
        var bob = account("USD");
        deposit(alice, 1_000, "USD");
        var key = key();
        transfer(key, alice, bob, 300, "USD");

        var reused = transfer(key, alice, bob, 999, "USD");

        assertThat(reused.status()).isEqualTo(422);
        assertThat(problemType(reused)).isEqualTo("urn:duraledger:problem:idempotency-key-reused");
        assertThat(balance(alice)).isEqualTo(700);
    }

    /** A refusal is an outcome too: the same key must never flip from "declined" to "done". */
    @Test
    void a_rejection_is_replayed_even_after_funds_arrive() {
        var alice = account("EUR");
        var bob = account("EUR");
        deposit(alice, 100, "EUR");
        var key = key();

        var rejected = transfer(key, alice, bob, 101, "EUR");
        deposit(alice, 1_000, "EUR");
        var retry = transfer(key, alice, bob, 101, "EUR");

        assertThat(rejected.status()).isEqualTo(422);
        assertThat(retry.status()).isEqualTo(422);
        assertThat(retry.replayed()).isEqualTo("true");
        assertThat(problemType(retry)).isEqualTo("urn:duraledger:problem:insufficient-funds");
        assertThat(balance(bob)).isZero();
    }

    @Test
    void currency_mismatch_unknown_and_same_accounts_are_rejected() {
        var usd = account("USD");
        var hkd = account("HKD");
        deposit(usd, 1_000, "USD");

        assertThat(problemType(transfer(key(), usd, hkd, 100, "USD"))).isEqualTo("urn:duraledger:problem:currency-mismatch");
        assertThat(problemType(transfer(key(), usd, UUID.randomUUID(), 100, "USD"))).isEqualTo("urn:duraledger:problem:account-not-found");
        assertThat(problemType(transfer(key(), usd, usd, 100, "USD"))).isEqualTo("urn:duraledger:problem:same-account");
        assertThat(balance(usd)).isEqualTo(1_000);
    }

    @Test
    void money_cannot_be_moved_out_of_a_system_account() {
        var clearing = jdbc.sql("SELECT id FROM accounts WHERE kind = 'EXTERNAL_CLEARING' AND currency = 'USD'")
                .query(UUID.class).single();

        assertThat(transfer(key(), clearing, account("USD"), 100, "USD").status()).isEqualTo(404);
    }

    @Test
    void invalid_amounts_are_a_bad_request() {
        assertThat(transfer(key(), account("USD"), account("USD"), -5, "USD").status()).isEqualTo(400);
    }

    /** 20 transfers draining one account at the same instant: only the affordable 10 may succeed. */
    @Test
    void concurrent_transfers_never_overdraw_the_source_account() throws Exception {
        var alice = account("USD");
        var bob = account("USD");
        deposit(alice, 1_000, "USD");

        var responses = runConcurrently(20, () -> transfer(key(), alice, bob, 100, "USD"));

        var succeeded = responses.stream().filter(r -> r.status() == 201).count();
        // 409 = optimistic retries exhausted: a refusal, never an overdraft
        assertThat(responses).extracting(Response::status).allMatch(s -> s == 201 || s == 422 || s == 409);
        assertThat(balance(alice)).isEqualTo(1_000 - 100 * succeeded).isGreaterThanOrEqualTo(0);
        assertThat(balance(bob)).isEqualTo(100 * succeeded);
        assertMaterializedMatchesLedger(alice, bob);
        if (strictlySerialized()) {
            // pessimistic: every request eventually gets the lock, so exactly the affordable 10 succeed
            assertThat(succeeded).isEqualTo(10);
        }
    }

    /** Transfers in both directions between the same two accounts, all at once. */
    @Test
    void opposite_transfers_between_the_same_accounts_do_not_deadlock() throws Exception {
        var alice = account("HKD");
        var bob = account("HKD");
        deposit(alice, 100_000, "HKD");
        deposit(bob, 100_000, "HKD");

        var responses = runConcurrently(40, () -> ThreadLocalRandom.current().nextBoolean()
                ? transfer(key(), alice, bob, 10, "HKD")
                : transfer(key(), bob, alice, 10, "HKD"));

        assertThat(responses).extracting(Response::status).allMatch(s -> s == 201 || s == 409);
        assertThat(balance(alice) + balance(bob)).isEqualTo(200_000);
        assertMaterializedMatchesLedger(alice, bob);
    }

    @Test
    void the_whole_ledger_nets_to_zero_per_currency() {
        var alice = account("GBP");
        var bob = account("GBP");
        deposit(alice, 5_000, "GBP");
        transfer(key(), alice, bob, 1_234, "GBP");

        assertThat(jdbc.sql("""
                SELECT count(*) FROM (
                    SELECT currency FROM ledger_entries GROUP BY currency
                    HAVING SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor ELSE -amount_minor END) <> 0) unbalanced
                """).query(Long.class).single()).isZero();
    }

    // --- helpers -------------------------------------------------------------

    /** Pessimistic locking queues every request, so outcomes are exact; optimistic ones may give up (409). */
    boolean strictlySerialized() {
        return true;
    }

    record Response(int status, JsonNode body, String contentType, String replayed) {}

    private UUID account(String currency) {
        var response = call(http.post().uri("/accounts").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("userId", UUID.randomUUID(), "currency", currency)));
        assertThat(response.status()).isEqualTo(201);
        return UUID.fromString(response.body().get("id").asString());
    }

    private void deposit(UUID account, long amount, String currency) {
        var response = call(http.post().uri("/deposits").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("accountId", account, "amountMinor", amount, "currency", currency)));
        assertThat(response.status()).isEqualTo(201);
    }

    private Response transfer(String key, UUID from, UUID to, long amount, String currency) {
        return call(http.post().uri("/transfers").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fromAccountId", from, "toAccountId", to, "amountMinor", amount, "currency", currency)));
    }

    private long balance(UUID account) {
        return get("/accounts/" + account + "/balance").body().get("balanceMinor").asLong();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String problemType(Response response) {
        return response.body().get("type").asString();
    }

    /** The stored balance must always equal the SUM over the account's ledger entries. */
    private void assertMaterializedMatchesLedger(UUID... accounts) {
        for (var account : accounts) {
            var derived = jdbc.sql("""
                    SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0)
                    FROM ledger_entries WHERE account_id = ?""").param(account).query(Long.class).single();
            assertThat(balance(account)).as("materialized balance of %s", account).isEqualTo(derived);
        }
    }

    /** Releases all tasks at once from a start gate, so they genuinely race. */
    private static <T> List<T> runConcurrently(int n, Callable<T> task) throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var futures = IntStream.range(0, n).mapToObj(i -> pool.submit(() -> {
                gate.await();
                return task.call();
            })).toList();
            gate.countDown();
            var results = new ArrayList<T>();
            for (var f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }

    private Response get(String path) {
        return call(http.get().uri(path));
    }

    private Response call(RestClient.RequestHeadersSpec<?> request) {
        return request.exchange((req, res) -> {
            String body = new String(res.getBody().readAllBytes());
            var type = res.getHeaders().getContentType();
            return new Response(res.getStatusCode().value(), body.isEmpty() ? null : json.readTree(body),
                    type == null ? null : type.getType() + "/" + type.getSubtype(),
                    res.getHeaders().getFirst("Idempotent-Replayed"));
        });
    }
}
