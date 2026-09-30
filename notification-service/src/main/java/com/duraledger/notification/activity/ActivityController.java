package com.duraledger.notification.activity;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Recent activity for an account: eventually consistent with the ledger, typically under a second behind. */
@RestController
class ActivityController {

    private final JdbcClient jdbc;

    ActivityController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/accounts/{accountId}/activity")
    List<ActivityItem> activity(@PathVariable UUID accountId,
                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return jdbc.sql("""
                        SELECT event_id, transaction_id, event_type, direction, amount_minor, currency,
                               counterparty_account_id, occurred_at
                        FROM notification.activity
                        WHERE account_id = :accountId
                        ORDER BY occurred_at DESC, event_id DESC
                        LIMIT :limit""")
                .param("accountId", accountId)
                .param("limit", limit)
                .query((rs, n) -> new ActivityItem(
                        rs.getLong("event_id"),
                        rs.getObject("transaction_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("direction"),
                        rs.getLong("amount_minor"),
                        rs.getString("currency"),
                        rs.getObject("counterparty_account_id", UUID.class),
                        rs.getObject("occurred_at", OffsetDateTime.class)))
                .list();
    }

    record ActivityItem(long eventId, UUID transactionId, String type, String direction, long amountMinor,
                        String currency, UUID counterpartyAccountId, OffsetDateTime occurredAt) {}
}
