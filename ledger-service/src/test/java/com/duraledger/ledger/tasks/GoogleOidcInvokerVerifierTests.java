package com.duraledger.ledger.tasks;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The real verifier's rejection paths: none of these may throw, all must be refused. */
class GoogleOidcInvokerVerifierTests {

    private final GoogleOidcInvokerVerifier verifier =
            new GoogleOidcInvokerVerifier("https://ledger.example", Set.of("scheduler@example.iam.gserviceaccount.com"));

    @Test
    void missing_or_non_bearer_headers_are_refused() {
        assertThat(verifier.isAllowed(null)).isFalse();
        assertThat(verifier.isAllowed("")).isFalse();
        assertThat(verifier.isAllowed("Basic dXNlcjpwYXNz")).isFalse();
    }

    @Test
    void malformed_and_unsigned_tokens_are_refused_without_throwing() {
        assertThat(verifier.isAllowed("Bearer not-a-jwt")).isFalse();
        // syntactically valid JWT with alg=none and a claimed allowed email - must still be refused
        assertThat(verifier.isAllowed("Bearer eyJhbGciOiJub25lIn0."
                + "eyJlbWFpbCI6InNjaGVkdWxlckBleGFtcGxlLmlhbS5nc2VydmljZWFjY291bnQuY29tIiwiZW1haWxfdmVyaWZpZWQiOnRydWV9.")).isFalse();
    }
}
