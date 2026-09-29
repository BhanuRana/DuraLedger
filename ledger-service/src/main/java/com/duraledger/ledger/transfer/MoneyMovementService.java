package com.duraledger.ledger.transfer;

import com.duraledger.ledger.account.Account;
import com.duraledger.ledger.account.AccountRepository;
import com.duraledger.ledger.ledger.LedgerPoster;
import com.duraledger.ledger.web.LedgerRejection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.duraledger.ledger.ledger.Leg.credit;
import static com.duraledger.ledger.ledger.Leg.debit;
import static com.duraledger.ledger.ledger.TransactionType.DEPOSIT;
import static com.duraledger.ledger.ledger.TransactionType.TRANSFER;

/** Each money movement is ONE database transaction: checks and ledger legs commit together or not at all. */
@Service
class MoneyMovementService {

    private final AccountRepository accounts;
    private final LedgerPoster poster;

    MoneyMovementService(AccountRepository accounts, LedgerPoster poster) {
        this.accounts = accounts;
        this.poster = poster;
    }

    @Transactional
    public TransferResponse transfer(TransferRequest request) {
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw LedgerRejection.unprocessable("same-account", "Cannot transfer to the same account");
        }
        Account from = userAccount(request.fromAccountId());
        Account to = userAccount(request.toAccountId());
        requireCurrency(from, request.currency());
        requireCurrency(to, request.currency());
        requireFunds(from, request.amountMinor());

        var posted = poster.post(TRANSFER, List.of(
                debit(from.id(), request.currency(), request.amountMinor()),
                credit(to.id(), request.currency(), request.amountMinor())));

        return new TransferResponse(posted.transactionId(), TRANSFER.name(), "COMPLETED",
                from.id(), to.id(), request.amountMinor(), request.currency(), posted.createdAt());
    }

    /** Money entering from outside: debit the currency's EXTERNAL_CLEARING account, credit the user. */
    @Transactional
    public DepositResponse deposit(DepositRequest request) {
        Account account = userAccount(request.accountId());
        requireCurrency(account, request.currency());
        Account clearing = accounts.systemAccount("EXTERNAL_CLEARING", request.currency());

        var posted = poster.post(DEPOSIT, List.of(
                debit(clearing.id(), request.currency(), request.amountMinor()),
                credit(account.id(), request.currency(), request.amountMinor())));

        return new DepositResponse(posted.transactionId(), DEPOSIT.name(), "COMPLETED",
                account.id(), request.amountMinor(), request.currency(), posted.createdAt());
    }

    private void requireFunds(Account account, long amountMinor) {
        long available = accounts.balance(account.id());
        if (available < amountMinor) {
            throw LedgerRejection.unprocessable("insufficient-funds",
                    "Account " + account.id() + " has " + available + " " + account.currency()
                            + " minor units, needs " + amountMinor);
        }
    }

    private Account userAccount(UUID id) {
        return accounts.find(id).filter(Account::isUserAccount)
                .orElseThrow(() -> LedgerRejection.accountNotFound(id));
    }

    private static void requireCurrency(Account account, String currency) {
        if (!account.currency().equals(currency)) {
            throw LedgerRejection.unprocessable("currency-mismatch",
                    "Account " + account.id() + " holds " + account.currency() + ", request is in " + currency);
        }
    }
}
