package com.duraledger.ledger.transfer;

import org.springframework.test.context.TestPropertySource;

/** The whole money-movement suite again, with optimistic locking: both strategies must be correct. */
@TestPropertySource(properties = "duraledger.transfer.locking=optimistic")
class OptimisticMoneyMovementApiTests extends MoneyMovementApiTests {

    @Override
    boolean strictlySerialized() {
        return false;
    }
}
