-- ADR 0137 (Accepted 2026-10-01): decimal portions. The structural fork the record
-- names -- widen the shared quantity column rather than add a parallel one -- is
-- taken here.
--
-- A splittable variant is ordered by the portion (0.5 of a plov), so every column
-- that carries an ordered quantity from the cart to the report becomes
-- numeric(10,3): the same width the inventory reservation line already uses for
-- the same fact (V0019, numeric(14,3) there for stock). The chain is:
--
--   ordering.cart_lines  ->  pricing.quote_lines  ->  ordering.order_lines
--     ->  kitchen.ticket_items  ->  reporting.fact_order_line
--     ->  reporting.classification_result.quantity_total (a sum of the above)
--
-- Widening one and not the next is how a half portion becomes a whole one
-- somewhere in the middle: a ticket that says 1 for an order that says 0.5, a
-- report that counts 1 sold for 0.5 cooked. They are all in this one migration.
--
-- What is deliberately NOT widened:
--
--   ordering.order_line_modifiers.quantity -- how many times an option was chosen,
--   which is a count of selections, not an amount of food.
--
--   inventory.* -- already numeric(14,3), and ADR 0137 leaves fractional stock
--   reservation to whichever record next touches the inventory ledger.
--
-- Every existing value is an integer and is representable exactly, so no row
-- changes meaning and no backfill is needed; ALTER COLUMN TYPE rewrites the table
-- with the same numbers. The rewrite takes an ACCESS EXCLUSIVE lock, which is why
-- lock_timeout is set below: if a long transaction is holding any of these tables
-- the migration fails fast and is rerun, rather than queueing behind it and
-- blocking every order write behind the queue.
--
-- The CHECK constraints (quantity > 0 on every one of them, and quantity <= 999 on
-- the cart) are expressions over the column and survive the type change as they
-- are: a fraction is now admitted, zero and negative still are not. The cart keeps
-- its upper bound. Whether a given variant may be ordered by the portion at all is
-- decided at cart time against its published physical attributes, not by the
-- column -- widening the type does not by itself invite a fractional can of soda.

SET LOCAL lock_timeout = '10s';

ALTER TABLE ordering.cart_lines
    ALTER COLUMN quantity TYPE numeric(10, 3);

ALTER TABLE pricing.quote_lines
    ALTER COLUMN quantity TYPE numeric(10, 3);

ALTER TABLE ordering.order_lines
    ALTER COLUMN quantity TYPE numeric(10, 3);

ALTER TABLE kitchen.ticket_items
    ALTER COLUMN quantity TYPE numeric(10, 3);

-- Partitioned by business_date: the type change is applied to every partition.
ALTER TABLE reporting.fact_order_line
    ALTER COLUMN quantity TYPE numeric(10, 3);

-- A sum of fact_order_line.quantity over a window, so wider than one line.
ALTER TABLE reporting.classification_result
    ALTER COLUMN quantity_total TYPE numeric(14, 3);

COMMENT ON COLUMN ordering.order_lines.quantity IS
    'ADR 0137. Units ordered, numeric(10,3): a whole number for every variant not sold by the portion, a multiple of the variant''s published portion_size for one that is. Was integer until V0449.';
COMMENT ON COLUMN ordering.cart_lines.quantity IS
    'ADR 0137. Units in the basket, numeric(10,3); validated against the variant''s published physical attributes when the line is put, not by the type.';
