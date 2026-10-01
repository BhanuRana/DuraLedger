package com.duraledger.ledger.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Money-moving endpoints. Idempotency-Key is mandatory: a missing header is a 400, by design. */
@RestController
class MoneyMovementController {

    private final MoneyMovementService service;
    private final TransferRetrier transfers;

    MoneyMovementController(MoneyMovementService service, TransferRetrier transfers) {
        this.service = service;
        this.transfers = transfers;
    }

    @PostMapping("/deposits")
    ResponseEntity<String> deposit(@RequestHeader("Idempotency-Key") @Size(min = 1, max = 255) String idempotencyKey,
                                   @Valid @RequestBody DepositRequest request) {
        return service.deposit(idempotencyKey, request).toResponseEntity();
    }

    @PostMapping("/transfers")
    ResponseEntity<String> transfer(@RequestHeader("Idempotency-Key") @Size(min = 1, max = 255) String idempotencyKey,
                                    @Valid @RequestBody TransferRequest request) {
        return transfers.transfer(idempotencyKey, request).toResponseEntity();
    }

    @PostMapping("/withdrawals")
    ResponseEntity<String> withdraw(@RequestHeader("Idempotency-Key") @Size(min = 1, max = 255) String idempotencyKey,
                                    @Valid @RequestBody WithdrawalRequest request) {
        return transfers.withdraw(idempotencyKey, request).toResponseEntity();
    }
}
