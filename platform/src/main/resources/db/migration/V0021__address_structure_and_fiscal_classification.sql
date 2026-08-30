-- Two schema extensions to already-built modules, done now because both become
-- data migrations the moment either module carries production rows.
--
-- 1. ADR 0015: the address model gains a stated coordinate source, so "we have
--    not geocoded this yet" and "this address legitimately has no point" stop
--    being the same NULL pair.
-- 2. ADR 0038 (Proposed): ИКПУ/MXIK and package code on catalog nodes.

-- ---------------------------------------------------------------------------
-- 1. Addresses: why a coordinate is missing
-- ---------------------------------------------------------------------------
--
-- ADR 0015 leaves latitude/longitude nullable and says nothing about what a null
-- pair means. In this market both of these are ordinary:
--
--   * the address was captured by an operator over the phone and nobody has run
--     it through a geocoder yet, and
--   * the address genuinely cannot be geocoded — a mahalla house described by
--     its ориентир (landmark), which is how a large share of addresses here are
--     actually given.
--
-- The first should be retried and can be fixed. The second is finished and must
-- not be retried, and dispatch must route it by calling rather than by point.
-- With one nullable pair and no source, a geocoding backfill cannot tell them
-- apart: it either re-queries every landmark address forever, or it stops
-- retrying the ones a provider outage left empty. The column is what separates
-- them, and it is in clear because it is a workflow state, not personal data —
-- the address text itself stays inside encrypted_fields.
ALTER TABLE customer.addresses
    ADD COLUMN coordinate_source varchar(24) NOT NULL DEFAULT 'NOT_GEOCODED';

COMMENT ON COLUMN customer.addresses.coordinate_source IS
    'ADR 0015. Why this address does or does not have a point. NOT_GEOCODED is retryable; LANDMARK_ONLY is a finished state and must not be re-queued for geocoding.';

ALTER TABLE customer.addresses
    ADD CONSTRAINT ck_address_coordinate_source CHECK (
        coordinate_source IN (
            -- Not attempted, or attempted and failed. Retryable.
            'NOT_GEOCODED',
            -- Deliberately no point: the address is a landmark description and
            -- dispatch reaches it by calling. Not a failure, not retryable.
            'LANDMARK_ONLY',
            -- Resolved by the ADR 0015 geocoding port.
            'GEOCODER',
            -- The customer dropped a pin on a map.
            'CUSTOMER_PIN',
            -- An operator placed the pin, usually while on the phone.
            'OPERATOR_PIN',
            -- Migration-only. A point that predates this column, whose
            -- provenance nobody recorded. Never written by the application:
            -- naming it rather than folding it into GEOCODER keeps a later
            -- provenance audit able to find exactly these rows.
            'LEGACY_UNSOURCED'
        )
    );

-- Half a coordinate is unroutable, and the original check let it through: with
-- longitude NULL the range AND evaluates to NULL, and a CHECK constraint passes
-- on NULL. Any such row is discarded rather than carried forward, because a
-- latitude alone points at the equator and would be treated as a real location
-- by the equivalence below.
UPDATE customer.addresses
SET latitude = NULL, longitude = NULL
WHERE (latitude IS NULL) <> (longitude IS NULL);

-- Rows that already carry a point were written before any source was recorded.
UPDATE customer.addresses
SET coordinate_source = 'LEGACY_UNSOURCED'
WHERE latitude IS NOT NULL;

-- Source and coordinates must agree. Without this a row can claim GEOCODER and
-- carry no point (so dispatch believes it has a routable address and does not),
-- or claim LANDMARK_ONLY while carrying one (so a backfill treats a finished
-- address as unresolved). Written as an equivalence rather than two one-way
-- rules so neither direction can drift.
ALTER TABLE customer.addresses
    ADD CONSTRAINT ck_address_coordinate_source_agrees CHECK (
        (coordinate_source IN ('NOT_GEOCODED', 'LANDMARK_ONLY')) = (latitude IS NULL)
    );

-- Pair completeness, now asserted separately from range for the reason above.
ALTER TABLE customer.addresses
    DROP CONSTRAINT ck_address_coordinates;

ALTER TABLE customer.addresses
    ADD CONSTRAINT ck_address_coordinates CHECK (
        (latitude IS NULL) = (longitude IS NULL)
        AND (
            latitude IS NULL
            OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
        )
    );

-- Serves the geocoding backfill: the addresses still worth asking a provider
-- about. Partial, so it does not carry the landmark-only rows that will never
-- be selected by it.
CREATE INDEX ix_addresses_awaiting_geocoding
    ON customer.addresses (tenant_id)
    WHERE status = 'ACTIVE' AND coordinate_source = 'NOT_GEOCODED';

-- Note on what did NOT move here. подъезд (entrance), этаж (floor), квартира
-- (apartment) and ориентир (landmark) are structured fields inside
-- encrypted_fields, not columns. They describe where one person lives; a column
-- would put them in every backup, replica and export in clear, which is exactly
-- what ADR 0029 exists to prevent. No query needs to filter on a floor.

-- ---------------------------------------------------------------------------
-- 2. Catalog: fiscal product classification
-- ---------------------------------------------------------------------------
--
-- ИКПУ/MXIK is the Uzbek state product/service classification code and the
-- package code (код упаковки) names the unit it is sold in. Both appear on a
-- fiscal receipt, and aggregators reject a menu whose items carry neither. ADR
-- 0016 left this as an open input; ADR 0038 closes it and states the codes are
-- mandatory on every priceable node.
--
-- Two things about the shape of this change.
--
-- First, the columns are nullable and nothing here refuses a row without them.
-- ADR 0038 is Proposed and pending legal input on the reference list and its
-- sign-off, so making classification a publication blocker today would wall off
-- every existing brand over a decision nobody has ratified. CatalogValidator
-- reports the gap as a WARNING instead, in the same way the unwired pricing
-- check is reported rather than hidden.
--
-- Second, ADR 0038's accepted form is a separate catalog.fiscal_classifications
-- table keyed by (priceable_type, priceable_id) with marking, excise, and age
-- restriction alongside, validated against an imported catalog.mxik_reference.
-- These columns are deliberately the smaller interim: they hold the two fields
-- every aggregator and every receipt needs, on the rows that already exist, so
-- an operator can start entering codes before that table lands. Moving them into
-- it later is a data migration inside one schema, which is the cheap direction.
ALTER TABLE catalog.products
    ADD COLUMN mxik_code varchar(32),
    ADD COLUMN package_code varchar(32);

ALTER TABLE catalog.variants
    ADD COLUMN mxik_code varchar(32),
    ADD COLUMN package_code varchar(32);

ALTER TABLE catalog.modifier_options
    ADD COLUMN mxik_code varchar(32),
    ADD COLUMN package_code varchar(32);

COMMENT ON COLUMN catalog.products.mxik_code IS
    'ADR 0038 (Proposed). ИКПУ/MXIK. A product is not itself priceable; this is the default its variants inherit when they carry no code of their own, so a single-variant dish is classified in one place.';
COMMENT ON COLUMN catalog.variants.mxik_code IS
    'ADR 0038 (Proposed). ИКПУ/MXIK for a priceable node. Overrides the product code when set.';
COMMENT ON COLUMN catalog.modifier_options.mxik_code IS
    'ADR 0038 (Proposed). ИКПУ/MXIK for a priceable node. A modifier reaches the receipt as its own line, so it needs its own classification.';

-- No format check. ADR 0038 is explicit that a code's shape belongs to the
-- official reference list and not to this schema: asserting a length or a digit
-- pattern the tax authority later changes is a migration nobody planned for.
--
-- What is checked is that a present code is not blank. An empty string satisfies
-- "the column is set" while classifying nothing, so without this the coverage
-- warning below would report a brand as fully classified while its receipts went
-- out unclassified — a silent failure rather than a visible gap.
ALTER TABLE catalog.products
    ADD CONSTRAINT ck_product_mxik_not_blank CHECK (
        mxik_code IS NULL OR length(btrim(mxik_code)) > 0
    ),
    ADD CONSTRAINT ck_product_package_not_blank CHECK (
        package_code IS NULL OR length(btrim(package_code)) > 0
    );

ALTER TABLE catalog.variants
    ADD CONSTRAINT ck_variant_mxik_not_blank CHECK (
        mxik_code IS NULL OR length(btrim(mxik_code)) > 0
    ),
    ADD CONSTRAINT ck_variant_package_not_blank CHECK (
        package_code IS NULL OR length(btrim(package_code)) > 0
    );

ALTER TABLE catalog.modifier_options
    ADD CONSTRAINT ck_modifier_option_mxik_not_blank CHECK (
        mxik_code IS NULL OR length(btrim(mxik_code)) > 0
    ),
    ADD CONSTRAINT ck_modifier_option_package_not_blank CHECK (
        package_code IS NULL OR length(btrim(package_code)) > 0
    );

-- Serves the per-brand coverage question ADR 0038 needs answered before it can
-- turn the warning into a blocker: which priceable nodes are still unclassified.
-- Partial, because the classified rows are not the ones anyone is looking for.
CREATE INDEX ix_variants_unclassified
    ON catalog.variants (tenant_id, brand_id)
    WHERE status <> 'ARCHIVED' AND mxik_code IS NULL;

CREATE INDEX ix_modifier_options_unclassified
    ON catalog.modifier_options (tenant_id, brand_id)
    WHERE status <> 'ARCHIVED' AND mxik_code IS NULL;
