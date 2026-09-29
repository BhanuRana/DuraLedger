package com.duraledger.ledger.benchmark;

import com.duraledger.ledger.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Pessimistic vs optimistic locking under load, over real HTTP against real Postgres 16.
 * Not part of the normal build (surefire only includes *Test/*Tests/*Spec). Run explicitly:
 * <pre>./mvnw test -Dtest='*LockingBenchmark'</pre>
 * Results append to target/benchmark/results.md.
 *
 * <p>Three scenarios, same total work:
 * <ul>
 *   <li><b>hot</b>    - every transfer debits ONE account (worst case: a merchant/payroll account)</li>
 *   <li><b>hot-deep</b> - same, but the account already has 50k ledger entries of history</li>
 *   <li><b>spread</b> - each worker debits its own account (typical: independent users)</li>
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class LockingBenchmark {

    static final int WORKERS = 32;
    static final int TRANSFERS = 2_000;
    static final int WARMUP_TRANSFERS = 500;
    static final int DEEP_HISTORY_DEPOSITS = 50_000; // 50k entries of history on the hot account (and 50k on clearing)

    @Value("${local.server.port}") int port;
    @Value("${duraledger.transfer.locking}") String mode;
    @Autowired JsonMapper json;
    @Autowired MeterRegistry meters;
    @Autowired JdbcClient jdbc;

    RestClient http;

    @Test
    void benchmark() throws Exception {
        http = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();

        run("warmup", Scenario.SPREAD, WARMUP_TRANSFERS); // JIT, connection pools, Postgres caches - discarded
        var results = List.of(
                run("hot", Scenario.HOT, TRANSFERS),
                run("hot-deep", Scenario.HOT_DEEP, TRANSFERS),
                run("spread", Scenario.SPREAD, TRANSFERS));

        var report = new StringBuilder();
        for (var r : results) {
            report.append(r.toMarkdownRow(mode)).append('\n');
        }
        System.out.println("\n| mode | scenario | ok | 409 | other | req/s | p50 ms | p95 ms | p99 ms | max ms | CAS conflicts |\n"
                + "|---|---|---|---|---|---|---|---|---|---|---|\n" + report);
        write(report.toString());
    }

    enum Scenario { HOT, HOT_DEEP, SPREAD }

    private Result run(String scenario, Scenario kind, int transfers) throws Exception {
        var recipients = IntStream.range(0, WORKERS).mapToObj(i -> account()).toList();
        var sources = kind == Scenario.SPREAD
                ? IntStream.range(0, WORKERS).mapToObj(i -> account()).toList()
                : Collections.nCopies(WORKERS, account());
        sources.stream().distinct().forEach(s -> deposit(s, 1_000_000_000L));
        if (kind == Scenario.HOT_DEEP) {
            seedHistory(sources.getFirst(), DEEP_HISTORY_DEPOSITS);
        }

        double conflictsBefore = conflicts();
        var latenciesMicros = Collections.synchronizedList(new ArrayList<Long>(transfers));
        var ok = new AtomicInteger();
        var conflict = new AtomicInteger();
        var other = new AtomicInteger();
        var next = new AtomicInteger();

        long start = System.nanoTime();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < WORKERS; w++) {
                int worker = w;
                pool.submit(() -> {
                    while (next.getAndIncrement() < transfers) {
                        long t0 = System.nanoTime();
                        int status = transfer(sources.get(worker), recipients.get(worker));
                        latenciesMicros.add((System.nanoTime() - t0) / 1_000);
                        (status == 201 ? ok : status == 409 ? conflict : other).incrementAndGet();
                    }
                });
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;

        var sorted = new ArrayList<>(latenciesMicros);
        Collections.sort(sorted);
        return new Result(scenario, ok.get(), conflict.get(), other.get(), transfers / seconds,
                pct(sorted, 50), pct(sorted, 95), pct(sorted, 99), sorted.getLast() / 1000.0,
                (long) (conflicts() - conflictsBefore));
    }

    record Result(String scenario, int ok, int conflict409, int other, double rps,
                  double p50, double p95, double p99, double max, long casConflicts) {

        String toMarkdownRow(String mode) {
            return "| %s | %s | %d | %d | %d | %.0f | %.1f | %.1f | %.1f | %.1f | %d |".formatted(
                    mode, scenario, ok, conflict409, other, rps, p50, p95, p99, max, casConflicts);
        }
    }

    // --- helpers -------------------------------------------------------------

    private double conflicts() {
        var counter = meters.find("duraledger.transfers.optimistic.conflicts").counter();
        return counter == null ? 0 : counter.count();
    }

    private static double pct(List<Long> sortedMicros, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sortedMicros.size()) - 1;
        return sortedMicros.get(Math.max(0, index)) / 1000.0;
    }

    /** Bulk-inserts N small balanced deposits directly in SQL (the API would take minutes). */
    private void seedHistory(UUID account, int deposits) {
        jdbc.sql("""
                WITH tx AS (
                    INSERT INTO transactions (type, status)
                    SELECT 'DEPOSIT', 'COMPLETED' FROM generate_series(1, :n)
                    RETURNING id)
                INSERT INTO ledger_entries (transaction_id, account_id, currency, amount_minor, direction)
                SELECT tx.id, leg.account_id, 'USD', 1000, leg.direction
                FROM tx CROSS JOIN (
                    SELECT id, 'DEBIT' FROM accounts WHERE kind = 'EXTERNAL_CLEARING' AND currency = 'USD'
                    UNION ALL SELECT :account, 'CREDIT') AS leg (account_id, direction)
                """).param("n", deposits).param("account", account).update();
        // keep the stored balance (V5) equal to the seeded entries
        jdbc.sql("UPDATE accounts SET balance_minor = balance_minor + :delta WHERE id = :account")
                .param("delta", deposits * 1000L).param("account", account).update();
        jdbc.sql("ANALYZE ledger_entries").update();
    }

    private UUID account() {
        String body = http.post().uri("/accounts").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("userId", UUID.randomUUID(), "currency", "USD"))
                .retrieve().body(String.class);
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    private void deposit(UUID account, long amount) {
        http.post().uri("/deposits").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("accountId", account, "amountMinor", amount, "currency", "USD"))
                .retrieve().toBodilessEntity();
    }

    private int transfer(UUID from, UUID to) {
        return http.post().uri("/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fromAccountId", from, "toAccountId", to, "amountMinor", 1, "currency", "USD"))
                .retrieve().toBodilessEntity().getStatusCode().value();
    }

    private static void write(String rows) throws IOException {
        var file = Path.of("target/benchmark/results.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, rows, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
