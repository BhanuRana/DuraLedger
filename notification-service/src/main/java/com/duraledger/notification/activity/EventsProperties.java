package com.duraledger.notification.activity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param topic          ledger-service's event topic
 * @param subscription   this service's subscription on it
 * @param createTopology create the topic and subscription at startup if missing (local/emulator only)
 */
@ConfigurationProperties("duraledger.events")
public record EventsProperties(
        String topic,
        String subscription,
        @DefaultValue("false") boolean createTopology) {}
