package com.duraledger.ledger.transfer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/** Convert {@code amountMinor} out of {@code fromAccountId} into the same user's {@code toCurrency} wallet. */
record FxConversionRequest(
        @NotNull UUID fromAccountId,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String toCurrency,
        @NotNull @Positive Long amountMinor) {}
