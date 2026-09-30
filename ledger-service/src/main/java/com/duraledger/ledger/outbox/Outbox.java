package com.duraledger.ledger.outbox;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static com.duraledger.ledger.jooq.Tables.OUTBOX;

/**
 * Write side of the outbox pattern: the event row commits in the same DB transaction as the ledger
 * write, so it can't be lost, and it can't be emitted for a change that rolled back.
 */
@Component
public class Outbox {

    private final DSLContext db;
    private final JsonMapper json;

    Outbox(DSLContext db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        db.insertInto(OUTBOX)
                .set(OUTBOX.AGGREGATE_TYPE, aggregateType)
                .set(OUTBOX.AGGREGATE_ID, aggregateId)
                .set(OUTBOX.EVENT_TYPE, eventType)
                .set(OUTBOX.PAYLOAD, JSONB.valueOf(json.writeValueAsString(payload)))
                .execute();
    }
}
