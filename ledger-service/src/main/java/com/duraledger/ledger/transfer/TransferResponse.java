package com.duraledger.ledger.transfer;

import java.time.OffsetDateTime;
import java.util.UUID;

record TransferResponse(UUID transactionId, String type, String status, UUID fromAccountId, UUID toAccountId,
                        long amountMinor, String currency, OffsetDateTime createdAt) {}
