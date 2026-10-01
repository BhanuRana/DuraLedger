package com.duraledger.notification.activity;

import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.spring.pubsub.PubSubAdmin;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import com.google.cloud.spring.pubsub.support.BasicAcknowledgeablePubsubMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Streaming-pull subscriber on ledger.events. Acks only after the projection committed; on failure it
 * nacks, so Pub/Sub redelivers.
 *
 * <p>duraledger.events.delivery=pull (default: local, always-on hosts). On Cloud Run nothing may hold
 * a connection open between requests, so {@link PushController} receives events instead.
 */
@Component
@ConditionalOnProperty(name = "duraledger.events.delivery", havingValue = "pull", matchIfMissing = true)
class LedgerEventSubscriber {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventSubscriber.class);

    private final PubSubTemplate pubsub;
    private final PubSubAdmin admin;
    private final EventsProperties properties;
    private final ActivityProjector projector;
    private volatile Subscriber subscriber;

    LedgerEventSubscriber(PubSubTemplate pubsub, PubSubAdmin admin, EventsProperties properties, ActivityProjector projector) {
        this.pubsub = pubsub;
        this.admin = admin;
        this.properties = properties;
        this.projector = projector;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        ensureTopology();
        subscriber = pubsub.subscribe(properties.subscription(), this::handle);
        log.info("Subscribed to {}", properties.subscription());
    }

    /** Either service may start first, so each makes sure the topic exists. */
    void ensureTopology() {
        if (!properties.createTopology()) {
            return;
        }
        if (admin.getTopic(properties.topic()) == null) {
            admin.createTopic(properties.topic());
        }
        if (admin.getSubscription(properties.subscription()) == null) {
            admin.createSubscription(properties.subscription(), properties.topic());
        }
    }

    /** Stop pulling before the connection pool closes (the same lesson as ledger-service's relay). */
    @EventListener(ContextClosedEvent.class)
    void stop() {
        if (subscriber != null) {
            subscriber.stopAsync().awaitTerminated();
        }
    }

    private void handle(BasicAcknowledgeablePubsubMessage message) {
        var attributes = message.getPubsubMessage().getAttributesMap();
        try {
            projector.apply(Long.parseLong(attributes.get("eventId")), attributes.get("eventType"),
                    message.getPubsubMessage().getData().toStringUtf8());
            message.ack();
        } catch (RuntimeException e) {
            log.warn("Failed to apply event {}, will be redelivered", attributes.get("eventId"), e);
            message.nack();
        }
    }
}
