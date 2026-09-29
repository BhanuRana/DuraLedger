package com.duraledger.ledger.ledger;

import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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
    public Posted post(TransactionType type, List<Leg> legs) {
        if (legs.size() < 2) {
            throw new IllegalArgumentException("A posting needs at least two legs, got " + legs.size());
        }
        var tx = db.insertInto(TRANSACTIONS)
                .set(TRANSACTIONS.TYPE, type.name())
                .set(TRANSACTIONS.STATUS, "COMPLETED")
                .returning(TRANSACTIONS.ID, TRANSACTIONS.CREATED_AT)
                .fetchSingle();

        var insert = db.insertInto(LEDGER_ENTRIES,
                LEDGER_ENTRIES.TRANSACTION_ID, LEDGER_ENTRIES.ACCOUNT_ID, LEDGER_ENTRIES.CURRENCY,
                LEDGER_ENTRIES.AMOUNT_MINOR, LEDGER_ENTRIES.DIRECTION);
        for (Leg leg : legs) {
            insert = insert.values(tx.getId(), leg.accountId(), leg.currency(), leg.amountMinor(), leg.direction().name());
        }
        insert.execute();

        return new Posted(tx.getId(), tx.getCreatedAt());
    }

    public record Posted(UUID transactionId, OffsetDateTime createdAt) {}
}
