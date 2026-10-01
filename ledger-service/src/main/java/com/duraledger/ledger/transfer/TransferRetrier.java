package com.duraledger.ledger.transfer;

import com.duraledger.ledger.idempotency.IdempotencyService.Result;
import com.duraledger.ledger.ledger.OptimisticConflictException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Retries a debit (transfer or withdrawal) that lost an optimistic race. Lives OUTSIDE the @Transactional boundary on
 * purpose: each attempt must be a brand-new DB transaction that re-reads balance and version.
 *
 * <p>Retrying is safe because the failed attempt rolled back everything, its idempotency claim
 * included. If a duplicate request claimed the key meanwhile, the retry simply replays the
 * duplicate's result: still exactly once.
 */
@Component
class TransferRetrier {

    private final MoneyMovementService service;
    private final TransferProperties properties;
    private final Counter conflicts;
    private final Counter exhausted;

    TransferRetrier(MoneyMovementService service, TransferProperties properties, MeterRegistry meters) {
        this.service = service;
        this.properties = properties;
        this.conflicts = meters.counter("duraledger.transfers.optimistic.conflicts");
        this.exhausted = meters.counter("duraledger.transfers.optimistic.exhausted");
    }

    Result transfer(String idempotencyKey, TransferRequest request) {
        return withRetries(() -> service.transfer(idempotencyKey, request));
    }

    Result withdraw(String idempotencyKey, WithdrawalRequest request) {
        return withRetries(() -> service.withdraw(idempotencyKey, request));
    }

    /** Each attempt goes through the service's @Transactional proxy: a fresh DB transaction. */
    private Result withRetries(Supplier<Result> attempt) {
        for (int n = 1; ; n++) {
            try {
                return attempt.get();
            } catch (OptimisticConflictException e) {
                conflicts.increment();
                if (n >= properties.maxOptimisticAttempts()) {
                    exhausted.increment();
                    throw e;
                }
                backoff(n);
            }
        }
    }

    /** "Full jitter": a random sleep in [0, min(cap, 2^attempt)) ms, so losers don't collide again in lockstep. */
    private static void backoff(int attempt) {
        long ceilingMillis = Math.min(50, 1L << attempt);
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(ceilingMillis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
