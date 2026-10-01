package com.duraledger.ledger.tasks;

/** Decides whether a request to an internal task endpoint comes from an allowed caller. */
public interface InvokerVerifier {

    /** @param authorizationHeader the raw {@code Authorization} header, may be null */
    boolean isAllowed(String authorizationHeader);
}
