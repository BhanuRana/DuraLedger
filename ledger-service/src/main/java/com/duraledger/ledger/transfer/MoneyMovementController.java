package com.duraledger.ledger.transfer;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MoneyMovementController {

    private final MoneyMovementService service;

    MoneyMovementController(MoneyMovementService service) {
        this.service = service;
    }

    @PostMapping("/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    DepositResponse deposit(@Valid @RequestBody DepositRequest request) {
        return service.deposit(request);
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    TransferResponse transfer(@Valid @RequestBody TransferRequest request) {
        return service.transfer(request);
    }
}
