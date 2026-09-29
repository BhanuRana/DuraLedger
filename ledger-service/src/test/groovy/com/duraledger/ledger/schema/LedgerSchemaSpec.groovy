package com.duraledger.ledger.schema

import groovy.sql.Sql
import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import spock.lang.Shared
import spock.lang.Specification

import java.sql.SQLException

/**
 * Tries to break every ledger invariant directly in SQL and expects Postgres to refuse. Runs the real
 * Flyway migrations on a real Postgres: deferred constraint triggers and composite foreign keys are
 * exactly what an in-memory database would fake.
 */
class LedgerSchemaSpec extends Specification {

    @Shared PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
    @Shared Sql sql

    def setupSpec() {
        postgres.start()
        Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .load()
                .migrate()
        sql = Sql.newInstance(postgres.jdbcUrl, postgres.username, postgres.password, "org.postgresql.Driver")
    }

    def cleanupSpec() {
        sql?.close()
        postgres.stop()
    }

    def "migration seeds one system account per supported currency"() {
        expect:
        sql.rows("SELECT currency FROM accounts WHERE is_system_account ORDER BY currency")*.currency ==
                ["EUR", "GBP", "HKD", "USD"]
    }

    def "a deposit is two legs against the system account, and balance is derived from the entries"() {
        given:
        def alice = userAccount("HKD")

        when:
        deposit(alice, "HKD", 10_000)

        then:
        balance(alice) == 10_000
        balance(system("HKD")) == -10_000   // the outside world "owes" what came in
    }

    def "a balanced transfer commits and moves money between accounts"() {
        given:
        def alice = userAccount("USD")
        def bob = userAccount("USD")
        deposit(alice, "USD", 5_000)

        when:
        post("TRANSFER", [[alice, "USD", 1_200, "DEBIT"], [bob, "USD", 1_200, "CREDIT"]])

        then:
        balance(alice) == 3_800
        balance(bob) == 1_200
    }

    def "a transaction that does not net to zero is rejected at COMMIT, and nothing is persisted"() {
        given:
        def alice = userAccount("EUR")
        def bob = userAccount("EUR")
        def entriesBefore = entryCount()

        when: "the legs disagree by one cent"
        post("TRANSFER", [[alice, "EUR", 1_000, "DEBIT"], [bob, "EUR", 999, "CREDIT"]])

        then:
        def e = thrown(SQLException)
        e.message.contains("does not net to zero in EUR")
        entryCount() == entriesBefore
    }

    def "a single one-sided entry is rejected: money cannot appear from nowhere"() {
        when:
        post("DEPOSIT", [[userAccount("GBP"), "GBP", 500, "CREDIT"]])

        then:
        thrown(SQLException)
    }

    def "ledger entries are append-only: #operation is rejected"() {
        given:
        deposit(userAccount("HKD"), "HKD", 100)

        when:
        sql.execute(statement)

        then:
        def e = thrown(SQLException)
        e.message.contains("append-only")

        where:
        operation  | statement
        "UPDATE"   | "UPDATE ledger_entries SET amount_minor = amount_minor * 100"
        "DELETE"   | "DELETE FROM ledger_entries"
        "TRUNCATE" | "TRUNCATE ledger_entries CASCADE"
    }

    def "an entry's currency must match its account's currency"() {
        given:
        def hkdAccount = userAccount("HKD")

        when: "posting USD legs onto an HKD account"
        post("TRANSFER", [[hkdAccount, "USD", 100, "CREDIT"], [system("USD"), "USD", 100, "DEBIT"]])

        then:
        def e = thrown(SQLException)
        e.message.contains("ledger_entries_account_currency_fk")
    }

    def "amounts must be positive minor units: the sign lives in direction, not in the number"() {
        when:
        post("TRANSFER", [[userAccount("USD"), "USD", -100, "DEBIT"], [userAccount("USD"), "USD", -100, "CREDIT"]])

        then:
        thrown(SQLException)
    }

    def "a user holds at most one account per currency"() {
        given:
        def userId = UUID.randomUUID()
        sql.execute("INSERT INTO accounts (user_id, currency) VALUES (?, 'USD')", [userId])

        when:
        sql.execute("INSERT INTO accounts (user_id, currency) VALUES (?, 'USD')", [userId])

        then:
        thrown(SQLException)
    }

    def "a system account never has an owner, and a user account always does"() {
        when:
        sql.execute("INSERT INTO accounts (currency, is_system_account) VALUES ('JPY', false)")

        then:
        def e = thrown(SQLException)
        e.message.contains("accounts_owner_chk")
    }

    def "whole-ledger invariant: every currency sums to zero across all accounts, system accounts included"() {
        expect:
        sql.rows("""
            SELECT currency FROM ledger_entries GROUP BY currency
            HAVING SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor ELSE -amount_minor END) <> 0
        """).isEmpty()
        entryCount() > 0
    }

    // --- helpers -----------------------------------------------------------

    private UUID userAccount(String currency) {
        sql.firstRow("INSERT INTO accounts (user_id, currency) VALUES (?, ?) RETURNING id",
                [UUID.randomUUID(), currency]).id as UUID
    }

    private UUID system(String currency) {
        sql.firstRow("SELECT id FROM accounts WHERE is_system_account AND currency = ?", [currency]).id as UUID
    }

    private void deposit(UUID account, String currency, long amount) {
        post("DEPOSIT", [[system(currency), currency, amount, "DEBIT"], [account, currency, amount, "CREDIT"]])
    }

    /** Inserts a transaction and its legs in one DB transaction; the zero-sum check fires at commit. */
    private void post(String type, List<List> legs) {
        sql.withTransaction {
            def txId = sql.firstRow("INSERT INTO transactions (type, status) VALUES (?, 'COMPLETED') RETURNING id",
                    [type]).id
            legs.each { account, currency, amount, direction ->
                sql.execute("""
                    INSERT INTO ledger_entries (transaction_id, account_id, currency, amount_minor, direction)
                    VALUES (?, ?, ?, ?, ?)""", [txId, account, currency, amount, direction])
            }
        }
    }

    private long balance(UUID account) {
        sql.firstRow("SELECT balance_minor FROM account_balances WHERE account_id = ?", [account]).balance_minor as long
    }

    private long entryCount() {
        sql.firstRow("SELECT count(*) AS n FROM ledger_entries").n as long
    }
}
