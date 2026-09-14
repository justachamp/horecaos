-- T11 7.4b: the delivery-sum-by-tariff audit. delivery_fee_resolutions (V0025)
-- already holds tariff, tariff version, zone, band and final fee, but its only
-- indexes are quote- and location-keyed (ix_quote_plan lives on the quote
-- table; delivery_fee_resolutions itself has none beyond its primary key) --
-- a range query grouped by tariff has to scan. This index is the one the
-- audit actually runs: a business-date-ish range plus tariff_id.
--
-- created_at rather than a business_date column: delivery_fee_resolutions has
-- no business_date of its own (it is resolved once, at checkout, and never
-- revisited), and created_at is already the column the reconciliation window
-- is a range over.

CREATE INDEX ix_fee_resolution_tariff_range ON fulfillment.delivery_fee_resolutions
    (tenant_id, created_at, tariff_id)
    WHERE tariff_id IS NOT NULL;
