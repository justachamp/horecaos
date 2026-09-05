-- ADR 0064 + ADR 0043: call facts through the same day-close pipeline every
-- other fact uses. No parallel stats store — this table is written by
-- DayCloseService.close() exactly like fact_order/fact_order_line/fact_refund,
-- from a new cross-schema read (JdbcReportingStore.readSourceCallEvents)
-- against voice.call_events, the same way readSourceOrders already reads
-- ordering.orders.
--
-- One new thing ADR 0043's existing physical model does not have: an hour
-- grain. fact_order/agg_branch_day are day-grain only, and the ADR 0064 exit
-- criteria explicitly wants "the operator roster of that moment" and
-- per-operator, per-location, per-hour figures, none of which a day bucket can
-- answer. This table adds the hour dimension for calls only, rather than
-- widening every existing fact table to a grain most of them do not need.
--
-- operator_principal_id is NOT NULL with a sentinel rather than nullable,
-- because a nullable column cannot sit inside a PRIMARY KEY and a UNIQUE
-- constraint's NULLS NOT DISTINCT still reads awkwardly for a dimension this
-- is not a home for (unlike agg_branch_day's legal_entity_id, which is
-- genuinely optional business data). '(unassigned)' is not a valid Keycloak
-- subject, so it can never collide with a real operator.
CREATE TABLE reporting.fact_call_hour (
    tenant_id uuid NOT NULL,
    business_date date NOT NULL,
    hour_of_day smallint NOT NULL,
    location_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    operator_principal_id varchar(255) NOT NULL DEFAULT '(unassigned)',

    boundary_version integer NOT NULL,
    metric_calculation_version integer NOT NULL,

    offered_count integer NOT NULL DEFAULT 0,
    answered_count integer NOT NULL DEFAULT 0,
    missed_count integer NOT NULL DEFAULT 0,
    transferred_count integer NOT NULL DEFAULT 0,

    -- Whole seconds, summed. A ratio (average handle time) is a report-time
    -- division of this by answered_count, not a stored column that could
    -- disagree with the two numbers it comes from.
    talk_duration_seconds bigint NOT NULL DEFAULT 0,

    built_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, location_id, business_date, hour_of_day, operator_principal_id),
    CONSTRAINT ck_fact_call_hour_range CHECK (hour_of_day BETWEEN 0 AND 23),
    CONSTRAINT ck_fact_call_hour_counts CHECK (
        offered_count >= 0 AND answered_count >= 0 AND missed_count >= 0
        AND transferred_count >= 0 AND talk_duration_seconds >= 0)
    -- No answered <= offered check here, deliberately: those two counts do not
    -- share a row. An OFFERED event has no operator yet (it lands in the
    -- '(unassigned)' bucket), while its later ANSWERED event may carry a real
    -- operator once the screen-pop acknowledgment resolves one — so the
    -- invariant that actually holds is one row's ANSWERED total across the
    -- whole location-hour never exceeding that same slice's OFFERED total,
    -- which is a rollup across rows, not a per-row constraint the database can
    -- express as a CHECK.
);

CREATE INDEX ix_fact_call_hour_location_day ON reporting.fact_call_hour (tenant_id, location_id, business_date);

GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.fact_call_hour TO horecaos_application;
-- horecaos_reporting_read already holds SELECT on every present and future
-- reporting table via V0031's `ALTER DEFAULT PRIVILEGES IN SCHEMA reporting`.
