package com.duraledger.ledger.transfer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Static rates: units of each currency per 1 USD. A deliberate simplification with no live market
 * data; every conversion records the rate it used on the transaction row, which is what matters for
 * auditing it later.
 */
@ConfigurationProperties("duraledger.fx")
public record FxProperties(Map<String, BigDecimal> unitsPerUsd) {}
