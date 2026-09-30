package com.duraledger.notification.activity;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Turns one ledger event into activity-feed rows. Idempotent: re-applying an event already seen
 * inserts nothing (at-least-once delivery means duplicates are normal, not an error).
 */
@Component
public class ActivityProjector {

    private static final Logger log = LoggerFactory.getLogger(ActivityProjector.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Counter applied;
    private final Counter duplicates;
    private final Counter ignored;

    ActivityProjector(JdbcClient jdbc, JsonMapper json, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.json = json;
        this.applied = meters.counter("duraledger.notifications.events", "result", "applied");
        this.duplicates = meters.counter("duraledger.notifications.events", "result", "duplicate");
        this.ignored = meters.counter("duraledger.notifications.events", "result", "ignored");
    }

    @Transactional
    public void apply(long eventId, String eventType, String payload) {
        JsonNode event = json.readTree(payload);
        int inserted = switch (eventType) {
            case "TransferCompleted" ->
                    insert(eventId, event, uuid(event, "fromAccountId"), "DEBIT", uuid(event, "toAccountId"))
                    + insert(eventId, event, uuid(event, "toAccountId"), "CREDIT", uuid(event, "fromAccountId"));
            case "DepositCompleted" -> insert(eventId, event, uuid(event, "accountId"), "CREDIT", null);
            default -> {
                // Forward compatible: ledger-service may add event types before this service knows them.
                // Throwing would nack the message and Pub/Sub would redeliver it forever.
                log.info("Ignoring event {} of unknown type {}", eventId, eventType);
                ignored.increment();
                yield -1;
            }
        };
        if (inserted > 0) {
            applied.increment();
        } else if (inserted == 0) {
            duplicates.increment();
        }
    }

    private int insert(long eventId, JsonNode event, UUID accountId, String direction, UUID counterparty) {
        return jdbc.sql("""
                        INSERT INTO notification.activity (event_id, account_id, transaction_id, event_type, direction,
                                                           amount_minor, currency, counterparty_account_id, occurred_at)
                        VALUES (:eventId, :accountId, :transactionId, :type, :direction, :amount, :currency,
                                :counterparty, :occurredAt)
                        ON CONFLICT (event_id, account_id) DO NOTHING""")
                .param("eventId", eventId)
                .param("accountId", accountId)
                .param("transactionId", uuid(event, "transactionId"))
                .param("type", event.get("type").asString())
                .param("direction", direction)
                .param("amount", event.get("amountMinor").asLong())
                .param("currency", event.get("currency").asString())
                .param("counterparty", counterparty)
                .param("occurredAt", OffsetDateTime.parse(event.get("createdAt").asString()))
                .update();
    }

    private static UUID uuid(JsonNode event, String field) {
        return UUID.fromString(event.get(field).asString());
    }
}
