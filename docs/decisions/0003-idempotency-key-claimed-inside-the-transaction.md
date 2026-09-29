# 3. Claim the idempotency key inside the money-movement transaction

- **Status:** accepted
- **Date:** 2026-09-30

## Context

A client that times out cannot tell "the transfer failed" from "it succeeded and the response was lost", so it retries. Mobile networks, load balancers and client libraries all retry on their own too. Every money-moving request therefore carries an `Idempotency-Key`, and the same key must produce the same effect exactly once, even when duplicates arrive at the same instant.

Common designs and their failure modes:
- **Check, then act** (`SELECT` the key, and if absent, do the work): two concurrent duplicates both see "absent" and both move money.
- **Claim in a separate transaction first**, then do the work in another: a crash between the two leaves the key claimed but the work undone. The key is stuck in `PENDING`, so duplicates must poll, and something must expire stale claims.
- **A distributed lock** (e.g. Redis) around the request: another moving part, and it's still separate from the database commit that actually matters.

## Decision

The key is claimed with `INSERT … ON CONFLICT DO NOTHING` **as the first statement of the same database transaction** that posts the ledger legs, and the stored response is written by that same transaction before it commits.

- The winner inserts the row. A concurrent duplicate's `INSERT` finds the winner's *uncommitted* unique-index entry and **waits** on Postgres's own lock. When the winner commits, the duplicate's insert affects 0 rows, and it reads and replays the committed response with `Idempotent-Replayed: true`.
- If the winner crashes or rolls back, its claim disappears with everything else, and the waiting duplicate claims the key and runs itself.
- `transactions.idempotency_key` is `UNIQUE`, so even a bug in this service can't make one key produce two ledger transactions.

## Consequences

- No polling loop, no expiry job and no extra infrastructure: the database lock does the waiting.
- A key can never be stuck: a claim exists only if its transaction committed.
- It relies on `READ COMMITTED` (the Postgres default), because the replaying statement must see the winner's commit.
- A duplicate holds a connection while it waits. That's acceptable at this scale; a huge burst of duplicates would need a short in-memory coalescing layer in front.
