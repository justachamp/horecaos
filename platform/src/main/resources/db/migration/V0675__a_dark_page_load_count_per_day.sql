-- ADR 0183 D18 (row 7.6c): the visits stage of the customer funnel as a page-load COUNT, labelled "page loads, not people",
-- dark behind the PLATFORM flag reporting.page_load_count_enabled until counsel answers.
--
-- What this table is, and is not.
--
--   * A counter: one row per business day, brand and sales channel, incremented by a public, rate-limited ping the storefront
--     sends once per tab session. No cookie, no identifier, no address, no user agent, no session, no path. There is no
--     distinct-visitor figure and no page-view figure and none can be derived from this.
--   * The same legal posture as the three tables of V0554: counts only, asserted by BehaviouralFunnelStructureTests, which
--     now reads this table's columns back as well. Nothing here is a log of anyone.
--   * Empty until the flag is turned on. A day without a row is "no page load was counted" and, before the first row, "the
--     counter was not collecting"; the funnel read reports the days before the first row as unrecorded and never as zero.
--
-- Same retention as the rest of the funnel (ADR 0167 Decision 7): swept by FunnelRetentionSweeper, report-only first.

CREATE TABLE reporting.page_load_counts_day (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    business_date date NOT NULL,
    channel_code varchar(32) NOT NULL,

    page_loads bigint NOT NULL,

    CONSTRAINT pk_page_load_counts_day PRIMARY KEY (tenant_id, brand_id, business_date, channel_code),
    CONSTRAINT ck_page_load_counts_day_count CHECK (page_loads >= 0),
    CONSTRAINT ck_page_load_counts_day_channel CHECK (length(btrim(channel_code)) > 0)
);

CREATE INDEX ix_page_load_counts_day_date ON reporting.page_load_counts_day (business_date);

COMMENT ON TABLE reporting.page_load_counts_day IS
    'ADR 0183 D18. Page loads (one per browser tab session) per business day, brand and sales channel. A count of loads and not of people: no cookie, identifier, address, session or path is read or stored. Written only while reporting.page_load_count_enabled is true (PLATFORM, default false).';

-- The public ping upserts, the funnel read selects, the retention job deletes. horecaos_reporting_read already holds SELECT on
-- every present and future table in the reporting schema through ALTER DEFAULT PRIVILEGES (V0031).
GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.page_load_counts_day TO horecaos_application;
