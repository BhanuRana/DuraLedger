package com.duraledger.ledger.benchmark;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "duraledger.transfer.locking=optimistic")
class OptimisticLockingBenchmark extends LockingBenchmark {}
