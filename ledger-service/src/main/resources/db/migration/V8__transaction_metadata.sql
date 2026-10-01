-- Context a transaction needs to be audited later, stored with it. First user: FX conversions record
-- the rate they used, because rates change and "what rate did this customer get?" must stay answerable
-- from the ledger alone.
ALTER TABLE transactions ADD COLUMN metadata jsonb NOT NULL DEFAULT '{}'::jsonb;
