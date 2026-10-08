-- ADR 0157, Release 2: view counts for stories, banners and pop-ups.
--
-- ANONYMOUS COUNTERS ONLY. The storefront reports impressions, clicks, dismissals and story
-- completions in batches; the platform stores a per-day total per (item, revision, channel) and
-- NOTHING that identifies who or what reported: no device, customer, address or network
-- identifier, and not even a timestamp finer than the day. They are labelled client-reported in
-- the console because anyone can inflate them, and they are not a metric the reporting layer
-- (ADR 0043) consumes.
--
-- The day is the tenant's calendar day (the brand's tenant timezone), computed by the service.
-- Counters reference the REVISION that was live, so editing an item starts a fresh series and a
-- disputed number can be tied to the exact text and window it was counted against.

CREATE TABLE content.item_daily_stats (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    item_id uuid NOT NULL,
    revision_no integer NOT NULL,
    day date NOT NULL,
    channel_code varchar(32) NOT NULL,

    impressions bigint NOT NULL DEFAULT 0,
    clicks bigint NOT NULL DEFAULT 0,
    dismissals bigint NOT NULL DEFAULT 0,
    completions bigint NOT NULL DEFAULT 0,

    CONSTRAINT pk_content_item_daily_stats PRIMARY KEY (item_id, revision_no, day, channel_code),
    CONSTRAINT fk_content_stats_revision FOREIGN KEY (item_id, revision_no, tenant_id)
        REFERENCES content.item_revisions (item_id, revision_no, tenant_id),
    CONSTRAINT fk_content_stats_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT ck_content_stats_nonnegative CHECK (
        impressions >= 0 AND clicks >= 0 AND dismissals >= 0 AND completions >= 0)
);

CREATE INDEX ix_content_stats_brand_day ON content.item_daily_stats (tenant_id, brand_id, day);

COMMENT ON TABLE content.item_daily_stats IS
    'ADR 0157 Release 2: anonymous per-day counters reported by the storefront and labelled client-reported. Holds no device, customer, address or network identifier. Not a metric the reporting layer consumes.';

GRANT SELECT, INSERT, UPDATE ON content.item_daily_stats TO horecaos_application;
