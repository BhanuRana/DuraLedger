package com.duraledger.ledger.ledger;

import com.duraledger.ledger.web.LedgerRejection;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.duraledger.ledger.jooq.Tables.LEDGER_ENTRIES;
import static com.duraledger.ledger.jooq.Tables.TRANSACTIONS;

@RestController
@RequestMapping("/transactions")
class TransactionController {

    private final DSLContext db;

    TransactionController(DSLContext db) {
        this.db = db;
    }

    @GetMapping("/{id}")
    TransactionView get(@PathVariable UUID id) {
        var tx = db.selectFrom(TRANSACTIONS).where(TRANSACTIONS.ID.eq(id)).fetchOptional()
                .orElseThrow(() -> new LedgerRejection(HttpStatus.NOT_FOUND, "transaction-not-found",
                        "Transaction " + id + " does not exist"));
        var entries = db.selectFrom(LEDGER_ENTRIES)
                .where(LEDGER_ENTRIES.TRANSACTION_ID.eq(id))
                .orderBy(LEDGER_ENTRIES.DIRECTION.desc(), LEDGER_ENTRIES.CURRENCY) // DEBITs first
                .fetch(e -> new EntryView(e.getAccountId(), e.getDirection(), e.getAmountMinor(), e.getCurrency()));
        return new TransactionView(tx.getId(), tx.getType(), tx.getStatus(), tx.getCreatedAt(), entries);
    }

    record TransactionView(UUID id, String type, String status, OffsetDateTime createdAt, List<EntryView> entries) {}

    record EntryView(UUID accountId, String direction, long amountMinor, String currency) {}
}
