package com.duraledger.ledger.web;

import com.duraledger.ledger.ledger.OptimisticConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/** Maps domain exceptions to RFC 9457 problems. Validation and malformed requests are handled by Boot. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(LedgerRejection.class)
    ResponseEntity<ProblemDetail> rejection(LedgerRejection e) {
        return ResponseEntity.status(e.status()).body(e.toProblemDetail());
    }

    /** A unique constraint said no, e.g. a second wallet for the same user and currency. */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ProblemDetail> duplicate(DuplicateKeyException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Resource already exists");
        problem.setType(URI.create("urn:duraledger:problem:already-exists"));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /** Optimistic retries exhausted under contention. Safe for the client to retry with the SAME key. */
    @ExceptionHandler(OptimisticConflictException.class)
    ResponseEntity<ProblemDetail> conflict(OptimisticConflictException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                e.getMessage() + "; retry with the same Idempotency-Key");
        problem.setType(URI.create("urn:duraledger:problem:concurrent-modification"));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
