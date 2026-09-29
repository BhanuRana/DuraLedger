package com.duraledger.ledger.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

/**
 * A refusal the client can act on (always 4xx), rendered as RFC 9457 problem+json with a stable
 * {@code type} URN. Clients branch on the type, never on the human-readable detail.
 */
public class LedgerRejection extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public LedgerRejection(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public static LedgerRejection accountNotFound(Object accountId) {
        return new LedgerRejection(HttpStatus.NOT_FOUND, "account-not-found", "Account " + accountId + " does not exist");
    }

    public static LedgerRejection unprocessable(String code, String detail) {
        return new LedgerRejection(HttpStatus.UNPROCESSABLE_CONTENT, code, detail);
    }

    public HttpStatus status() {
        return status;
    }

    public ProblemDetail toProblemDetail() {
        var problem = ProblemDetail.forStatusAndDetail(status, getMessage());
        problem.setType(URI.create("urn:duraledger:problem:" + code));
        return problem;
    }
}
