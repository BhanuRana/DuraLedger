package com.duraledger.ledger.ledger;

import java.util.UUID;

/** One side of a posting. The amount is always positive minor units; the sign is the direction. */
public record Leg(UUID accountId, String currency, long amountMinor, Direction direction) {

    public static Leg debit(UUID accountId, String currency, long amountMinor) {
        return new Leg(accountId, currency, amountMinor, Direction.DEBIT);
    }

    public static Leg credit(UUID accountId, String currency, long amountMinor) {
        return new Leg(accountId, currency, amountMinor, Direction.CREDIT);
    }
}
