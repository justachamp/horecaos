-- ADR 0041: kitchen station throughput ceilings (frontend-information-architecture.md
-- §2.6, "Capacity & buffer settings").
--
-- ADR 0041 named this table and deliberately did not create it: "Configuration no
-- code reads is worse than no configuration: an operator would set a ceiling that
-- silently does nothing." That objection was aimed at one specific consumer —
-- `release_at = target_ready_at - prep_estimate - station_queue_offset`, the
-- scheduler shift the ADR sketches — which this migration still does not build.
-- `KitchenTicketService.decideRelease` is unchanged by this file.
--
-- What changes the calculus is the screen this migration serves. A branch manager
-- reading "the grill does 40 plates an hour" and comparing it, by eye, against
-- what the board is actually queueing is itself a real consumer — the "cook-count
-- planning" half of §2.6's own description, done by a human rather than a
-- scheduler. That is a materially different claim from a ceiling nobody reads at
-- all, and it is the honest scope this wave ships: the ceiling is configurable and
-- listable. Feeding it into `release_at`, and computing the other half of §2.6 —
-- an actual "cook headcount output" — stay not built; see the wave's final report
-- for why the second one is a product policy (how many portions one cook produces
-- an hour, and what forecast volume to plan against) rather than a schema gap.
--
-- One ceiling per station, per weekday, per local time-of-day window. Weekday
-- rather than a date: a throughput ceiling describes a station's plant and staffing
-- pattern, which repeats weekly, not a one-off event — a one-off closure or
-- surge is `tenant.location_service_state` (V0020) and ADR 0041's kitchen buffer,
-- not a second calendar here. `weekday` is `java.time.DayOfWeek#getValue()`,
-- ISO-8601 1=Monday..7=Sunday, matching the convention this codebase already uses
-- for delivery-tariff peak windows (V0025).
--
-- Overlap between two windows for the same station and weekday is rejected by the
-- service layer before the insert, not by a database exclusion constraint. A GiST
-- exclusion over a `time`-typed window would need a custom range type — PostgreSQL
-- ships no built-in `timerange` — and building one is disproportionate machinery
-- for a screen a manager edits occasionally between shifts, unlike V0034's
-- reservation hold, which two hosts can genuinely race on the same second. The
-- plain UNIQUE constraint below still catches the exact-duplicate case at the
-- database, which is the one a retried request could otherwise double-insert.

CREATE TABLE kitchen.station_capacity (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    station_id uuid NOT NULL,

    -- ISO-8601: 1 = Monday .. 7 = Sunday.
    weekday smallint NOT NULL,
    window_start time NOT NULL,
    window_end time NOT NULL,

    portions_per_hour integer NOT NULL,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_station_capacity_weekday CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT ck_station_capacity_window CHECK (window_end > window_start),
    CONSTRAINT ck_station_capacity_portions CHECK (portions_per_hour > 0 AND portions_per_hour <= 100000),
    CONSTRAINT ck_station_capacity_version CHECK (version >= 1),
    CONSTRAINT fk_station_capacity_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    -- `uq_station_identity` (V0030) is exactly (id, tenant_id, location_id); this
    -- is the same shape `kitchen.location_routing_rules.fk_location_rule_station`
    -- already uses to keep a rule from ever naming another branch's station.
    CONSTRAINT fk_station_capacity_station FOREIGN KEY (station_id, tenant_id, location_id)
        REFERENCES kitchen.stations (id, tenant_id, location_id),
    CONSTRAINT uq_station_capacity_window UNIQUE (tenant_id, station_id, weekday, window_start, window_end)
);

COMMENT ON TABLE kitchen.station_capacity IS
    'ADR 0041, frontend-information-architecture.md 2.6. A station''s throughput ceiling for one weekday and one local time window. Read today only by the operations settings screen; release scheduling does not consume it yet.';
COMMENT ON COLUMN kitchen.station_capacity.weekday IS
    'ISO-8601: 1 = Monday .. 7 = Sunday (java.time.DayOfWeek#getValue()).';
COMMENT ON COLUMN kitchen.station_capacity.portions_per_hour IS
    'The ceiling a manager sets by eye, not a measured rate. Nothing in this release derives it from an ingredient or a recipe.';

CREATE INDEX ix_station_capacity_station ON kitchen.station_capacity (tenant_id, station_id, weekday);

GRANT SELECT, INSERT ON kitchen.station_capacity TO horecaos_application;
