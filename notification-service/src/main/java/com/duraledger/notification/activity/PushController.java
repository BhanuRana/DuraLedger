package com.duraledger.notification.activity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * duraledger.events.delivery=push (Cloud Run): Pub/Sub POSTs each message here, waking the service
 * from zero. The status code IS the ack: 2xx = done, anything else = Pub/Sub redelivers with
 * backoff (and eventually dead-letters). Same idempotent projector as pull mode.
 */
@RestController
@ConditionalOnProperty(name = "duraledger.events.delivery", havingValue = "push")
class PushController {

    private static final Logger log = LoggerFactory.getLogger(PushController.class);

    private final InvokerVerifier invokers;
    private final ActivityProjector projector;

    PushController(InvokerVerifier invokers, ActivityProjector projector) {
        this.invokers = invokers;
        this.projector = projector;
    }

    @PostMapping("/pubsub/push")
    ResponseEntity<Void> receive(@RequestHeader(name = "Authorization", required = false) String auth,
                                 @RequestBody PushEnvelope envelope) {
        if (!invokers.isAllowed(auth)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        var message = envelope.message();
        var attributes = message.attributes() == null ? Map.<String, String>of() : message.attributes();
        if (!attributes.containsKey("eventId")) {
            // Retrying can never fix a message without an id - ack it so it doesn't redeliver forever.
            log.warn("Dropping message {} without eventId", message.messageId());
            return ResponseEntity.noContent().build();
        }
        var payload = new String(Base64.getDecoder().decode(message.data()), StandardCharsets.UTF_8);
        projector.apply(Long.parseLong(attributes.get("eventId")), attributes.get("eventType"), payload);
        return ResponseEntity.noContent().build(); // ack; exceptions above become 500 -> redelivery
    }

    /** Pub/Sub push wire format: {"message": {"attributes", "data" (base64), "messageId"}, "subscription"} */
    record PushEnvelope(Message message, String subscription) {
        record Message(Map<String, String> attributes, String data, String messageId) {}
    }

    @Configuration
    @ConditionalOnProperty(name = "duraledger.events.delivery", havingValue = "push")
    static class VerifierConfig {

        @Bean
        InvokerVerifier invokerVerifier(@Value("${duraledger.events.push-audience}") String audience,
                                        @Value("${duraledger.events.push-invokers}") Set<String> allowedInvokers) {
            return new GoogleOidcInvokerVerifier(audience, allowedInvokers);
        }
    }
}
