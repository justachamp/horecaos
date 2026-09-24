-- Settings 10.2b (operations-gap-map): Locations detail Tab 1 (Основное) has
-- no sort order, no venue attributes, and no localized branch content.
-- `tenant.locations` has carried a display name, a place (V0023) and nothing
-- else describable since V0003.
--
-- Three unrelated gaps, addressed together because they share Tab 1 and share
-- this row.

-- ---------------------------------------------------------------------------
-- 1. Manual sort order
-- ---------------------------------------------------------------------------
--
-- The branch list (10.2a) has always ordered by display_name -- alphabetic by
-- accident, the same "no deliberate ranking" gap V0400 closed for outcome
-- reasons. A tenant with a flagship branch or a natural visiting order (main
-- kitchen first, satellite kiosks after) has no way to put it first.
--
-- A plain integer, not a fractional or gap-numbered scheme: `JdbcTenantControlPlaneStore
-- .findLocations` reads a handful of rows per brand, never paginates, and a
-- console reorder (once one exists) can always renumber the whole visible set
-- the same whole-set-write way `10.10a`'s reason reorder does.
ALTER TABLE tenant.locations
    ADD COLUMN sort_order integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN tenant.locations.sort_order IS
    '10.2b. The branch list''s own manual ordering, lowest first, ties broken by display_name. Defaults to 0 so an unordered fleet reads exactly as it always has.';

-- Serves the branch list's own query, which now orders by this column first.
CREATE INDEX ix_locations_brand_sort_order ON tenant.locations (tenant_id, brand_id, sort_order);

-- ---------------------------------------------------------------------------
-- 2. Venue attributes
-- ---------------------------------------------------------------------------
--
-- What a guest or an operator planning capacity would want to know once they
-- are standing in front of the branch, distinct from LocationPlace's "how to
-- find it": seating, roughly what a cheque runs, and whether there is
-- somewhere to park or leave a child. None of it is required -- most of the
-- fleet was registered long before this screen existed to ask.
ALTER TABLE tenant.locations
    ADD COLUMN seats integer,
    -- Money: integer minor units plus an ISO currency code (ADR 0018), same
    -- as everywhere else in this schema -- never a fractional column.
    ADD COLUMN average_cheque_amount bigint,
    ADD COLUMN average_cheque_currency char(3),
    ADD COLUMN has_parking boolean NOT NULL DEFAULT false,
    ADD COLUMN has_playground boolean NOT NULL DEFAULT false,
    ADD COLUMN virtual_tour_url varchar(500);

COMMENT ON COLUMN tenant.locations.seats IS '10.2b. Roughly how many guests the dining room seats. Absent means not recorded, not zero.';
COMMENT ON COLUMN tenant.locations.average_cheque_amount IS '10.2b. What a typical cheque runs at this branch, in minor units of average_cheque_currency. Informational -- not read by pricing.';
COMMENT ON COLUMN tenant.locations.virtual_tour_url IS '10.2b. A link to a 360 walkthrough or similar, shown as-is; the platform does not validate it resolves.';

ALTER TABLE tenant.locations
    ADD CONSTRAINT ck_locations_seats CHECK (seats IS NULL OR seats >= 0),
    ADD CONSTRAINT ck_locations_average_cheque_amount CHECK (average_cheque_amount IS NULL OR average_cheque_amount >= 0),
    -- A pairing check, the same shape ck_locations_coordinate_source_agrees
    -- (V0023) already uses for latitude/longitude: an amount without a
    -- currency cannot be displayed, and a currency without an amount is not
    -- an average of anything.
    ADD CONSTRAINT ck_locations_average_cheque_pairing CHECK (
        (average_cheque_amount IS NULL) = (average_cheque_currency IS NULL)
    ),
    ADD CONSTRAINT ck_locations_average_cheque_currency CHECK (
        average_cheque_currency IS NULL OR average_cheque_currency ~ '^[A-Z]{3}$'
    ),
    ADD CONSTRAINT ck_locations_virtual_tour_url CHECK (
        virtual_tour_url IS NULL OR virtual_tour_url ~ '^https?://'
    );

-- ---------------------------------------------------------------------------
-- 3. Localized branch content
-- ---------------------------------------------------------------------------
--
-- `tenant.locations.display_name` is the operational name every screen and
-- report already uses, in no particular language -- the same role
-- `tenant.brands.display_name` plays for a brand. It is not what this table
-- is for. This is the customer-facing name and description a storefront
-- would show in the guest's own language, one row per locale, the same shape
-- V0242's `tenant.brand_locales` already established for a brand's own
-- per-language description -- widened here to also carry a localized display
-- name, since a branch's public name (\"Chilonzor 2\" vs a mall's own naming
-- for the same unit) legitimately differs from its operational one.
CREATE TABLE tenant.location_content (
    tenant_id uuid NOT NULL,
    location_id uuid NOT NULL,

    -- Same open vocabulary as brand_locales: not a foreign key, because there
    -- is no platform-wide locale registry yet (see that table's own comment).
    locale varchar(16) NOT NULL,

    display_name varchar(200),
    description varchar(2000),

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (location_id, locale),
    CONSTRAINT fk_location_content_location FOREIGN KEY (tenant_id, location_id)
        REFERENCES tenant.locations (tenant_id, id),
    CONSTRAINT ck_location_content_not_blank CHECK (
        (display_name IS NULL OR length(btrim(display_name)) > 0)
        AND (description IS NULL OR length(btrim(description)) > 0)
    )
);

CREATE INDEX ix_location_content_tenant ON tenant.location_content (tenant_id, location_id);

COMMENT ON TABLE tenant.location_content IS
    '10.2b. A branch''s own localized display name and description, one row per supported locale. Absent for a locale means the platform default is shown, not a blank.';

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.location_content TO horecaos_application;
