package com.duraledger.ledger.tasks;

import com.google.auth.oauth2.TokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Accepts only Google-signed OIDC ID tokens (what Cloud Scheduler and Pub/Sub push attach) that are
 * (1) issued for this service's URL as audience and (2) belong to an allow-listed service account.
 * The service itself is publicly reachable, so the task endpoints can't rely on Cloud Run IAM alone.
 */
class GoogleOidcInvokerVerifier implements InvokerVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleOidcInvokerVerifier.class);

    private final TokenVerifier verifier;
    private final Set<String> allowedInvokers;

    GoogleOidcInvokerVerifier(String audience, Set<String> allowedInvokers) {
        this.verifier = TokenVerifier.newBuilder().setAudience(audience).build();
        this.allowedInvokers = allowedInvokers;
    }

    @Override
    public boolean isAllowed(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return false;
        }
        try {
            var payload = verifier.verify(authorizationHeader.substring("Bearer ".length())).getPayload();
            var email = (String) payload.get("email");
            boolean ok = Boolean.TRUE.equals(payload.get("email_verified")) && allowedInvokers.contains(email);
            if (!ok) {
                log.warn("Rejected task call from {}", email);
            }
            return ok;
        } catch (TokenVerifier.VerificationException | IllegalArgumentException e) { // bad signature, expired, malformed
            log.warn("Rejected task call: {}", e.getMessage());
            return false;
        }
    }
}
