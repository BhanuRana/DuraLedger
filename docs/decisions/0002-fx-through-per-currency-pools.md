# 2. Currency conversion posts four legs through per-currency FX pools

- **Status:** accepted
- **Date:** 2026-09-29

## Context

The ledger requires every transaction to net to zero **per currency** ([ADR 0001](0001-enforce-ledger-invariants-in-postgres.md)). The obvious way to post a conversion of 100.00 USD into 780.00 HKD is two legs: debit the user's USD wallet 10,000, credit their HKD wallet 78,000. That posting has one USD leg and one HKD leg, so neither currency nets to zero, and the trigger rejects it. Writing a spec for it made this concrete before any Java existed.

Relaxing the rule ("net to zero after converting to a base currency") would make the core invariant depend on exchange rates, which change and are rounded. The whole-ledger sum per currency would stop being exactly zero, and reconciliation would need tolerances.

## Decision

The house holds an **FX pool** account in each currency: its inventory for conversions. A conversion is four legs:

| Account | Direction | Amount |
|---|---|---:|
| User · USD | DEBIT | 10,000 |
| FX pool · USD | CREDIT | 10,000 |
| FX pool · HKD | DEBIT | 78,000 |
| User · HKD | CREDIT | 78,000 |

Each currency nets to zero on its own. The single `is_system_account` flag becomes an account `kind`: `USER`, `EXTERNAL_CLEARING` (the outside world, for deposits and withdrawals) and `FX_POOL` (V2 migration).

## Consequences

- The zero-sum invariant stays exact in every currency, with no rates and no tolerances.
- The FX pools' balances show the house's net currency position at any moment, which is real information a treasury team would want.
- Rounding must never create money: whoever owns the sub-cent remainder must be decided when conversions are built.
- A spec asserts the naive two-leg posting is rejected, so the design can't silently regress.
