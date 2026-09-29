-- Version column for optimistic concurrency control on debits.
--
-- Under optimistic locking a debit reads the account without a lock, then commits only if
--   UPDATE accounts SET version = version + 1 WHERE id = ? AND version = <version read earlier>
-- updates one row. 0 rows = someone else debited this account since we read it: roll back, retry.
-- The version is bumped under BOTH locking modes, so pessimistic and optimistic writers stay safe
-- side by side (e.g. during a rolling deploy that switches modes).
ALTER TABLE accounts ADD COLUMN version bigint NOT NULL DEFAULT 0;
