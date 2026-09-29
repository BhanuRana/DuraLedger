package com.duraledger.ledger.account;

import com.duraledger.ledger.web.LedgerRejection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
class AccountController {

    private final AccountRepository accounts;

    AccountController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
        return AccountResponse.of(accounts.create(request.userId(), request.currency()));
    }

    @GetMapping("/{id}")
    AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.of(userAccount(id));
    }

    @GetMapping("/{id}/balance")
    BalanceResponse balance(@PathVariable UUID id) {
        var account = userAccount(id);
        return new BalanceResponse(account.id(), account.currency(), accounts.balance(account.id()));
    }

    // System accounts (clearing, FX pools) are internal: the public API treats them as not found.
    private Account userAccount(UUID id) {
        return accounts.find(id).filter(Account::isUserAccount)
                .orElseThrow(() -> LedgerRejection.accountNotFound(id));
    }

    record CreateAccountRequest(
            @NotNull UUID userId,
            @NotNull @Pattern(regexp = "HKD|USD|EUR|GBP", message = "supported currencies: HKD, USD, EUR, GBP") String currency) {}

    record AccountResponse(UUID id, UUID userId, String currency, OffsetDateTime createdAt) {
        static AccountResponse of(Account a) {
            return new AccountResponse(a.id(), a.userId(), a.currency(), a.createdAt());
        }
    }

    record BalanceResponse(UUID accountId, String currency, long balanceMinor) {}
}
