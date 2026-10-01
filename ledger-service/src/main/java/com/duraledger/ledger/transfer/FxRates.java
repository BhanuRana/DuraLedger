package com.duraledger.ledger.transfer;

import com.duraledger.ledger.web.LedgerRejection;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cross rates through USD, in BigDecimal end to end: money never touches a double.
 * The converted amount is rounded DOWN to a whole minor unit: the house keeps the sub-cent remainder,
 * so the FX pool can never be drained by rounding (real FX desks do the same, via the spread).
 * Assumes every supported currency has 2 decimals (true for HKD/USD/EUR/GBP; JPY would need exponents).
 */
@Component
class FxRates {

    private final FxProperties properties;

    FxRates(FxProperties properties) {
        this.properties = properties;
    }

    BigDecimal rate(String from, String to) {
        return unitsPerUsd(to).divide(unitsPerUsd(from), 10, RoundingMode.HALF_EVEN);
    }

    long convert(long amountMinor, String from, String to) {
        return BigDecimal.valueOf(amountMinor)
                .multiply(unitsPerUsd(to))
                .divide(unitsPerUsd(from), 0, RoundingMode.DOWN)
                .longValueExact();
    }

    private BigDecimal unitsPerUsd(String currency) {
        var rate = properties.unitsPerUsd().get(currency);
        if (rate == null) {
            throw LedgerRejection.unprocessable("unsupported-currency", "No FX rate for " + currency);
        }
        return rate;
    }
}
