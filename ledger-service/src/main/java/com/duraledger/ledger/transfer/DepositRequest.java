package com.duraledger.ledger.transfer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

record DepositRequest(
        @NotNull UUID accountId,
        @NotNull @Positive Long amountMinor,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {}
