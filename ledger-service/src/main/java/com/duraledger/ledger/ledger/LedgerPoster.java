package com.duraledger.ledger.ledger;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import static com.duraledger.ledger.jooq.Tables.ACCOUNTS;
import static com.duraledger.ledger.jooq.Tables.LEDGER_ENTRIES;
import static com.duraledger.ledger.jooq.Tables.TRANSACTIONS;

/**
 * The only code path that writes ledger entries. Zero-sum per currency is also enforced by the
 * deferred trigger in V1, so a bug here fails the COMMIT instead of corrupting money.
 */
@Component
public class LedgerPoster {

    private final DSLContext db;

    LedgerPoster(DSLContext db) {
        this.db = db;
    }

    /** Must join the caller's transaction: the legs and whatever checked them commit together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Posted post(TransactionType type, String idempotencyKey, List<Leg> legs) {
        return post(type, idempotencyKey, legs, null);
    }

    /** @throws OptimisticConflictException if {@code guard}'s account changed since it was read */
    @Transactional(propagation = Propagation.MANDATORY)
    public Posted post(TransactionType type, String idempotencyKey, List<Leg> legs, VersionGuard guard) {
        return post(type, idempotencyKey, legs, guard, null);
    }

    /** @param metadataJson stored on the transaction row (e.g. the FX rate used), or null for none */
    @Transactional(propagation = Propagation.MANDATORY)
    public Posted post(TransactionType type, String idempotencyKey, List<Leg> legs, VersionGuard guard,
                       String metadataJson) {
        if (legs.size() < 2) {
            throw new IllegalArgumentException("A posting needs at least two legs, got " + legs.size());
        }
        var tx = db.insertInto(TRANSACTIONS)
                .set(TRANSACTIONS.TYPE, type.name())
                .set(TRANSACTIONS.STATUS, "COMPLETED")
                .set(TRANSACTIONS.IDEMPOTENCY_KEY, idempotencyKey)
                .set(TRANSACTIONS.METADATA, JSONB.valueOf(metadataJson == null ? "{}" : metadataJson))
                .returning(TRANSACTIONS.ID, TRANSACTIONS.CREATED_AT)
                .fetchSingle();

        var insert = db.insertInto(LEDGER_ENTRIES,
                LEDGER_ENTRIES.TRANSACTION_ID, LEDGER_ENTRIES.ACCOUNT_ID, LEDGER_ENTRIES.CURRENCY,
                LEDGER_ENTRIES.AMOUNT_MINOR, LEDGER_ENTRIES.DIRECTION);
        for (Leg leg : legs) {
            insert = insert.values(tx.getId(), leg.accountId(), leg.currency(), leg.amountMinor(), leg.direction().name());
        }
        insert.execute();

        applyToMaterializedBalances(legs, guard);
        return new Posted(tx.getId(), tx.getCreatedAt());
    }

    /**
     * Keeps accounts.balance_minor equal to the SUM of the entries, in the same transaction (V5).
     *
     * <p>Each account's net delta is applied in Postgres uuid order, so concurrent postings take row
     * locks in the same order as {@code AccountRepository#lockInIdOrder} and A->B / B->A can't
     * deadlock. Canonical strings compare like Postgres's unsigned bytes; {@code UUID.compareTo}
     * compares signed halves and would disagree for about half of all ids.
     *
     * <p>The guarded account's update is also its version compare-and-set, in the same statement: a
     * separate CAS run first would lock the source row out of order again.
     */
    private void applyToMaterializedBalances(List<Leg> legs, VersionGuard guard) {
        var deltas = new TreeMap<UUID, Long>(Comparator.comparing(UUID::toString));
        for (Leg leg : legs) {
            long signed = leg.direction() == Direction.CREDIT ? leg.amountMinor() : -leg.amountMinor();
            deltas.merge(leg.accountId(), signed, Long::sum);
        }
        deltas.forEach((accountId, delta) -> {
            var update = db.update(ACCOUNTS).set(ACCOUNTS.BALANCE_MINOR, ACCOUNTS.BALANCE_MINOR.plus(delta));
            if (guard != null && guard.accountId().equals(accountId)) {
                int updated = update.set(ACCOUNTS.VERSION, ACCOUNTS.VERSION.plus(1))
                        .where(ACCOUNTS.ID.eq(accountId), ACCOUNTS.VERSION.eq(guard.expectedVersion()))
                        .execute();
                if (updated == 0) {
                    throw new OptimisticConflictException(accountId);
                }
            } else {
                update.where(ACCOUNTS.ID.eq(accountId), ACCOUNTS.KIND.eq("USER")).execute(); // system accounts stay NULL
            }
        });
    }

    public record Posted(UUID transactionId, OffsetDateTime createdAt) {}
}
