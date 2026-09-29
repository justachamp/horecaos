-- ADR 0047's cart-to-table binding, on ADR 0019's `ordering.cart_fulfillment`.
--
-- ADR 0047 ("Extensions to existing decisions") says the cart fulfilment table
-- "gains a nullable `dinein_table_id` beside `address_id`, exactly one set per
-- mode", and V0034's header named the column and said it could not be added until
-- the table existed. V0056 created the table for a DELIVERY destination and, by
-- design, for no other mode: `ck_cart_fulfillment_is_delivery`, and address and
-- coordinate columns that were NOT NULL because a destination that cannot be
-- measured is not one ADR 0037 can price.
--
-- This widens it to the second mode and keeps the rule the ADR states: a row is
-- either a DELIVERY destination (every destination column set, no table) or a
-- DINE_IN table binding (a table, no destination column at all). The foreign key
-- V0056 already carries -- (cart_id, tenant_id, fulfillment_mode) against
-- ordering.carts -- is what stops either kind sitting on a cart of the other mode,
-- so a pickup cart still cannot acquire a doorstep or a table.
--
-- `dinein_table_id` is deliberately not a foreign key. `dinein.tables` belongs to
-- another module's schema, and ordering asks it through `TableBindingPort` rather
-- than joining it (the same boundary `customer_address_id` draws to the customer
-- schema). A table archived after the cart bound to it is refused at checkout by
-- the port's own read, which is where that fact is owned.
--
-- The binding is a guest's, made from the token a scan minted: the table is read
-- from the token, never taken from a request. What it buys is that checkout can
-- put the order on the table's bill in the same transaction that creates it, so
-- the attach cannot be lost between two calls.

ALTER TABLE ordering.cart_fulfillment
    DROP CONSTRAINT ck_cart_fulfillment_is_delivery,
    DROP CONSTRAINT ck_cart_fulfillment_coordinates;

ALTER TABLE ordering.cart_fulfillment
    ALTER COLUMN address_encrypted DROP NOT NULL,
    ALTER COLUMN latitude DROP NOT NULL,
    ALTER COLUMN longitude DROP NOT NULL,
    ADD COLUMN dinein_table_id uuid;

ALTER TABLE ordering.cart_fulfillment
    ADD CONSTRAINT ck_cart_fulfillment_row_mode CHECK (fulfillment_mode IN ('DELIVERY', 'DINE_IN')),
    ADD CONSTRAINT ck_cart_fulfillment_coordinates CHECK (
        (latitude IS NULL OR latitude BETWEEN -90 AND 90)
        AND (longitude IS NULL OR longitude BETWEEN -180 AND 180)),
    ADD CONSTRAINT ck_cart_fulfillment_one_kind CHECK (
        (fulfillment_mode = 'DELIVERY'
            AND dinein_table_id IS NULL
            AND address_encrypted IS NOT NULL
            AND latitude IS NOT NULL
            AND longitude IS NOT NULL)
        OR
        (fulfillment_mode = 'DINE_IN'
            AND dinein_table_id IS NOT NULL
            AND customer_address_id IS NULL
            AND address_encrypted IS NULL
            AND delivery_instructions_encrypted IS NULL
            AND recipient_name_encrypted IS NULL
            AND recipient_phone_encrypted IS NULL
            AND latitude IS NULL
            AND longitude IS NULL));

COMMENT ON COLUMN ordering.cart_fulfillment.dinein_table_id IS
    'ADR 0047 cart-to-table binding: the table a guest''s QR token was minted for. Set exactly when fulfillment_mode is DINE_IN. Not a foreign key: dinein.tables is another module''s, asked through TableBindingPort.';
