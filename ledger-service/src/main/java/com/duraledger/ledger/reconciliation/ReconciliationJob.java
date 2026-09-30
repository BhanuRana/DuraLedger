package com.duraledger.ledger.reconciliation;

import com.duraledger.ledger.reconciliation.ReconciliationReport.BalanceMismatch;
import com.duraledger.ledger.reconciliation.ReconciliationReport.CurrencyImbalance;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.duraledger.ledger.jooq.Tables.ACCOUNTS;
import static com.duraledger.ledger.jooq.Tables.LEDGER_ENTRIES;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.sum;
import static org.jooq.impl.DSL.when;

/**
 * Continuously proves the ledger is correct, instead of assuming it:
 * <ol>
 *   <li><b>Whole-ledger zero-sum per currency</b>: strictly stronger than the per-transaction
 *       trigger, because it also catches entries written with triggers disabled, a bad manual fix,
 *       or a bug in the trigger itself.</li>
 *   <li><b>Stored balance = ledger</b>: {@code accounts.balance_minor} (V5) is a projection of the
 *       entries; this check is what makes storing the balance safe.</li>
 * </ol>
 * Runs READ ONLY at REPEATABLE READ, so both checks see one snapshot even while transfers commit.
 * Read-only and idempotent, so every replica running it is harmless (just redundant work).
 *
 * <p>Deliberately not a readiness probe: failing readiness would pull every replica out of the load
 * balancer and halt all payments, while a ledger inconsistency needs a human.
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private static final Field<Long> SIGNED_AMOUNT = when(LEDGER_ENTRIES.DIRECTION.eq("CREDIT"), LEDGER_ENTRIES.AMOUNT_MINOR)
            .otherwise(LEDGER_ENTRIES.AMOUNT_MINOR.neg());

    private final DSLContext db;
    private final Timer duration;
    private final AtomicLong currencyViolations = new AtomicLong();
    private final AtomicLong balanceViolations = new AtomicLong();
    private final AtomicLong lastRunEpochSeconds = new AtomicLong();
    private final AtomicReference<ReconciliationReport> latest = new AtomicReference<>();

    ReconciliationJob(DSLContext db, MeterRegistry meters) {
        this.db = db;
        this.duration = meters.timer("duraledger.reconciliation.duration");
        // Alert on: violations > 0, or last run older than ~3x the interval (the job itself died).
        meters.gauge("duraledger.reconciliation.violations", Tags.of("check", "currency_zero_sum"),
                currencyViolations);
        meters.gauge("duraledger.reconciliation.violations", Tags.of("check", "materialized_balance"),
                balanceViolations);
        meters.gauge("duraledger.reconciliation.last.run.timestamp.seconds", lastRunEpochSeconds);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport run() {
        var startedAt = OffsetDateTime.now();
        long t0 = System.nanoTime();

        long entries = db.select(count()).from(LEDGER_ENTRIES).fetchSingle().value1();

        var imbalances = db.select(LEDGER_ENTRIES.CURRENCY, sum(SIGNED_AMOUNT))
                .from(LEDGER_ENTRIES)
                .groupBy(LEDGER_ENTRIES.CURRENCY)
                .having(sum(SIGNED_AMOUNT).ne(inline(BigDecimal.ZERO)))
                .fetch(r -> new CurrencyImbalance(r.value1(), r.value2().longValueExact()));

        var ledgerBalance = coalesce(sum(SIGNED_AMOUNT), inline(BigDecimal.ZERO));
        var mismatches = db.select(ACCOUNTS.ID, ACCOUNTS.CURRENCY, ACCOUNTS.BALANCE_MINOR, ledgerBalance)
                .from(ACCOUNTS)
                .leftJoin(LEDGER_ENTRIES).on(LEDGER_ENTRIES.ACCOUNT_ID.eq(ACCOUNTS.ID))
                .where(ACCOUNTS.KIND.eq("USER"))
                .groupBy(ACCOUNTS.ID, ACCOUNTS.CURRENCY, ACCOUNTS.BALANCE_MINOR)
                .having(ledgerBalance.ne(ACCOUNTS.BALANCE_MINOR.cast(BigDecimal.class)))
                .fetch(r -> new BalanceMismatch(r.value1(), r.value2(), r.value3(), r.value4().longValueExact()));

        var took = Duration.ofNanos(System.nanoTime() - t0);
        var report = new ReconciliationReport(startedAt, took, entries, imbalances, mismatches);
        record(report);
        return report;
    }

    public ReconciliationReport latest() {
        return latest.get();
    }

    private void record(ReconciliationReport report) {
        duration.record(report.duration());
        currencyViolations.set(report.currencyImbalances().size());
        balanceViolations.set(report.balanceMismatches().size());
        lastRunEpochSeconds.set(report.startedAt().toEpochSecond());
        latest.set(report);
        if (report.healthy()) {
            log.info("Reconciliation OK: {} entries checked in {} ms", report.entriesChecked(), report.duration().toMillis());
        } else {
            // ERROR on purpose: in production this pages someone. Money invariants don't get a WARN.
            log.error("RECONCILIATION FAILED: currency imbalances={}, balance mismatches={}",
                    report.currencyImbalances(), report.balanceMismatches());
        }
    }
}
