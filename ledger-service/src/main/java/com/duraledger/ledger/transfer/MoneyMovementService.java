package com.duraledger.ledger.transfer;

import com.duraledger.ledger.account.Account;
import com.duraledger.ledger.account.AccountRepository;
import com.duraledger.ledger.idempotency.IdempotencyService;
import com.duraledger.ledger.idempotency.IdempotencyService.Outcome;
import com.duraledger.ledger.idempotency.IdempotencyService.Result;
import com.duraledger.ledger.ledger.LedgerPoster;
import com.duraledger.ledger.ledger.VersionGuard;
import com.duraledger.ledger.outbox.Outbox;
import com.duraledger.ledger.web.LedgerRejection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.duraledger.ledger.ledger.Leg.credit;
import static com.duraledger.ledger.ledger.Leg.debit;
import static com.duraledger.ledger.ledger.TransactionType.DEPOSIT;
import static com.duraledger.ledger.ledger.TransactionType.TRANSFER;
import static com.duraledger.ledger.ledger.TransactionType.WITHDRAWAL;

/**
 * Each money movement is ONE database transaction: idempotency claim -> lock -> checks -> ledger legs
 * -> outbox event -> idempotency completion. Either all of it commits or none of it does.
 */
@Service
class MoneyMovementService {

    private final AccountRepository accounts;
    private final LedgerPoster poster;
    private final IdempotencyService idempotency;
    private final TransferProperties properties;
    private final Outbox outbox;

    MoneyMovementService(AccountRepository accounts, LedgerPoster poster, IdempotencyService idempotency,
                         TransferProperties properties, Outbox outbox) {
        this.accounts = accounts;
        this.poster = poster;
        this.idempotency = idempotency;
        this.properties = properties;
        this.outbox = outbox;
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

            var response = new TransferResponse(posted.transactionId(), TRANSFER.name(), "COMPLETED",
                    from.id(), to.id(), request.amountMinor(), request.currency(), posted.createdAt());
            outbox.append("transaction", posted.transactionId(), "TransferCompleted", response);
            return new Outcome(201, response);
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

            var response = new DepositResponse(posted.transactionId(), DEPOSIT.name(), "COMPLETED",
                    account.id(), request.amountMinor(), request.currency(), posted.createdAt());
            outbox.append("transaction", posted.transactionId(), "DepositCompleted", response);
            return new Outcome(201, response);
        });
    }

    /** Money leaving to the outside world: debit the user, credit the currency's EXTERNAL_CLEARING account. */
    @Transactional
    public Result withdraw(String idempotencyKey, WithdrawalRequest request) {
        return idempotency.execute(idempotencyKey, "POST /withdrawals", request, () -> {
            // A debit, so it's serialized exactly like a transfer's source account.
            Account account = properties.locking() == LockingMode.PESSIMISTIC
                    ? userAccount(accounts.lockInIdOrder(request.accountId()).get(request.accountId()), request.accountId())
                    : userAccount(request.accountId());
            requireCurrency(account, request.currency());
            requireFunds(account, request.amountMinor());
            Account clearing = accounts.systemAccount("EXTERNAL_CLEARING", request.currency());

            var posted = poster.post(WITHDRAWAL, idempotencyKey, List.of(
                    debit(account.id(), request.currency(), request.amountMinor()),
                    credit(clearing.id(), request.currency(), request.amountMinor())),
                    new VersionGuard(account.id(), account.version()));

            var response = new WithdrawalResponse(posted.transactionId(), WITHDRAWAL.name(), "COMPLETED",
                    account.id(), request.amountMinor(), request.currency(), posted.createdAt());
            outbox.append("transaction", posted.transactionId(), "WithdrawalCompleted", response);
            return new Outcome(201, response);
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
