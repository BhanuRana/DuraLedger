package com.duraledger.ledger.transfer;

import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code rate} = units of toCurrency per unit of fromCurrency, as a decimal string (never a double). */
record FxConversionResponse(UUID transactionId, String type, String status,
                            UUID fromAccountId, long fromAmountMinor, String fromCurrency,
                            UUID toAccountId, long toAmountMinor, String toCurrency,
                            String rate, OffsetDateTime createdAt) {}
