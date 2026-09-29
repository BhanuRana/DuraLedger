package com.duraledger.ledger.account;

import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code balanceMinor} is the materialized balance (V5); null for system accounts. */
public record Account(UUID id, UUID userId, String currency, String kind, long version, Long balanceMinor,
                      OffsetDateTime createdAt) {

    public boolean isUserAccount() {
        return "USER".equals(kind);
    }
}
