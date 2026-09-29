package com.duraledger.ledger.account;

import com.duraledger.ledger.jooq.tables.records.AccountsRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import static com.duraledger.ledger.jooq.Tables.ACCOUNTS;

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

    /**
     * {@code SELECT ... FOR UPDATE}: the row lock is held until the caller's transaction ends, so a
     * concurrent debit of the same account waits here and then sees the balance after this one.
     */
    public Optional<Account> lockForUpdate(UUID id) {
        return db.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(id)).forUpdate().fetchOptional(AccountRepository::toAccount);
    }

    /**
     * Compare-and-set on the version read earlier. Returns false if someone else changed the account in
     * between. The UPDATE also takes the row lock, so a concurrent CAS waits for this transaction to
     * end and then finds the version moved.
     */
    public boolean bumpVersion(UUID id, long expectedVersion) {
        return db.update(ACCOUNTS)
                .set(ACCOUNTS.VERSION, ACCOUNTS.VERSION.plus(1))
                .where(ACCOUNTS.ID.eq(id), ACCOUNTS.VERSION.eq(expectedVersion))
                .execute() == 1;
    }

    public Account systemAccount(String kind, String currency) {
        return db.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.KIND.eq(kind), ACCOUNTS.CURRENCY.eq(currency))
                .fetchSingle(AccountRepository::toAccount);
    }

    private static Account toAccount(AccountsRecord r) {
        return new Account(r.getId(), r.getUserId(), r.getCurrency(), r.getKind(), r.getVersion(), r.getBalanceMinor(),
                r.getCreatedAt());
    }
}
