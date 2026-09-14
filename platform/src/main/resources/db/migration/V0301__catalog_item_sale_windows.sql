-- Row 4.2g (P47): the per-item sale schedule V0020 withdrew.
--
-- V0020's own footer records the withdrawal: "ADR 0016's sketched
-- `location_offerings.sales_schedule_id` is withdrawn by the same decision
-- ... item windows reference tenant.service_schedules instead." No table was
-- ever created to do that referencing, so a breakfast-only item has had no
-- binding at all -- a branch manager stops it by hand at closing and un-stops
-- it by hand the next morning, every single day.
--
-- This is deliberately not a reference to a reusable, named
-- tenant.service_schedules row (the shape V0020's own comment sketched). That
-- table's exceptions/acceptsScheduledOrders machinery answers "when is the
-- branch open"; an item's own sale window is a narrower question with no
-- named-schedule-library UI anywhere in the product editor's brief, and
-- pointing at a shared object would mean editing "Breakfast hours" from one
-- item's screen silently moves every other item bound to the same row. Each
-- (location, variant) owns its own window rows outright, exactly like
-- tenant.service_schedule_rules owns a weekly window each, but with no
-- indirection through a named header.
--
-- Weekly-recurring only, no dated exceptions: the gap this closes
-- (operations-gap-map.md 4.2g) is a daily on/off pattern, not a holiday
-- calendar, and tenant.service_schedule_exceptions already answers "is the
-- branch itself closed today" for every item at once. Zero rows for a
-- (location, variant) pair means unrestricted -- the current, unchanged
-- behaviour -- so adding this table changes nothing for every item that never
-- gets a window.
CREATE TABLE catalog.item_sale_windows (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    variant_id uuid NOT NULL,

    -- ISO-8601 numbering, 1 = Monday through 7 = Sunday -- matching
    -- tenant.service_schedule_rules.day_of_week and java.time.DayOfWeek
    -- .getValue(), so the resolver and q-schedule-grid never have to
    -- translate between two conventions for the same fact.
    day_of_week smallint NOT NULL,
    opens_at time NOT NULL,
    closes_at time NOT NULL,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_item_sale_window_day CHECK (day_of_week BETWEEN 1 AND 7),
    -- Composite so a variant id from another brand, or a location id from
    -- another tenant, cannot be bound -- matching catalog.preparation_bands'
    -- and catalog.channel_offering_exclusions' own style for reaching across
    -- into tenant.locations.
    CONSTRAINT fk_item_sale_window_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_item_sale_window_variant FOREIGN KEY (variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id),
    -- Refuses the exact duplicate window a double-click on "+ Window" would
    -- otherwise insert twice; the editor still replaces the whole set on every
    -- save; see JdbcCatalogStore#replaceItemSaleWindows.
    CONSTRAINT uq_item_sale_window UNIQUE (location_id, variant_id, day_of_week, opens_at, closes_at)
);

CREATE INDEX ix_item_sale_windows_lookup
    ON catalog.item_sale_windows (tenant_id, location_id, variant_id);

COMMENT ON TABLE catalog.item_sale_windows IS
    'Row 4.2g: the per-item weekly sale schedule V0020 withdrew and never replaced. Resolved in the location''s own timezone; empty for a variant means unrestricted.';

GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.item_sale_windows TO horecaos_application;
