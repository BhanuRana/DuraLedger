package com.duraledger.ledger.account;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Account(UUID id, UUID userId, String currency, String kind, long version, OffsetDateTime createdAt) {

    public boolean isUserAccount() {
        return "USER".equals(kind);
    }
}
