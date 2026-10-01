package com.duraledger.ledger.outbox;

import com.duraledger.ledger.jooq.tables.records.OutboxRecord;
import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.cloud.spring.pubsub.PubSubAdmin;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.duraledger.ledger.jooq.Tables.OUTBOX;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.min;

/**
 * Publishes committed outbox rows to Pub/Sub: the "relay" half of the outbox pattern.
 *
 * <p>Delivery is <b>at-least-once</b>: rows are marked published only after Pub/Sub acknowledged
 * them, so a crash between publish and commit re-publishes the batch on the next tick. Consumers
 * dedupe on the {@code eventId} attribute (the outbox id). Exactly-once delivery across a database and
 * a broker isn't possible without a distributed transaction; at-least-once plus idempotent consumers
 * is the standard answer.
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} lets several ledger-service replicas relay concurrently: each grabs
 * a different batch instead of blocking on, or double-publishing, the same rows.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final DSLContext db;
    private final PubSubTemplate pubsub;
    private final PubSubAdmin admin;
    private final OutboxProperties properties;
    private final TransactionTemplate tx;
    private final Counter pubsubFailures;
    private final Counter databaseFailures;
    private volatile boolean running = true;

    OutboxRelay(DSLContext db, PubSubTemplate pubsub, PubSubAdmin admin, OutboxProperties properties,
                PlatformTransactionManager transactionManager, MeterRegistry meters) {
        this.db = db;
        // REQUIRES_NEW: publishPending() is also called from Outbox's afterCommit hook, where the finished
        // transaction's connection is still bound. A joining template would run inside it, and its
        // UPDATEs would only commit as a side effect of Spring resetting autocommit afterwards.
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Tagged by cause: a tick also fails when Postgres is down, and an alert saying "Pub/Sub is
        // failing" during a database outage sends on-call to the wrong system.
        this.pubsubFailures = meters.counter("duraledger.outbox.publish.failures", "cause", "pubsub");
        this.databaseFailures = meters.counter("duraledger.outbox.publish.failures", "cause", "database");
        this.pubsub = pubsub;
        this.admin = admin;
        this.properties = properties;
        // Relay health at a glance: how far behind is it, in rows and in seconds?
        meters.gauge("duraledger.outbox.pending", this, OutboxRelay::pendingCount);
        meters.gauge("duraledger.outbox.oldest.pending.seconds", this, OutboxRelay::oldestPendingAgeSeconds);
    }

    /**
     * Create and tolerate ALREADY_EXISTS, not "if missing, create": when several pods start at once, two
     * can both see the topic missing, and the loser crashed (found on the kind cluster). It's the same
     * check-then-act race the idempotency keys avoid.
     */
    @EventListener(ApplicationReadyEvent.class)
    void createTopologyIfConfigured() {
        if (!properties.createTopology()) {
            return;
        }
        try {
            admin.createTopic(properties.topic());
            log.info("Created Pub/Sub topic {}", properties.topic());
        } catch (AlreadyExistsException e) {
            log.debug("Pub/Sub topic {} already exists", properties.topic());
        }
    }

    /**
     * ContextClosedEvent fires before any bean is destroyed. Without it, ticks kept firing after the
     * Pub/Sub publisher and the connection pool were already shut down.
     */
    @EventListener(ContextClosedEvent.class)
    void stop() {
        running = false;
    }

    /**
     * The running check must sit OUTSIDE the transaction: with @Transactional on this method, Spring's
     * proxy borrows a connection before the body runs, so a tick during shutdown failed on the closed
     * pool before it could see the flag. Hence a TransactionTemplate instead of the annotation.
     */
    /** Called by the internal scheduler (local) or the {@code /internal/tasks/outbox-sweep} endpoint (Cloud Run). */
    public int publishPending() {
        if (!running) {
            return 0;
        }
        try {
            return tx.execute(status -> publishBatch());
        } catch (PublishFailedException e) {
            // Expected during a Pub/Sub outage: rows stay unpublished and the next tick retries. One WARN
            // line and a counter to alert on, instead of an ERROR stack trace 5 times a second.
            pubsubFailures.increment();
            log.warn("Outbox publish failed, will retry: {}", e.toString());
            return 0;
        } catch (RuntimeException e) {
            // Couldn't read or mark the outbox (database down): nothing was published, nothing is lost.
            databaseFailures.increment();
            log.warn("Outbox relay could not reach the database, will retry: {}", e.toString());
            return 0;
        }
    }

    /** Publishes batches until the outbox is empty or {@code maxBatches} is reached. */
    public int drain(int maxBatches) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int published = publishPending();
            total += published;
            if (published < properties.batchSize()) {
                break;
            }
        }
        return total;
    }

    private int publishBatch() {
        var batch = db.selectFrom(OUTBOX)
                .where(OUTBOX.PUBLISHED_AT.isNull())
                .orderBy(OUTBOX.ID)
                .limit(properties.batchSize())
                .forUpdate()
                .skipLocked()
                .fetch();
        if (batch.isEmpty()) {
            return 0;
        }

        var acks = batch.stream().map(this::publish).toArray(CompletableFuture[]::new);
        // Any failure throws -> the transaction rolls back -> rows stay unpublished -> retried next tick.
        try {
            CompletableFuture.allOf(acks).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new PublishFailedException(e);
        }

        db.update(OUTBOX)
                .set(OUTBOX.PUBLISHED_AT, OffsetDateTime.now())
                .where(OUTBOX.ID.in(batch.getValues(OUTBOX.ID)))
                .execute();
        return batch.size();
    }

    private CompletableFuture<String> publish(OutboxRecord event) {
        return pubsub.publish(properties.topic(), event.getPayload().data(), Map.of(
                "eventId", event.getId().toString(),
                "eventType", event.getEventType(),
                "aggregateType", event.getAggregateType(),
                "aggregateId", event.getAggregateId().toString(),
                "occurredAt", event.getCreatedAt().toString()));
    }

    private double pendingCount() {
        return db.select(count()).from(OUTBOX).where(OUTBOX.PUBLISHED_AT.isNull()).fetchSingle().value1();
    }

    private double oldestPendingAgeSeconds() {
        var oldest = db.select(min(OUTBOX.CREATED_AT)).from(OUTBOX).where(OUTBOX.PUBLISHED_AT.isNull()).fetchSingle().value1();
        return oldest == null ? 0 : java.time.Duration.between(oldest, OffsetDateTime.now()).toMillis() / 1000.0;
    }

    /** Pub/Sub refused or timed out, as opposed to the database failing around it. */
    static final class PublishFailedException extends RuntimeException {
        PublishFailedException(Throwable cause) {
            super("Publishing outbox batch failed; will retry", cause);
        }
    }
}
