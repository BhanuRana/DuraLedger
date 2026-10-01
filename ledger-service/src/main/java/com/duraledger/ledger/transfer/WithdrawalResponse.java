package com.duraledger.ledger.transfer;

import java.time.OffsetDateTime;
import java.util.UUID;

record WithdrawalResponse(UUID transactionId, String type, String status, UUID accountId,
                          long amountMinor, String currency, OffsetDateTime createdAt) {}
