package com.duraledger.ledger.transfer;

import com.duraledger.ledger.account.Account;
import com.duraledger.ledger.account.AccountRepository;
import com.duraledger.ledger.idempotency.IdempotencyService;
import com.duraledger.ledger.idempotency.IdempotencyService.Outcome;
import com.duraledger.ledger.idempotency.IdempotencyService.Result;
import com.duraledger.ledger.ledger.LedgerPoster;
import com.duraledger.ledger.ledger.VersionGuard;
import com.duraledger.ledger.web.LedgerRejection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.duraledger.ledger.ledger.Leg.credit;
import static com.duraledger.ledger.ledger.Leg.debit;
import static com.duraledger.ledger.ledger.TransactionType.DEPOSIT;
import static com.duraledger.ledger.ledger.TransactionType.TRANSFER;

/**
 * Each money movement is ONE database transaction: idempotency claim -> lock -> checks -> ledger legs
 * -> idempotency completion. Either all of it commits or none of it does.
 */
@Service
class MoneyMovementService {

    private final AccountRepository accounts;
    private final LedgerPoster poster;
    private final IdempotencyService idempotency;
    private final TransferProperties properties;

    MoneyMovementService(AccountRepository accounts, LedgerPoster poster, IdempotencyService idempotency,
                         TransferProperties properties) {
        this.accounts = accounts;
        this.poster = poster;
        this.idempotency = idempotency;
        this.properties = properties;
    }

    @Transactional
    public Result transfer(String idempotencyKey, TransferRequest request) {
        return idempotency.execute(idempotencyKey, "POST /transfers", request, () -> {
            if (request.fromAccountId().equals(request.toAccountId())) {
                throw LedgerRejection.unprocessable("same-account", "Cannot transfer to the same account");
            }
            Account from;
            Account to;
            if (properties.locking() == LockingMode.PESSIMISTIC) {
                // Both rows get their balance updated, so lock both up front, in id order, so that
                // A->B and B->A running together can't deadlock.
                var locked = accounts.lockInIdOrder(request.fromAccountId(), request.toAccountId());
                from = userAccount(locked.get(request.fromAccountId()), request.fromAccountId());
                to = userAccount(locked.get(request.toAccountId()), request.toAccountId());
            } else {
                // OPTIMISTIC: read without locks; the version guard on the debit detects a race.
                from = userAccount(request.fromAccountId());
                to = userAccount(request.toAccountId());
            }
            requireCurrency(from, request.currency());
            requireCurrency(to, request.currency());
            requireFunds(from, request.amountMinor());

            // The debit is guarded by the version we read. Under OPTIMISTIC a concurrent debit makes the
            // poster throw OptimisticConflictException (everything rolls back, TransferRetrier retries).
            // Under PESSIMISTIC we hold the lock, so it can't fail; it just bumps the version, which
            // keeps both modes safe side by side.
            var posted = poster.post(TRANSFER, idempotencyKey, List.of(
                    debit(from.id(), request.currency(), request.amountMinor()),
                    credit(to.id(), request.currency(), request.amountMinor())),
                    new VersionGuard(from.id(), from.version()));

            return new Outcome(201, new TransferResponse(posted.transactionId(), TRANSFER.name(), "COMPLETED",
                    from.id(), to.id(), request.amountMinor(), request.currency(), posted.createdAt()));
        });
    }

    /** Money entering from outside: debit the currency's EXTERNAL_CLEARING account, credit the user. */
    @Transactional
    public Result deposit(String idempotencyKey, DepositRequest request) {
        return idempotency.execute(idempotencyKey, "POST /deposits", request, () -> {
            Account account = userAccount(request.accountId());
            requireCurrency(account, request.currency());
            Account clearing = accounts.systemAccount("EXTERNAL_CLEARING", request.currency());

            var posted = poster.post(DEPOSIT, idempotencyKey, List.of(
                    debit(clearing.id(), request.currency(), request.amountMinor()),
                    credit(account.id(), request.currency(), request.amountMinor())));

            return new Outcome(201, new DepositResponse(posted.transactionId(), DEPOSIT.name(), "COMPLETED",
                    account.id(), request.amountMinor(), request.currency(), posted.createdAt()));
        });
    }

    private static void requireFunds(Account account, long amountMinor) {
        long available = account.balanceMinor(); // materialized (V5): O(1), not a SUM over history
        if (available < amountMinor) {
            throw LedgerRejection.unprocessable("insufficient-funds",
                    "Account " + account.id() + " has " + available + " " + account.currency()
                            + " minor units, needs " + amountMinor);
        }
    }

    private Account userAccount(UUID id) {
        return userAccount(accounts.find(id).orElse(null), id);
    }

    private static Account userAccount(Account account, UUID requestedId) {
        if (account == null || !account.isUserAccount()) {
            throw LedgerRejection.accountNotFound(requestedId);
        }
        return account;
    }

    private static void requireCurrency(Account account, String currency) {
        if (!account.currency().equals(currency)) {
            throw LedgerRejection.unprocessable("currency-mismatch",
                    "Account " + account.id() + " holds " + account.currency() + ", request is in " + currency);
        }
    }
}
