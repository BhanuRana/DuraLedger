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
        assertThat(balance(alice)).isEqualTo(700);
        assertThat(balance(bob)).isEqualTo(300);
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
        assertThat(responses).extracting(Response::status).containsOnly(201, 422);
        assertThat(succeeded).isEqualTo(10);
        assertThat(balance(alice)).isZero();
        assertThat(balance(bob)).isEqualTo(1_000);
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

    record Response(int status, JsonNode body, String contentType) {}

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
                    type == null ? null : type.getType() + "/" + type.getSubtype());
        });
    }
}
