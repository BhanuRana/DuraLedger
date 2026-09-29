package com.duraledger.ledger.idempotency;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

import static com.duraledger.ledger.jooq.Tables.IDEMPOTENCY_KEYS;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * Exactly-once execution per Idempotency-Key, enforced by Postgres rather than a check-then-act.
 *
 * <p>The key is claimed with {@code INSERT ... ON CONFLICT DO NOTHING} <em>inside the caller's DB
 * transaction</em>. A concurrent duplicate's INSERT hits the winner's uncommitted unique-index entry
 * and blocks until the winner commits (then it replays the stored response) or rolls back (then it
 * claims the key and runs itself). So:
 * <ul>
 *   <li>no application-level polling loop: Postgres's own lock does the waiting;</li>
 *   <li>a crash mid-request rolls the claim back with everything else, so no key is stuck PENDING.</li>
 * </ul>
 * Requires READ COMMITTED (the Postgres default): the replay SELECT must see the winner's commit.
 */
@Service
public class IdempotencyService {

    private final DSLContext db;
    private final JsonMapper json;

    IdempotencyService(DSLContext db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    /** @param operation names the endpoint, so the same key can't be replayed against a different one */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result execute(String key, String operation, Object request, Supplier<Outcome> action) {
        String requestHash = sha256(operation + "\n" + json.writeValueAsString(request));

        int claimed = db.insertInto(IDEMPOTENCY_KEYS)
                .set(IDEMPOTENCY_KEYS.KEY, key)
                .set(IDEMPOTENCY_KEYS.REQUEST_HASH, requestHash)
                .set(IDEMPOTENCY_KEYS.PROCESSING_STATUS, "PENDING")
                .onConflictDoNothing()
                .execute();
        if (claimed == 0) {
            return replay(key);
        }

        Outcome outcome = action.get();
        String body = json.writeValueAsString(outcome.body());
        db.update(IDEMPOTENCY_KEYS)
                .set(IDEMPOTENCY_KEYS.PROCESSING_STATUS, "COMPLETED")
                .set(IDEMPOTENCY_KEYS.RESPONSE_BODY, JSONB.valueOf(body))
                .set(IDEMPOTENCY_KEYS.STATUS_CODE, outcome.status())
                .where(IDEMPOTENCY_KEYS.KEY.eq(key))
                .execute();
        return new Result(outcome.status(), body, false);
    }

    private Result replay(String key) {
        var stored = db.selectFrom(IDEMPOTENCY_KEYS).where(IDEMPOTENCY_KEYS.KEY.eq(key)).fetchSingle();
        // Only committed rows are visible here, and the claim commits together with COMPLETED.
        if (!"COMPLETED".equals(stored.getProcessingStatus())) {
            throw new IllegalStateException("Idempotency key " + key + " visible but not completed");
        }
        return new Result(stored.getStatusCode(), stored.getResponseBody().data(), true);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What the business action produced: HTTP status and body, to store and return. */
    public record Outcome(int status, Object body) {}

    /** The response to send; {@code replayed} marks a stored response returned for a retry. */
    public record Result(int status, String jsonBody, boolean replayed) {

        public ResponseEntity<String> toResponseEntity() {
            var response = ResponseEntity.status(status).contentType(APPLICATION_JSON);
            if (replayed) {
                response.header("Idempotent-Replayed", "true");
            }
            return response.body(jsonBody);
        }
    }
}
