package com.duraledger.ledger.account;

import com.duraledger.ledger.jooq.tables.records.AccountsRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
     * {@code SELECT ... FOR UPDATE} on all given accounts, taking the row locks in id order. A consistent
     * order is what prevents deadlocks: if A->B locked A then B while B->A locked B then A, each would
     * wait for the other forever. Missing ids are simply absent from the result.
     */
    public Map<UUID, Account> lockInIdOrder(UUID... ids) {
        return db.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.ID.in(ids))
                .orderBy(ACCOUNTS.ID)
                .forUpdate()
                .fetch(AccountRepository::toAccount)
                .stream()
                .collect(Collectors.toMap(Account::id, Function.identity()));
    }

    /** The same user's wallet in another currency (one per user and currency). */
    public Optional<Account> findUserAccount(UUID userId, String currency) {
        return db.selectFrom(ACCOUNTS)
                .where(ACCOUNTS.USER_ID.eq(userId), ACCOUNTS.CURRENCY.eq(currency), ACCOUNTS.KIND.eq("USER"))
                .fetchOptional(AccountRepository::toAccount);
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
