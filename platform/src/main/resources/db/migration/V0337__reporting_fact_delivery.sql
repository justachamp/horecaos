-- T11 (ADR 0125, ADR 0043, ADR 0042): the delivery fact `/statistics/couriers`
-- has never had. One row per `fulfillment.courier_assignment_earnings` row,
-- written by `DayCloseService` at the same close that already writes
-- `fact_order`/`fact_order_tender`/`fact_call_hour` -- see that class for why a
-- day is written whole or not at all.
--
-- `accepted_at` is not a column `courier_assignment_earnings` carries (ADR 0042
-- never needed it: the accrual is computed from what the rate card and the
-- promise say, not from how long the courier held the bag). The close-time
-- projector joins `fulfillment.assignment_attempts` for it, so `transit_seconds`
-- here has an honest start instant rather than a guess -- see ADR 0125.
--
-- The `COURIER` scope of `reporting.agg_sla_bucket_day` needs no schema change:
-- `ck_agg_sla_scope_kind` (V0031) already allows it and has since before this
-- fact existed. This migration only adds the fact table it is now cut from.

CREATE TABLE reporting.fact_delivery (
    tenant_id uuid NOT NULL,
    courier_assignment_earning_id uuid NOT NULL,
    business_date date NOT NULL,
    boundary_version integer NOT NULL,
    metric_calculation_version integer NOT NULL,

    courier_id uuid NOT NULL,
    location_id uuid NOT NULL,
    shipment_id uuid NOT NULL,
    assignment_attempt_id uuid NOT NULL,

    distance_meters integer NOT NULL,
    distance_source varchar(24) NOT NULL,
    on_time_outcome varchar(16) NOT NULL,

    accepted_at timestamptz NOT NULL,
    delivered_at timestamptz NOT NULL,
    -- delivered_at - accepted_at, stored rather than recomputed by every reader,
    -- the same reason fact_order stores seconds_total instead of leaving every
    -- chart to subtract two timestamptz columns itself.
    transit_seconds integer NOT NULL,

    built_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_fact_delivery PRIMARY KEY (tenant_id, courier_assignment_earning_id),
    CONSTRAINT ck_fact_delivery_distance CHECK (distance_meters >= 0),
    CONSTRAINT ck_fact_delivery_distance_source CHECK (distance_source IN (
        'ROUTING', 'HAVERSINE_FACTORED', 'MANUAL')),
    CONSTRAINT ck_fact_delivery_on_time CHECK (on_time_outcome IN (
        'ON_TIME', 'LATE', 'LATE_EXCUSED', 'UNKNOWN')),
    CONSTRAINT ck_fact_delivery_transit CHECK (transit_seconds >= 0),
    CONSTRAINT ck_fact_delivery_order_of_events CHECK (delivered_at >= accepted_at)
);

COMMENT ON TABLE reporting.fact_delivery IS
    'ADR 0125/T11. Derived and rebuildable from fulfillment.courier_assignment_earnings + assignment_attempts, exactly as every other reporting.fact_* table is derived from its own module -- see DayCloseService. Never joined against a courier''s protected name: 7.4/7.4a resolve display through P19''s reveal, keyed on courier_id, never through this table.';
COMMENT ON COLUMN reporting.fact_delivery.courier_assignment_earning_id IS
    'The natural key: one delivery accrues exactly once (ux_earning_attempt, V0040), so one earning is exactly one delivery fact. Not the shipment id -- a cancelled-then-rebooked shipment could in principle carry two earnings across two plans, though today one shipment has one plan.';

-- The leaderboard's own grouping: every delivery for a courier across a range.
CREATE INDEX ix_fact_delivery_courier ON reporting.fact_delivery
    (tenant_id, courier_id, business_date);
-- The branch cut, symmetric with agg_branch_day's own index.
CREATE INDEX ix_fact_delivery_location ON reporting.fact_delivery
    (tenant_id, location_id, business_date);

GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.fact_delivery TO horecaos_application;
