-- w6-reporting-facts, batch 11 (7.4b, ADR 0023/0125): the delivery-sum-by-
-- tariff audit's own closed fact. `readTariffAudit` read
-- `fulfillment.delivery_fee_resolutions` joined through
-- `quote -> order -> shipment` live, on every `GET .../tariff-audit`
-- request -- the exact pattern ADR 0023's read-only-projection invariant
-- forbids for a report. One row per resolution that named a tariff, written
-- at close time by `DayCloseService`, right beside `fact_delivery`'s own
-- producer -- see that class for why a day is written whole or not at all.
--
-- `resolution_id` is the natural key. Unlike `fact_external_delivery_cost`
-- beside it, this fact never goes stale relative to its own source:
-- `delivery_fee_resolutions` is "resolved once, at checkout, and never
-- revisited" (V0338's own comment), so whatever this fact captures at close
-- time is the resolution's final, permanent answer.
--
-- `order_id`/`shipment_id` are carried as opaque ids only -- exactly what
-- `readTariffAudit`'s own join needed the courier for, and nothing this
-- fact's readers use them to look up further; ADR 0029 already forbids any
-- name reaching a reporting fact.

CREATE TABLE reporting.fact_delivery_fee_resolution (
    tenant_id uuid NOT NULL,
    resolution_id uuid NOT NULL,
    business_date date NOT NULL,
    boundary_version integer NOT NULL,
    metric_calculation_version integer NOT NULL,

    location_id uuid NOT NULL,
    tariff_id uuid NOT NULL,
    tariff_version integer NOT NULL,
    zone_id uuid,
    -- The tariff's tier/band within the resolution -- delivery_fee_resolutions'
    -- own column name (V0025).
    band_sequence integer,
    courier_id uuid,

    order_id uuid NOT NULL,
    shipment_id uuid NOT NULL,

    final_fee_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    resolved_at timestamptz NOT NULL,

    built_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_fact_delivery_fee_resolution PRIMARY KEY (tenant_id, resolution_id),
    CONSTRAINT ck_fact_fee_resolution_fee CHECK (final_fee_minor >= 0),
    CONSTRAINT ck_fact_fee_resolution_currency CHECK (currency ~ '^[A-Z]{3}$')
);

COMMENT ON TABLE reporting.fact_delivery_fee_resolution IS
    'ADR 0023/0125, w6-reporting-facts (7.4b). Derived and rebuildable from fulfillment.delivery_fee_resolutions joined through ordering.orders and fulfillment.shipments -- see DayCloseService. Never joined against a courier''s protected name, the same rule fact_delivery states for itself.';

-- The audit's own grouping: a tariff-and-date range, mirroring V0338's index
-- on the live table it replaces.
CREATE INDEX ix_fact_fee_resolution_tariff ON reporting.fact_delivery_fee_resolution
    (tenant_id, business_date, tariff_id);
-- The report's own location filter.
CREATE INDEX ix_fact_fee_resolution_location ON reporting.fact_delivery_fee_resolution
    (tenant_id, location_id, business_date);

GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.fact_delivery_fee_resolution TO horecaos_application;
