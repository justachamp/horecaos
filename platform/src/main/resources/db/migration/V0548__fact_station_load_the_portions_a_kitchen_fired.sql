-- ADR 0163: reporting.fact_station_load, the portions each kitchen station fired, by business date and
-- operating hour.
--
-- A cook is planned per station, and nothing in `reporting` could say what a station cooked: fact_order counts
-- ORDERS (one order is anything from one portion to thirty), fact_order_line has the right unit (portions) but
-- the wrong axis (the catalogue, not the kitchen: one category can route to three stations, a combo splits
-- across them) and no station column, and both bucket by the order's own hour and keep completed orders only.
-- The instant that matters to a cook is when cooking starts: kitchen.tickets.released_at. This fact is
-- written by the day close from the kitchen's tickets, exactly as fact_order_tender (V0304, ADR 0115) was
-- added to it from payments: the close job is the one reader of other modules' tables, and it writes only
-- into `reporting`.
--
-- What counts, exactly (Decision 1): a ticket item counts when its ticket has released_at set and
-- (status <> 'CANCELLED' OR started_at IS NOT NULL). Work a cook began keeps its portions even if the line
-- was then struck or its ticket voided, because the labour was spent; a line struck before anyone touched it
-- does not, which is what an amendment does to the line it replaces (the replacement counts once, in the
-- hour of the ticket's own released_at); a ticket voided before it fired has no released_at and counts
-- nothing. This is not the ceiling read in JdbcKitchenStore (still to be cooked in a window): it asks what was
-- cooked.
--
-- The station's code and role are stored as they were that day, so renaming or archiving a station never
-- rewrites last quarter's planning. No foreign keys, as with every reporting fact: station_id, location_id and
-- the station's code and role are derived copies (like fact_order_line.category_id).
--
-- Not partitioned: the grain is date x station x hour, tens of rows a day per branch, and
-- reporting.ensure_fact_partition refuses tables it does not manage by name (the shape V0367 chose for
-- fact_forecast for the same reason).
--
-- PII (ADR 0029): none. The fact is station and hour counts. No staff name, no staff identifier, no ticket or
-- order identifier is stored.

CREATE TABLE reporting.fact_station_load (
    tenant_id uuid NOT NULL,
    business_date date NOT NULL,
    location_id uuid NOT NULL,
    station_id uuid NOT NULL,
    station_code varchar(32) NOT NULL,
    station_role varchar(16) NOT NULL,
    -- 0 is the business day's own start (reporting.domain.BusinessDayBoundary), the same axis the branch
    -- forecast uses, not the wall clock.
    operating_hour smallint NOT NULL,
    portions numeric(12, 3) NOT NULL,
    tickets integer NOT NULL,
    boundary_version integer NOT NULL,
    metric_calculation_version integer NOT NULL,

    PRIMARY KEY (tenant_id, business_date, location_id, station_id, operating_hour),
    CONSTRAINT ck_station_load_hour CHECK (operating_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_station_load_portions CHECK (portions >= 0),
    CONSTRAINT ck_station_load_tickets CHECK (tickets >= 0)
);

-- The plan's read: one branch, one weekday's history, newest date first.
CREATE INDEX ix_station_load_branch_date
    ON reporting.fact_station_load (tenant_id, location_id, business_date DESC);

COMMENT ON TABLE reporting.fact_station_load IS
    'ADR 0163. Portions fired per business date, location, station and operating hour, written by the day close from kitchen.tickets (released_at) and rebuilt for one date by DayCloseService.rebuildStationLoad. A recut compares and never writes it.';
COMMENT ON COLUMN reporting.fact_station_load.operating_hour IS
    '0 = the business day''s own start (BusinessDayBoundary), not the wall clock. The branch forecast uses the same axis.';
COMMENT ON COLUMN reporting.fact_station_load.station_code IS
    'The station''s code as it was that day. Renaming or archiving a station never rewrites a closed day.';

GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.fact_station_load TO horecaos_application;
GRANT SELECT ON reporting.fact_station_load TO horecaos_reporting_read;
