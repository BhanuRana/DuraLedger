package com.duraledger.ledger.account;

import com.duraledger.ledger.jooq.tables.records.AccountsRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import static com.duraledger.ledger.jooq.Tables.ACCOUNTS;
import static com.duraledger.ledger.jooq.Tables.ACCOUNT_BALANCES;

@Repository
public class AccountRepository {

    private final DSLContext db;

    AccountRepository(DSLContext db) {
        this.db = db;
    }

    public Account create(UUID userId, String currency) {
        return toAccount(db.insertInto(ACCOUNTS)
                .set(ACCOUNTS.USER_ID, userId)
                .set(ACCOUNTS.CURRENCY, currency)
                .returning()
                .fetchSingle());
    }

    public Optional<Account> find(UUID id) {
        return db.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(id)).fetchOptional(AccountRepository::toAccount);
    }

    public Account systemAccount(String kind, String currency) {
        return db.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.KIND.eq(kind), ACCOUNTS.CURRENCY.eq(currency))
                .fetchSingle(AccountRepository::toAccount);
    }

    /** Derived from the ledger (the account_balances view): a SUM over the account's entries. */
    public long balance(UUID accountId) {
        return db.select(ACCOUNT_BALANCES.BALANCE_MINOR)
                .from(ACCOUNT_BALANCES)
                .where(ACCOUNT_BALANCES.ACCOUNT_ID.eq(accountId))
                .fetchSingle(ACCOUNT_BALANCES.BALANCE_MINOR);
    }

    private static Account toAccount(AccountsRecord r) {
        return new Account(r.getId(), r.getUserId(), r.getCurrency(), r.getKind(), r.getCreatedAt());
    }
}
