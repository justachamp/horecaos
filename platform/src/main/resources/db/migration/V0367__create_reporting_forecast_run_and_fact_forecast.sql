-- Wave W02 (7.8/7.8a): ADR 0043's own "Forecasting" section sketches
-- `reporting.forecast_run` and `reporting.fact_forecast` and neither was ever
-- built -- the owner's 2026-09-05 decision shipped `demand-history`'s honest
-- historical average instead and left the model for later. This is that
-- later: a seasonal-naive model (day-of-week x hour-of-day, trailing window,
-- no holiday factor -- see below) with a confidence interval, run nightly,
-- and reconciled against what actually happened once the business day closes.
--
-- Deliberately smaller than the ADR's own sketch in two ways:
--
-- 1. No `holiday_calendar_version` or `reporting.holidays`/`calendar_version`.
--    That is ADR 0043's own "holiday factor derived from prior occurrences of
--    the same named holiday, bounded by a floor and a ceiling" -- a materially
--    bigger modelling item the wave brief explicitly defers. What this wave
--    gives the model instead is a same-day exclude-or-weight switch keyed off
--    the already-built `tenant.public_holidays` (ADR 0090), applied to the
--    sample the average is drawn from -- see `readDemandHistory`'s
--    `holidayMode` parameter, not a schema concern.
-- 2. `forecast_run`/`fact_forecast` are not partitioned. `fact_order` and
--    `fact_order_line` are one row per order/line -- production volume;
--    `fact_forecast` is one row per (run, business date, hour[, category or
--    variant]) -- a run for one location covers at most 24 branch-level rows
--    plus a bounded department/product breakdown, orders of magnitude smaller.
--    `reporting.ensure_fact_partition` also refuses any table it does not
--    already know by name (see V0031), so adding these here without touching
--    that function is the correct shape, not an oversight.
--
-- ---------------------------------------------------------------------------
-- forecast_run
-- ---------------------------------------------------------------------------
--
-- One row per generation: which location, which weekday, what sample fed it,
-- and under what model. Runs are never updated or replaced -- a rerun writes a
-- new run_id -- so the history of "what the model would have said" is itself
-- a fact a manager can audit, the same reason `close_runs` never overwrites.
CREATE TABLE reporting.forecast_run (
    run_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    location_id uuid NOT NULL,

    -- ISO-8601: 1 = Monday .. 7 = Sunday, matching demand-history's own axis.
    weekday smallint NOT NULL,

    -- Seasonal-naive, version 1: mean and sample standard deviation of the
    -- weekday's most recent `sample_size` occurrences, per operating-day hour.
    -- A later model bumps this rather than mutating what version 1 means.
    model_version integer NOT NULL DEFAULT 1,

    -- How many of the location's most recent qualifying occurrences of
    -- `weekday` fed the model -- mirrors demand-history's own sampleSize.
    requested_sample_size integer NOT NULL,

    -- Two-sided confidence level the stored interval targets (0.80 = 80%).
    -- Written down rather than assumed, so a stored interval stays
    -- interpretable even if a later model version changes the default.
    confidence_level numeric(4,3) NOT NULL,

    -- INCLUDE: every qualifying date counts fully (demand-history's own
    -- default, unchanged). EXCLUDE: a flagged holiday date is dropped from the
    -- sample entirely. WEIGHT: a flagged holiday date stays in the sample but
    -- counts at HolidayAwareness.HOLIDAY_WEIGHT -- see that class.
    holiday_mode varchar(16) NOT NULL DEFAULT 'INCLUDE',

    generated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_forecast_run_weekday CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT ck_forecast_run_sample_size CHECK (requested_sample_size >= 1),
    CONSTRAINT ck_forecast_run_confidence CHECK (confidence_level > 0 AND confidence_level < 1),
    CONSTRAINT ck_forecast_run_holiday_mode CHECK (holiday_mode IN ('INCLUDE', 'EXCLUDE', 'WEIGHT')),
    CONSTRAINT fk_forecast_run_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    -- Lets fact_forecast's own foreign key name a run and a tenant in one
    -- constraint, so a row can never reference another tenant's run -- the
    -- same shape kitchen.stations' uq_station_identity gives its own
    -- location-scoped children.
    CONSTRAINT uq_forecast_run_identity UNIQUE (run_id, tenant_id)
);

COMMENT ON TABLE reporting.forecast_run IS
    'Wave W02 (ADR 0043 Forecasting, scoped down -- see file header). One seasonal-naive generation for one location and weekday. Never updated: a rerun is a new row, so the model''s own history is auditable.';
COMMENT ON COLUMN reporting.forecast_run.holiday_mode IS
    'INCLUDE (default, matches demand-history unchanged), EXCLUDE (a flagged tenant.public_holidays date is dropped from the sample) or WEIGHT (kept, counted at HolidayAwareness.HOLIDAY_WEIGHT). 7.8b.';

CREATE INDEX ix_forecast_run_lookup ON reporting.forecast_run (tenant_id, location_id, weekday, generated_at DESC);

-- ---------------------------------------------------------------------------
-- fact_forecast
-- ---------------------------------------------------------------------------
--
-- One row per (run, business date, operating-day hour), optionally split by
-- department (category_id) or product (variant_id) for 7.8a. Exactly one of
-- three grains per row -- branch (both null), department (category_id only)
-- or product (variant_id set) -- enforced by the three partial unique indexes
-- below rather than a primary key, because a primary key cannot include a
-- nullable column and NULL is exactly what distinguishes the grains here.
--
-- `actual_quantity` and `absolute_percentage_error` are null until the
-- forecasted business date's business day closes -- ADR 0043's own "the
-- actual is written back after the day closes, so absolute_percentage_error
-- is a stored fact". `ForecastService.backfillActuals` is the only writer of
-- those two columns; everything else on a row is set once, at generation.
CREATE TABLE reporting.fact_forecast (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    location_id uuid NOT NULL,

    business_date date NOT NULL,

    -- Operating-day-relative: 0 is the hour the location's business day
    -- starts (ReportQueryService's BusinessDayBoundary), never a wall-clock
    -- hour. See DemandHistory's own hourOfDay for the sibling read this
    -- mirrors, changed in the same wave to use the same axis.
    operating_hour smallint NOT NULL,

    -- 7.8a. Null on a branch-level row. Never both this and variant_id set to
    -- two different products' categories -- a product row's own category, if
    -- resolved, is denormalised here too so a department roll-up does not
    -- need a second join back to catalog.
    category_id uuid,
    variant_id uuid,
    product_name_snapshot varchar(255),

    forecast_quantity numeric(8,2) NOT NULL,
    confidence_low numeric(8,2) NOT NULL,
    confidence_high numeric(8,2) NOT NULL,
    sample_size integer NOT NULL,

    actual_quantity numeric(8,2),
    absolute_percentage_error numeric(8,4),

    built_at timestamptz NOT NULL DEFAULT now(),

    -- Tenant-scoped on purpose: run_id alone would let a row reference
    -- another tenant's run, the exact cross-tenant path a foreign key exists
    -- to close (see uq_forecast_run_identity on the parent).
    CONSTRAINT fk_fact_forecast_run FOREIGN KEY (run_id, tenant_id)
        REFERENCES reporting.forecast_run (run_id, tenant_id),
    CONSTRAINT ck_fact_forecast_hour CHECK (operating_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_fact_forecast_quantities CHECK (
        forecast_quantity >= 0 AND confidence_low >= 0
        AND confidence_high >= confidence_low AND sample_size >= 1),
    CONSTRAINT ck_fact_forecast_actual CHECK (actual_quantity IS NULL OR actual_quantity >= 0),
    -- The pairing rule DemandHistory's own fact_order.seconds_late uses: an
    -- error rate is known exactly when an actual is, never independently.
    CONSTRAINT ck_fact_forecast_error_pairing CHECK (
        (actual_quantity IS NOT NULL) = (absolute_percentage_error IS NOT NULL))
);

-- Branch grain: at most one row per run/date/hour with no department or
-- product split.
CREATE UNIQUE INDEX ux_fact_forecast_branch
    ON reporting.fact_forecast (run_id, business_date, operating_hour)
    WHERE category_id IS NULL AND variant_id IS NULL;

-- Department grain: at most one row per run/date/hour/category, product unset.
CREATE UNIQUE INDEX ux_fact_forecast_department
    ON reporting.fact_forecast (run_id, business_date, operating_hour, category_id)
    WHERE category_id IS NOT NULL AND variant_id IS NULL;

-- Product grain: at most one row per run/date/hour/variant.
CREATE UNIQUE INDEX ux_fact_forecast_product
    ON reporting.fact_forecast (run_id, business_date, operating_hour, variant_id)
    WHERE variant_id IS NOT NULL;

CREATE INDEX ix_fact_forecast_comparison
    ON reporting.fact_forecast (tenant_id, location_id, business_date, operating_hour)
    WHERE category_id IS NULL AND variant_id IS NULL;

CREATE INDEX ix_fact_forecast_pending_actuals
    ON reporting.fact_forecast (tenant_id, business_date)
    WHERE actual_quantity IS NULL;

COMMENT ON TABLE reporting.fact_forecast IS
    'Wave W02 (ADR 0043 Forecasting, scoped down). One forecasted (business date, operating hour) at branch, department or product grain -- see the three partial unique indexes for exactly which. actual_quantity/absolute_percentage_error are null until the date''s business day closes.';
COMMENT ON COLUMN reporting.fact_forecast.operating_hour IS
    'Operating-day-relative, 0 = the location''s business-day start. Never a wall-clock hour -- 7.8''s own named gap, fixed in the same wave for demand-history''s hourOfDay too.';
COMMENT ON COLUMN reporting.fact_forecast.category_id IS
    '7.8a department grain. Null on a branch-level row. Not a foreign key: like fact_order_line.category_id, reporting keeps a derived copy rather than reaching into catalog on the read path (ADR 0023).';

-- forecast_run is never updated -- see this file's header -- so INSERT and
-- SELECT are the whole write surface. fact_forecast needs UPDATE too:
-- backfillActuals sets actual_quantity/absolute_percentage_error in place
-- once the forecasted date's business day closes.
GRANT SELECT, INSERT ON reporting.forecast_run TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON reporting.fact_forecast TO horecaos_application;
GRANT SELECT ON reporting.forecast_run TO horecaos_reporting_read;
GRANT SELECT ON reporting.fact_forecast TO horecaos_reporting_read;
