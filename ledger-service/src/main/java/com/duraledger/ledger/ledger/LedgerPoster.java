package com.duraledger.ledger.ledger;

import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
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
        if (legs.size() < 2) {
            throw new IllegalArgumentException("A posting needs at least two legs, got " + legs.size());
        }
        var tx = db.insertInto(TRANSACTIONS)
                .set(TRANSACTIONS.TYPE, type.name())
                .set(TRANSACTIONS.STATUS, "COMPLETED")
                .set(TRANSACTIONS.IDEMPOTENCY_KEY, idempotencyKey)
                .returning(TRANSACTIONS.ID, TRANSACTIONS.CREATED_AT)
                .fetchSingle();

        var insert = db.insertInto(LEDGER_ENTRIES,
                LEDGER_ENTRIES.TRANSACTION_ID, LEDGER_ENTRIES.ACCOUNT_ID, LEDGER_ENTRIES.CURRENCY,
                LEDGER_ENTRIES.AMOUNT_MINOR, LEDGER_ENTRIES.DIRECTION);
        for (Leg leg : legs) {
            insert = insert.values(tx.getId(), leg.accountId(), leg.currency(), leg.amountMinor(), leg.direction().name());
        }
        insert.execute();

        applyToMaterializedBalances(legs);
        return new Posted(tx.getId(), tx.getCreatedAt());
    }

    /** Keeps accounts.balance_minor equal to the SUM of the entries, in the same transaction (V5). */
    private void applyToMaterializedBalances(List<Leg> legs) {
        for (Leg leg : legs) {
            long delta = leg.direction() == Direction.CREDIT ? leg.amountMinor() : -leg.amountMinor();
            db.update(ACCOUNTS)
                    .set(ACCOUNTS.BALANCE_MINOR, ACCOUNTS.BALANCE_MINOR.plus(delta))
                    .where(ACCOUNTS.ID.eq(leg.accountId()), ACCOUNTS.KIND.eq("USER")) // system accounts stay NULL
                    .execute();
        }
    }

    public record Posted(UUID transactionId, OffsetDateTime createdAt) {}
}
