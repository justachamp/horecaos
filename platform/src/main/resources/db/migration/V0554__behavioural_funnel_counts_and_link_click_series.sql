-- ADR 0167 (row 7.6c): the customer funnel and the acquisition-source chart, built from
-- server-side facts the platform already holds, as daily counts that contain no subject.
--
-- What this migration is, and is not.
--
--   * Three tables of COUNTS. No account id, no guest reference hash, no customer subject
--     hash, no session, no device, no address. A structural test (BehaviouralFunnelStructureTests)
--     reads these columns back from information_schema and fails on any name that suggests a
--     person; the absence is the legal posture of the whole record, so it is asserted, not
--     trusted.
--   * Nothing here collects anything from a browser, an app or a bot. ADR 0167 Decision 1
--     reopens ADR 0043's "behavioural telemetry ships from day one" bullet: no analytics.events
--     topic, no fact_behaviour. The visits stage of the funnel is "not measured" until counsel
--     confirms a basis, and it renders as exactly that, never as zero.
--   * The tier 2 attribution columns (acquisition_link_id on the account, attribution_link_id on
--     the order) are SPECIFIED by the record and deliberately not created: a column nobody may
--     write is schema everybody must reason about, and attaching how a person arrived to the
--     person is the collection that waits for a legal answer.
--
-- A NULL count means "not recorded" (the carts it would have been counted from were already
-- past retention when the capture ran) and is never 0. The chart draws it as a gap.

CREATE TABLE reporting.fact_funnel_day (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    business_date date NOT NULL,
    channel_code varchar(32) NOT NULL,

    -- Distinct carts whose first line was added on this business date.
    basket_started integer,
    -- Distinct carts with a checkout attempt on this business date, whatever its outcome.
    checkout_started integer,
    -- Orders placed on this business date, from reporting.fact_order.
    ordered integer,

    boundary_version integer NOT NULL,
    computed_at timestamptz NOT NULL,

    CONSTRAINT pk_fact_funnel_day PRIMARY KEY (tenant_id, brand_id, business_date, channel_code),
    CONSTRAINT ck_fact_funnel_day_basket CHECK (basket_started IS NULL OR basket_started >= 0),
    CONSTRAINT ck_fact_funnel_day_checkout CHECK (checkout_started IS NULL OR checkout_started >= 0),
    CONSTRAINT ck_fact_funnel_day_ordered CHECK (ordered IS NULL OR ordered >= 0),
    CONSTRAINT ck_fact_funnel_day_channel CHECK (length(btrim(channel_code)) > 0)
);

CREATE INDEX ix_fact_funnel_day_date ON reporting.fact_funnel_day (business_date);

COMMENT ON TABLE reporting.fact_funnel_day IS
    'ADR 0167. One row per business day, brand and sales channel: how many baskets were started, how many carts reached checkout and how many orders were placed. Counts only, no subject. NULL = not recorded (the carts were past retention), never 0. Written by DayCloseService inside the close transaction; deleted by the funnel retention job after reporting.funnel_retention_days.';

CREATE TABLE reporting.fact_registration_day (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    business_date date NOT NULL,
    origin varchar(16) NOT NULL,

    -- New brand profiles created on this business date, by how the account came to exist.
    registered integer NOT NULL,

    boundary_version integer NOT NULL,
    computed_at timestamptz NOT NULL,

    CONSTRAINT pk_fact_registration_day PRIMARY KEY (tenant_id, brand_id, business_date, origin),
    CONSTRAINT ck_fact_registration_day_origin CHECK (
        origin IN ('SELF_SERVICE', 'OPERATOR', 'IMPORT', 'AGGREGATOR', 'MIGRATION')),
    CONSTRAINT ck_fact_registration_day_count CHECK (registered >= 0)
);

CREATE INDEX ix_fact_registration_day_date ON reporting.fact_registration_day (business_date);

COMMENT ON TABLE reporting.fact_registration_day IS
    'ADR 0167. New brand profiles per business day, brand and account origin. A count, never a list of people. Same retention and the same close transaction as fact_funnel_day.';

-- The one-time backfill of the days closed before this capture existed (ADR 0167, "Close job and
-- backfill"): a row per tenant, written when its history has been walked, so the walk happens once.
-- The days it reads are the ones whose carts, checkout attempts and profiles are still in the live
-- tables; dates older than the cart retention window get NULL baskets and checkouts and the
-- registration and ordered counts from the tables that keep them. It holds a date and nothing else.
CREATE TABLE reporting.funnel_backfill_state (
    tenant_id uuid PRIMARY KEY,
    backfilled_through date NOT NULL,
    updated_at timestamptz NOT NULL
);

COMMENT ON TABLE reporting.funnel_backfill_state IS
    'ADR 0167. A tenant present here has had its already-closed business days walked once to fill reporting.fact_funnel_day and fact_registration_day. Days closed afterwards are captured by DayCloseService itself.';

-- The anonymous click series for an attribution link: one counter per link and day, upserted by
-- the public click endpoint. marketing.attribution_links.click_count remains the running total.
-- The endpoint records no address, no user agent and no cookie (ADR 0044: "a count only, never an
-- event log naming who clicked"); the rate limit lives in memory.
CREATE TABLE marketing.attribution_link_clicks_day (
    tenant_id uuid NOT NULL,
    link_id uuid NOT NULL,
    day date NOT NULL,
    clicks integer NOT NULL,

    CONSTRAINT pk_attribution_link_clicks_day PRIMARY KEY (tenant_id, link_id, day),
    CONSTRAINT fk_attribution_link_clicks_day_link FOREIGN KEY (link_id, tenant_id)
        REFERENCES marketing.attribution_links (id, tenant_id),
    CONSTRAINT ck_attribution_link_clicks_day_count CHECK (clicks >= 0)
);

CREATE INDEX ix_attribution_link_clicks_day_day ON marketing.attribution_link_clicks_day (day);

COMMENT ON TABLE marketing.attribution_link_clicks_day IS
    'ADR 0167 Decision 4, tier 1. Clicks per attribution link per day. A count and never an event: nothing here can be joined to a person, a session or a device.';

-- The capture reads the live carts and checkout attempts by creation instant, once per business
-- day per tenant. Without these the day-close scan is a sequential read of every cart line ever
-- written.
CREATE INDEX IF NOT EXISTS ix_cart_lines_tenant_created ON ordering.cart_lines (tenant_id, created_at);
CREATE INDEX IF NOT EXISTS ix_checkout_attempts_tenant_created ON ordering.checkout_attempts (tenant_id, created_at);

-- The application role writes all three (the click endpoint upserts, the close job writes, the
-- retention job deletes). horecaos_reporting_read already holds SELECT on every present and
-- future table in the reporting schema through ALTER DEFAULT PRIVILEGES (V0031); the marketing
-- table is not a reporting table and is read through marketing.api, never by that role.
GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.fact_funnel_day TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.fact_registration_day TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.funnel_backfill_state TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON marketing.attribution_link_clicks_day TO horecaos_application;
