package com.duraledger.ledger.outbox;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static com.duraledger.ledger.jooq.Tables.OUTBOX;

/**
 * Write side of the outbox pattern: the event row commits in the same DB transaction as the ledger
 * write, so it can't be lost, and it can't be emitted for a change that rolled back.
 * {@link OutboxRelay} publishes it.
 */
@Component
public class Outbox {

    private final DSLContext db;
    private final JsonMapper json;
    private final OutboxRelay relay;
    private final OutboxProperties properties;

    Outbox(DSLContext db, JsonMapper json, OutboxRelay relay, OutboxProperties properties) {
        this.db = db;
        this.json = json;
        this.relay = relay;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        db.insertInto(OUTBOX)
                .set(OUTBOX.AGGREGATE_TYPE, aggregateType)
                .set(OUTBOX.AGGREGATE_ID, aggregateId)
                .set(OUTBOX.EVENT_TYPE, eventType)
                .set(OUTBOX.PAYLOAD, JSONB.valueOf(json.writeValueAsString(payload)))
                .execute();

        if (properties.publishAfterCommit()) {
            // afterCommit, never before: publishing an event for a transaction that then rolls back
            // is exactly the bug the outbox exists to prevent. Failures here are swallowed by the
            // relay (the money is already committed; the sweep will publish it later).
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    relay.publishPending();
                }
            });
        }
    }
}
