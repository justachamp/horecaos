-- Wave P27 (7.2a): the commercial order log («Заказы») has nothing to print
-- but eight characters of order_id — a UUID fragment no operator can read
-- back over the phone or match against a printed receipt.
-- ordering.orders.public_order_number (V0022) is exactly that number
-- already: the short, per-location-per-day counter every receipt and kitchen
-- ticket already carries. The close job simply never copied it onto the
-- fact, the same gap V0274 found for operator_principal_id.
ALTER TABLE reporting.fact_order
    ADD COLUMN public_order_number varchar(24);

COMMENT ON COLUMN reporting.fact_order.public_order_number IS
    'ADR 0043 (wave P27). Snapshotted from ordering.orders.public_order_number at close time — the short number a receipt and a kitchen ticket both print. Null on a row closed before this migration and never recut.';

-- No GRANT block: fact_order already has one from V0031, and a GRANT is
-- per-table, not per-column (see V0274's own closing comment).
