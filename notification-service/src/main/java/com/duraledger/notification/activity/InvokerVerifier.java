package com.duraledger.notification.activity;

/** Decides whether a push request comes from an allowed caller. */
public interface InvokerVerifier {

    /** @param authorizationHeader the raw {@code Authorization} header, may be null */
    boolean isAllowed(String authorizationHeader);
}
