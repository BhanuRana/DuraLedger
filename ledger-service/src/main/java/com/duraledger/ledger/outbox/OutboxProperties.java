package com.duraledger.ledger.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param topic          Pub/Sub topic ledger events are published to
 * @param batchSize      max outbox rows published per relay tick
 * @param pollInterval   delay between relay ticks
 * @param createTopology create the topic at startup if missing (local/emulator only; elsewhere it's infrastructure)
 * @param publishAfterCommit also publish right after each money movement commits. On Cloud Run there's
 *                           no always-on poller, so this keeps events near-real-time; the scheduled
 *                           sweep stays as the safety net for a crash between commit and publish.
 */
@ConfigurationProperties("duraledger.outbox")
public record OutboxProperties(
        @DefaultValue("ledger.events") String topic,
        @DefaultValue("100") int batchSize,
        @DefaultValue("200ms") Duration pollInterval,
        @DefaultValue("false") boolean createTopology,
        @DefaultValue("false") boolean publishAfterCommit) {}
