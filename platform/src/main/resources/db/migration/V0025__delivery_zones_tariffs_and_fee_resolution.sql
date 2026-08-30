-- ADR 0037: delivery zones, tariffs, and delivery-fee resolution.
--
-- Between ADR 0018's reserved pipeline stages and ADR 0014's "snapshotted at
-- checkout" there is a number on every delivery order that nothing owns. This is
-- where it comes from, and — more importantly — this is where the evidence for it
-- lives, because the question that actually gets asked six weeks later is not
-- "what is the fee" but "why was that address charged 18,000 so'm on a Friday
-- evening", and a total cannot answer it.
--
-- Everything lives in schema `fulfillment`, which ADR 0037 assigns and which V0001
-- created and nothing has used since. Pricing reads a resolved charge through a
-- port and never these tables: if pricing could join them, a zone edit would
-- change what a past quote computes, which is exactly the property this design
-- exists to prevent.
--
-- ---------------------------------------------------------------------------
-- The legacy field mapping
-- ---------------------------------------------------------------------------
--
-- `docs/domains/legacy-profile-findings.md` section 6 found that the legacy
-- `vendors.delivery` JSON already carries a radius, a distance tariff, a minimum
-- order value and a time-banded surcharge — the same five facts ADR 0037 arrived
-- at independently, before anyone had read that column. Seventeen branches, two
-- key shapes, one model. That agreement is the strongest evidence available that
-- the shape below is the right one, and it turns the import from a redesign into
-- a field mapping. Each legacy key lands here:
--
--   max_distance     -> delivery_tariff_versions.max_distance_meters, and the
--                       radius of the circle in service_zone_versions.authoring_shape
--                       that the zone's geometry is buffered from. It is one number
--                       in the legacy row doing two jobs — how far the branch will
--                       go, and the shape of the area it will go within — and both
--                       jobs still exist, so it is written to both places rather
--                       than one of them being inferred from the other later.
--   distance          -> delivery_tariff_bands.to_meters of the first band, the
--                       radius included in the flat price.
--   distance_price    -> delivery_tariff_bands.base_minor of that first band.
--   prices_per_km     -> delivery_tariff_bands.per_km_minor of the second band,
--                       which runs [distance, max_distance) with base_minor 0.
--   min_order_price   -> service_zone_versions.min_basket_minor. On the zone and
--                       not the tariff, because ADR 0037 states the minimum
--                       basket per zone: a city-centre zone and an outskirts zone
--                       sharing one rate table still take different minimums.
--   peak_hours        -> delivery_tariff_time_rules, one row per window, with the
--                       uplift expressed as surcharge_minor when the legacy value
--                       is an amount and multiplier_basis_points when it is a
--                       factor.
--
-- The `discount` key two of the seventeen branches carry has deliberately no
-- column here. It is not a delivery fee at all; whether it means a threshold
-- waiver, a flat reduction, or a promotion is not recoverable from the JSON, and
-- inventing a column for a field whose meaning is a guess is how a wrong number
-- acquires a schema and becomes permanent. The importer must quarantine those two
-- branches under ADR 0024 and have a human read them.

-- ---------------------------------------------------------------------------
-- Extensions
-- ---------------------------------------------------------------------------
--
-- PostGIS answers containment with a spatial index. ADR 0037 rejected computing
-- point-in-polygon in Java: a hand-written ray-cast is a correctness liability on
-- shared borders, and scanning every polygon of every zone on the checkout path
-- does not fit the latency budget. `infra/postgres/Dockerfile` installs 3.6 and
-- fails the image build if the extension does not load, so this line is verified
-- rather than hoped for.
--
-- btree_gist is what lets the band exclusion constraint below mix an equality on
-- uuid with an overlap on a range in one index. Both need superuser at migration
-- time, which the ADR 0034 self-hosted deployment has and a managed PostgreSQL
-- generally would not — the alternative named there is H3 cell indexing.
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------------------
-- A branch without a point cannot originate a zone
-- ---------------------------------------------------------------------------
--
-- V0023 already says a location's coordinate is absent exactly when
-- coordinate_source is NOT_GEOCODED, and refuses a LANDMARK_ONLY state on
-- purpose: a branch has a door a human can pin. What it cannot express is that
-- (0, 0) is not a pin.
--
-- Three of the real legacy branches sit at exactly (0, 0). That is a genuine
-- point in the Gulf of Guinea, four hundred kilometres off Ghana, and every
-- distance calculation in this migration and every containment test in PostGIS
-- accepts it without complaint. A branch there is 6,000 km from every Tashkent
-- address, so it silently prices nothing, serves nothing, and reports as fully
-- configured. That is worse than an unlocated branch, which at least announces
-- itself.
--
-- No live row can violate this — the columns arrived in V0023 and the legacy
-- import has not run — so the constraint is added without a backfill, and the
-- import will now be refused at the database rather than producing three branches
-- that look located and are not.
ALTER TABLE tenant.locations
    ADD CONSTRAINT ck_locations_coordinate_not_null_island CHECK (
        latitude IS NULL OR NOT (latitude = 0 AND longitude = 0)
    );

COMMENT ON CONSTRAINT ck_locations_coordinate_not_null_island ON tenant.locations IS
    'ADR 0037. (0, 0) is a point in the Gulf of Guinea that every distance and containment test accepts, so an unlocated branch stored as the origin would price and serve nothing while reporting as configured.';

-- ---------------------------------------------------------------------------
-- Regions
-- ---------------------------------------------------------------------------
--
-- A code, a name, a centre, and an explicit bounding box. Nothing else: ADR 0037
-- is emphatic that a region carries no pricing, no configuration and no
-- permissions, because the moment it carries any of those it has become the
-- Tenant -> Brand -> Region -> Location tier ADR 0002 rejected.
--
-- The box is the point of the table. An unconstrained geocoder asked for a
-- Tashkent street name will return a plausible street of the same name in another
-- country; the address is accepted, and the mistake surfaces when a courier is
-- standing somewhere else entirely. The box also bounds zone activation, which is
-- what stops an operator saving a polygon in the wrong hemisphere after a
-- latitude/longitude transposition — a class of error no containment test
-- complains about, because the polygon is perfectly valid, just elsewhere.
CREATE TABLE fulfillment.regions (
    id uuid PRIMARY KEY,
    -- Null means a platform region every tenant may reference. Tashkent is not
    -- one tenant's fact.
    tenant_id uuid,
    code varchar(32) NOT NULL,
    display_name_ru varchar(200) NOT NULL,
    display_name_uz varchar(200) NOT NULL,
    display_name_en varchar(200) NOT NULL,
    centre_lat double precision NOT NULL,
    centre_lon double precision NOT NULL,
    bbox_sw_lat double precision NOT NULL,
    bbox_sw_lon double precision NOT NULL,
    bbox_ne_lat double precision NOT NULL,
    bbox_ne_lon double precision NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_region_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_region_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_region_coordinates CHECK (
        centre_lat BETWEEN -90 AND 90 AND centre_lon BETWEEN -180 AND 180
        AND bbox_sw_lat BETWEEN -90 AND 90 AND bbox_sw_lon BETWEEN -180 AND 180
        AND bbox_ne_lat BETWEEN -90 AND 90 AND bbox_ne_lon BETWEEN -180 AND 180
    ),
    -- A degenerate or inverted box accepts nothing or everything, and both fail
    -- silently: the first marks every geocode LOW_CONFIDENCE, the second marks
    -- none. Neither raises an error anywhere.
    CONSTRAINT ck_region_bbox_oriented CHECK (
        bbox_ne_lat > bbox_sw_lat AND bbox_ne_lon > bbox_sw_lon
    ),
    -- A centre outside its own box is a data-entry error that would put the map
    -- editor's initial view outside the area it is meant to be drawing in.
    CONSTRAINT ck_region_centre_within_bbox CHECK (
        centre_lat BETWEEN bbox_sw_lat AND bbox_ne_lat
        AND centre_lon BETWEEN bbox_sw_lon AND bbox_ne_lon
    ),
    CONSTRAINT fk_region_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id)
);

-- NULLS NOT DISTINCT, so two platform regions cannot share a code. Without it
-- PostgreSQL treats every null tenant as distinct and the platform half of this
-- table has no uniqueness at all.
CREATE UNIQUE INDEX uq_regions_tenant_code
    ON fulfillment.regions (tenant_id, code) NULLS NOT DISTINCT;

COMMENT ON TABLE fulfillment.regions IS
    'ADR 0037. A code, a name, a centre and a bounding box that constrains geocoding and zone activation. Carries no pricing, configuration or permissions, so it is not the Region tier ADR 0002 rejected.';

-- ---------------------------------------------------------------------------
-- Zones
-- ---------------------------------------------------------------------------
--
-- One entity with a typed role, not Delever's three overlapping geometry layers
-- and not the legacy dashboard's JSON shapes typed circle/polygon/city. Three
-- layers with no documented interaction is the ambiguity rather than a fix for
-- it, and three containment implementations disagree at the boundary — which is
-- the one place a customer notices.
CREATE TABLE fulfillment.service_zones (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    -- DELIVERY decides whether an address may be delivered to and at what price.
    -- CATCHMENT decides which locations are candidates and guards the branch
    -- boundary. A "free geozone" is not a third role: it is a DELIVERY zone whose
    -- tariff resolves to zero.
    zone_role varchar(16) NOT NULL,
    code varchar(32) NOT NULL,
    display_name_ru varchar(200) NOT NULL,
    display_name_uz varchar(200) NOT NULL,
    display_name_en varchar(200) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_service_zone_role CHECK (zone_role IN ('DELIVERY', 'CATCHMENT')),
    CONSTRAINT ck_service_zone_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    -- Archived, never deleted. A resolution row six weeks old names this zone,
    -- and a deleted row turns that evidence into a dangling id.
    CONSTRAINT ck_service_zone_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT fk_service_zone_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT uq_service_zone_code UNIQUE (tenant_id, brand_id, code),
    CONSTRAINT uq_service_zone_tenant_identity UNIQUE (tenant_id, id),
    CONSTRAINT uq_service_zone_brand_identity UNIQUE (tenant_id, brand_id, id),
    -- Carries the role into the version table's foreign key, so the check that a
    -- CATCHMENT zone holds no tariff can be written as a constraint rather than
    -- as a rule some service is trusted to apply.
    CONSTRAINT uq_service_zone_role_identity UNIQUE (tenant_id, id, zone_role)
);

CREATE INDEX ix_service_zones_brand ON fulfillment.service_zones (tenant_id, brand_id, zone_role);

-- A version is what a quote pins. Editing geometry, priority, tariff binding or
-- threshold creates a new one and never mutates the active row, because a payout
-- dispute asks whether *that* address was inside *that* zone and today's polygon
-- cannot answer it.
CREATE TABLE fulfillment.service_zone_versions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    zone_id uuid NOT NULL,
    -- Denormalized from the parent through the composite foreign key below, so
    -- the role is available to this table's own CHECK constraints.
    zone_role varchar(16) NOT NULL,
    version integer NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',

    -- Geography rather than geometry: distances and areas come back in metres on
    -- the spheroid without anyone choosing a projection, and choosing the wrong
    -- projection for Uzbekistan is a mistake that shows up as a fee that is
    -- plausible and wrong. A circle is authored as a circle and stored buffered
    -- into a polygon, so exactly one predicate ever answers "is this point
    -- inside".
    area geography(MultiPolygon, 4326) NOT NULL,
    -- What the operator drew, kept beside what the database evaluates. The
    -- alternative is an editor that turns every circle into an unmovable polygon
    -- on first save: the centre and radius are gone, and nudging the radius means
    -- dragging sixty-four vertices.
    authoring_shape jsonb NOT NULL,
    -- Set when the shape is a circle drawn around a branch. Recorded because the
    -- legacy import derives the circle from a branch coordinate, and a zone whose
    -- origin later moves has to be findable.
    origin_location_id uuid,
    region_id uuid,

    -- Overlap is legal — a premium inner-city zone inside a wider city zone is
    -- normal — so it is ranked, not forbidden.
    priority integer NOT NULL DEFAULT 0,
    -- Stored rather than computed per query. It is the second rank key, it never
    -- changes for a frozen version, and ST_Area on a multipolygon in the ranking
    -- of every quote is work with a known answer.
    area_sq_meters double precision NOT NULL,

    currency char(3) NOT NULL,
    -- The zone's rate table. Points at the tariff's lineage and not at one of its
    -- versions: a tariff edit must not force a new version of every zone bound to
    -- it, and the resolution row records which tariff version actually applied.
    delivery_tariff_id uuid,
    free_delivery_from_minor bigint,
    min_basket_minor bigint,

    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    activated_by uuid,
    activated_at timestamptz,
    retired_at timestamptz,

    CONSTRAINT ck_zone_version_role CHECK (zone_role IN ('DELIVERY', 'CATCHMENT')),
    CONSTRAINT ck_zone_version_status CHECK (
        status IN ('DRAFT', 'ACTIVE', 'RETIRED', 'DISCARDED')),
    CONSTRAINT ck_zone_version_number CHECK (version >= 1),
    CONSTRAINT ck_zone_version_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_zone_version_area CHECK (area_sq_meters > 0),
    CONSTRAINT ck_zone_version_amounts CHECK (
        (free_delivery_from_minor IS NULL OR free_delivery_from_minor >= 0)
        AND (min_basket_minor IS NULL OR min_basket_minor >= 0)
    ),
    -- A CATCHMENT zone decides candidacy, never price. Letting one carry a tariff
    -- would give the resolver two rate tables for one address with nothing saying
    -- which wins, which is the ambiguity this whole ADR is about.
    CONSTRAINT ck_zone_version_catchment_is_not_priced CHECK (
        zone_role = 'DELIVERY'
        OR (delivery_tariff_id IS NULL
            AND free_delivery_from_minor IS NULL
            AND min_basket_minor IS NULL)
    ),
    -- Who activated it and when move together. Written as an equivalence so
    -- neither half can drift; the "(a IS NULL AND b IS NULL) OR (...)" form that
    -- shipped here once leaves a three-valued-logic hole a half-populated row
    -- walks straight through.
    CONSTRAINT ck_zone_version_activation_pairing CHECK (
        (activated_at IS NULL) = (activated_by IS NULL)
    ),
    -- A version that has never been activated cannot be live or retired, and one
    -- that has been must carry the fact. Both directions, for the same reason.
    CONSTRAINT ck_zone_version_activation_agrees CHECK (
        (status IN ('ACTIVE', 'RETIRED')) = (activated_at IS NOT NULL)
    ),
    CONSTRAINT ck_zone_version_retirement_agrees CHECK (
        (status = 'RETIRED') = (retired_at IS NOT NULL)
    ),
    CONSTRAINT fk_zone_version_zone FOREIGN KEY (tenant_id, zone_id, zone_role)
        REFERENCES fulfillment.service_zones (tenant_id, id, zone_role),
    CONSTRAINT fk_zone_version_region FOREIGN KEY (region_id)
        REFERENCES fulfillment.regions (id),
    CONSTRAINT fk_zone_version_origin_location FOREIGN KEY (tenant_id, origin_location_id)
        REFERENCES tenant.locations (tenant_id, id),
    CONSTRAINT uq_zone_version_number UNIQUE (zone_id, version),
    -- The key the resolver joins on, and the one that carries tenant ancestry into
    -- the resolution evidence.
    CONSTRAINT uq_zone_version_identity UNIQUE (tenant_id, zone_id, version)
);

-- One live version per zone, the pattern tenant.policy_current already uses. A
-- second ACTIVE row would make containment depend on which one the planner
-- emitted first.
CREATE UNIQUE INDEX uq_zone_version_current
    ON fulfillment.service_zone_versions (zone_id)
    WHERE status = 'ACTIVE';

-- The checkout-path index. Partial on ACTIVE because a draft or retired polygon
-- is never a containment candidate, and those rows accumulate for ever.
CREATE INDEX ix_zone_version_area
    ON fulfillment.service_zone_versions USING gist (area)
    WHERE status = 'ACTIVE';

COMMENT ON COLUMN fulfillment.service_zone_versions.authoring_shape IS
    'ADR 0037. What the operator drew — a circle with a centre and a radius, or the polygon rings — kept beside the buffered geometry so the editor round-trips a circle instead of turning it into an unmovable polygon on first save.';
COMMENT ON COLUMN fulfillment.service_zone_versions.area_sq_meters IS
    'ADR 0037. The second key of the deterministic overlap ranking, stored rather than computed per quote because it cannot change for a frozen version.';
COMMENT ON COLUMN fulfillment.service_zone_versions.delivery_tariff_id IS
    'ADR 0037. The tariff lineage, not one of its versions: a rate edit must not force a new version of every zone bound to it. Which tariff version actually applied is recorded on the resolution row.';

-- Which branches this zone applies to. A zone with no binding covers nothing,
-- which is the safe direction: a half-configured zone is visibly inert rather
-- than quietly serving the whole brand.
CREATE TABLE fulfillment.zone_location_bindings (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    zone_id uuid NOT NULL,
    location_id uuid NOT NULL,
    valid_from timestamptz NOT NULL DEFAULT now(),
    valid_until timestamptz,

    PRIMARY KEY (zone_id, location_id, valid_from),
    CONSTRAINT ck_zone_binding_window CHECK (valid_until IS NULL OR valid_until > valid_from),
    -- Both sides carry brand ancestry, which is what makes a cross-brand or
    -- cross-tenant binding fail at the database instead of in whichever service
    -- happened to write the row. ADR 0037's testing list names exactly this.
    CONSTRAINT fk_zone_binding_zone FOREIGN KEY (tenant_id, brand_id, zone_id)
        REFERENCES fulfillment.service_zones (tenant_id, brand_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_zone_binding_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id)
);

CREATE INDEX ix_zone_bindings_location
    ON fulfillment.zone_location_bindings (tenant_id, location_id);

-- ---------------------------------------------------------------------------
-- Tariffs
-- ---------------------------------------------------------------------------
--
-- ADR 0037 sketches one `delivery_tariffs` table with a version column and bands
-- keyed by (tariff_id, sequence). That sketch cannot meet the ADR's own exit
-- criterion — reconstructing a months-old fee "without executing today's rates" —
-- because editing a band would rewrite the rows a past resolution points at. So
-- the tariff is split the same way the zone is: a lineage row that names the rate
-- table, and immutable versions carrying the numbers. Bands and time rules hang
-- off a version, never off the lineage.
CREATE TABLE fulfillment.delivery_tariffs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    code varchar(32) NOT NULL,
    name varchar(200) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    -- The last rung of ADR 0037's step 4, and the reason it exists: a brand that
    -- has drawn zones but has not yet priced every one of them still needs an
    -- answer, and the alternative to a default is a quote refused for a
    -- configuration gap the operator cannot see. It is deliberately not a
    -- fallback to zero — free delivery and a missing rate table must never look
    -- alike, so an unset default gives NO_TARIFF and says so.
    is_brand_default boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_delivery_tariff_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_delivery_tariff_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT fk_delivery_tariff_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT uq_delivery_tariff_code UNIQUE (tenant_id, brand_id, code),
    CONSTRAINT uq_delivery_tariff_tenant_identity UNIQUE (tenant_id, id),
    CONSTRAINT uq_delivery_tariff_brand_identity UNIQUE (tenant_id, brand_id, id)
);

-- Exactly one default per brand, and only among live tariffs. Two would make the
-- last rung of the precedence chain depend on row order, which is the same defect
-- as two equally ranked zones and has the same cure.
CREATE UNIQUE INDEX uq_delivery_tariff_brand_default
    ON fulfillment.delivery_tariffs (tenant_id, brand_id)
    WHERE is_brand_default AND status = 'ACTIVE';

-- The middle rung of step 4: this branch's own rate table, outranked by a zone's
-- and outranking the brand default. Time-bounded like every other binding here,
-- because "we changed our delivery prices in March" has to be answerable without
-- a second table of history.
CREATE TABLE fulfillment.location_tariff_bindings (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    tariff_id uuid NOT NULL,
    valid_from timestamptz NOT NULL DEFAULT now(),
    valid_until timestamptz,

    PRIMARY KEY (location_id, valid_from),
    CONSTRAINT ck_location_tariff_window CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT fk_location_tariff_tariff FOREIGN KEY (tenant_id, brand_id, tariff_id)
        REFERENCES fulfillment.delivery_tariffs (tenant_id, brand_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_location_tariff_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id)
);

CREATE TABLE fulfillment.delivery_tariff_versions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    tariff_id uuid NOT NULL,
    version integer NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    currency char(3) NOT NULL,

    -- TARIFF is the default and the recommendation. PROVIDER_QUOTE makes the
    -- price depend on Yandex surge at the instant of checkout: two customers in
    -- one building pay different fees, the tenant cannot publish a delivery
    -- price, and ADR 0018's promise that a quote is reproducible from its context
    -- hash stops being true. It exists for a tenant that asked for pass-through
    -- in writing, and it is clamped by the same min and max as a tariff fee.
    fee_source varchar(16) NOT NULL DEFAULT 'TARIFF',

    -- RADIUS is haversine from the branch and needs no provider. ROAD needs a
    -- routing binding and is refused at activation without one.
    distance_mode varchar(8) NOT NULL DEFAULT 'RADIUS',
    -- What straight-line distance is multiplied by when routing is unavailable
    -- and RADIUS_FALLBACK applies. A platform default that must be calibrated per
    -- city; Qoida has not measured it, which is why it is a column with a stated
    -- value rather than a constant nobody can see.
    road_factor_basis_points integer NOT NULL DEFAULT 13000,
    routing_provider_installation_id uuid,

    max_distance_meters integer NOT NULL,
    min_fee_minor bigint NOT NULL DEFAULT 0,
    max_fee_minor bigint,

    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    activated_by uuid,
    activated_at timestamptz,
    retired_at timestamptz,

    CONSTRAINT ck_tariff_version_status CHECK (
        status IN ('DRAFT', 'ACTIVE', 'RETIRED', 'DISCARDED')),
    CONSTRAINT ck_tariff_version_number CHECK (version >= 1),
    CONSTRAINT ck_tariff_version_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_tariff_version_fee_source CHECK (fee_source IN ('TARIFF', 'PROVIDER_QUOTE')),
    CONSTRAINT ck_tariff_version_distance_mode CHECK (distance_mode IN ('RADIUS', 'ROAD')),
    -- A detour factor below 1.0 claims the road is shorter than the straight
    -- line, which is not a calibration anyone meant and would under-charge every
    -- fallback quote.
    CONSTRAINT ck_tariff_version_road_factor CHECK (road_factor_basis_points >= 10000),
    CONSTRAINT ck_tariff_version_max_distance CHECK (max_distance_meters > 0),
    CONSTRAINT ck_tariff_version_fee_bounds CHECK (
        min_fee_minor >= 0 AND (max_fee_minor IS NULL OR max_fee_minor >= min_fee_minor)
    ),
    -- ADR 0037: ROAD is refused at activation if no routing binding exists. As a
    -- constraint rather than a service rule, so a draft may be authored in ROAD
    -- mode while the binding is still being installed and simply cannot go live
    -- until it is.
    CONSTRAINT ck_tariff_version_road_needs_routing CHECK (
        status <> 'ACTIVE' OR distance_mode <> 'ROAD'
        OR routing_provider_installation_id IS NOT NULL
    ),
    CONSTRAINT ck_tariff_version_activation_pairing CHECK (
        (activated_at IS NULL) = (activated_by IS NULL)
    ),
    CONSTRAINT ck_tariff_version_activation_agrees CHECK (
        (status IN ('ACTIVE', 'RETIRED')) = (activated_at IS NOT NULL)
    ),
    CONSTRAINT ck_tariff_version_retirement_agrees CHECK (
        (status = 'RETIRED') = (retired_at IS NOT NULL)
    ),
    CONSTRAINT fk_tariff_version_tariff FOREIGN KEY (tenant_id, tariff_id)
        REFERENCES fulfillment.delivery_tariffs (tenant_id, id),
    CONSTRAINT fk_tariff_version_routing FOREIGN KEY (tenant_id, routing_provider_installation_id)
        REFERENCES integration.installations (tenant_id, id),
    CONSTRAINT uq_tariff_version_number UNIQUE (tariff_id, version),
    CONSTRAINT uq_tariff_version_identity UNIQUE (tenant_id, tariff_id, version)
);

CREATE UNIQUE INDEX uq_tariff_version_current
    ON fulfillment.delivery_tariff_versions (tariff_id)
    WHERE status = 'ACTIVE';

COMMENT ON COLUMN fulfillment.delivery_tariff_versions.road_factor_basis_points IS
    'ADR 0037. Straight-line distance is multiplied by this when routing times out, and the resolution records distance_source = RADIUS_FALLBACK. 13000 is a platform guess that must be calibrated per city; it is a column so that guess is visible and correctable.';
COMMENT ON COLUMN fulfillment.delivery_tariff_versions.fee_source IS
    'ADR 0037. PROVIDER_QUOTE charges the live provider price clamped by the same min and max; the gap between it and the provider invoice is an ADR 0014 DELIVERY_COST_SUBSIDY and never a change to the fee.';

-- Bands must tile [0, max_distance_meters) with no gap and no overlap. A gap is
-- what makes 4,700 m unpriceable while 4,600 m and 4,800 m both price fine, and
-- nobody finds that until a customer reports it.
--
-- Overlap is enforced here, in the database, by the exclusion constraint below.
-- Gap-freeness is not expressible as a constraint — it is a property of the whole
-- set against the version's maximum — so it is checked at activation, and the
-- split is deliberate rather than an oversight: the half that can be enforced
-- always is.
CREATE TABLE fulfillment.delivery_tariff_bands (
    tenant_id uuid NOT NULL,
    tariff_id uuid NOT NULL,
    tariff_version integer NOT NULL,
    sequence integer NOT NULL,
    from_meters integer NOT NULL,
    to_meters integer NOT NULL,
    base_minor bigint NOT NULL,
    per_km_minor bigint NOT NULL DEFAULT 0,

    PRIMARY KEY (tariff_id, tariff_version, sequence),
    CONSTRAINT ck_tariff_band_range CHECK (from_meters >= 0 AND to_meters > from_meters),
    CONSTRAINT ck_tariff_band_amounts CHECK (base_minor >= 0 AND per_km_minor >= 0),
    CONSTRAINT fk_tariff_band_version FOREIGN KEY (tenant_id, tariff_id, tariff_version)
        REFERENCES fulfillment.delivery_tariff_versions (tenant_id, tariff_id, version)
        ON DELETE CASCADE,
    -- Half-open ranges, matching the resolver's [from, to) band test exactly. Two
    -- bands claiming 4,000 m would let the fee depend on which row the planner
    -- reached first.
    CONSTRAINT ex_tariff_band_no_overlap EXCLUDE USING gist (
        tariff_id WITH =, tariff_version WITH =,
        int4range(from_meters, to_meters, '[)') WITH &&
    )
);

-- Highest-priority matching rule, or none. The multiplier applies before the
-- surcharge and rounding happens once — both stated in ADR 0037 because two
-- implementations that disagree on either produce two defensible fees for one
-- address.
CREATE TABLE fulfillment.delivery_tariff_time_rules (
    tenant_id uuid NOT NULL,
    tariff_id uuid NOT NULL,
    tariff_version integer NOT NULL,
    sequence integer NOT NULL,
    priority integer NOT NULL DEFAULT 0,
    -- Bit 0 is Monday, matching java.time.DayOfWeek's 1..7 shifted down by one.
    -- A mask rather than seven rows: "weekday evenings" is one rule an operator
    -- edits once.
    day_of_week_mask smallint NOT NULL,
    -- Local wall-clock in the location's IANA zone, resolved at quote creation.
    -- Deliberately `time` and not `timestamptz`, for V0020's reason: a recurring
    -- peak window is a rule about local time, and storing it as an instant makes
    -- it drift the moment a zone changes.
    local_from_time time NOT NULL,
    local_to_time time NOT NULL,
    multiplier_basis_points integer NOT NULL DEFAULT 10000,
    surcharge_minor bigint NOT NULL DEFAULT 0,

    PRIMARY KEY (tariff_id, tariff_version, sequence),
    CONSTRAINT ck_time_rule_mask CHECK (day_of_week_mask BETWEEN 1 AND 127),
    -- A window is not allowed to wrap midnight. "22:00 to 02:00" as a single row
    -- needs the evaluator to special-case from > to, and the special case is
    -- silently absent in every implementation that forgets it. Two rows say the
    -- same thing and cannot be got wrong.
    CONSTRAINT ck_time_rule_window CHECK (local_to_time > local_from_time),
    CONSTRAINT ck_time_rule_multiplier CHECK (multiplier_basis_points > 0),
    CONSTRAINT ck_time_rule_surcharge CHECK (surcharge_minor >= 0),
    CONSTRAINT fk_time_rule_version FOREIGN KEY (tenant_id, tariff_id, tariff_version)
        REFERENCES fulfillment.delivery_tariff_versions (tenant_id, tariff_id, version)
        ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- Resolution evidence
-- ---------------------------------------------------------------------------
--
-- The delivery-side twin of pricing.quote_adjustments: normalized columns for
-- reconciliation, JSONB beside them as evidence, never JSONB as the model.
-- Refusals are recorded as fully as successes, because "why did this address get
-- no delivery option" is asked at least as often as "why was it this much".
--
-- ADR 0029: no address text and no coordinates. The reconciliation and heat-map
-- consumers need the zone, not the doorstep, and a coordinate is a direct pointer
-- at where one identifiable person lives.
CREATE TABLE fulfillment.delivery_fee_resolutions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    -- Null for a serviceability check or a control-plane simulation, both of
    -- which resolve without a quote ever existing. No foreign key even when it is
    -- set: the fee is an input to the quote, so this row is written before
    -- pricing.quotes has one to point at, and a constraint would order the two
    -- writes backwards.
    quote_id uuid,
    location_id uuid NOT NULL,
    -- Bumped when the resolution order itself changes, so an old row is never
    -- re-explained by new rules.
    resolution_version integer NOT NULL,
    outcome varchar(32) NOT NULL,
    reason_code varchar(48),

    zone_id uuid,
    zone_version integer,
    tariff_id uuid,
    tariff_version integer,
    band_sequence integer,
    time_rule_sequence integer,

    distance_meters integer,
    distance_mode varchar(8),
    distance_source varchar(24),
    routing_provider varchar(64),

    provider_quote_minor bigint,
    computed_fee_minor bigint,
    final_fee_minor bigint,
    currency char(3) NOT NULL,

    -- The candidates that contained the point and lost the ranking. Recorded
    -- because "the customer says the other zone's price applies" is answerable
    -- only if the alternatives are known.
    losing_zone_ids uuid[] NOT NULL DEFAULT '{}',
    evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_fee_resolution_outcome CHECK (outcome IN (
        -- A fee was computed and stands.
        'RESOLVED',
        -- ADR 0040 pricing authority is EXTERNAL. Checked before a zone or a
        -- tariff is looked up, so no tariff configuration can reintroduce a
        -- Qoida-computed fee on top of the one the aggregator already collected.
        'EXTERNALLY_PRICED',
        -- The branch has no coordinate, so nothing can be measured from it. A
        -- distinct outcome and not OUT_OF_ZONE: the address is fine and the
        -- configuration is not, and telling an operator "no zones covered this
        -- address" would send them to redraw a polygon that is not the problem.
        'LOCATION_NOT_LOCATED',
        -- Outside every ACTIVE DELIVERY zone bound to the chosen location. Never
        -- re-homed to a location that does cover it.
        'OUT_OF_ZONE',
        -- Inside a delivery zone but outside the location's catchment, which is
        -- what stops one branch accepting an order from the far side of the city
        -- through a shared city-wide zone.
        'OUTSIDE_CATCHMENT',
        -- No tariff on the zone, the location, or the brand. Refused rather than
        -- priced at zero: a missing rate table and free delivery must never look
        -- alike.
        'NO_TARIFF',
        -- Inside the polygon and past the tariff's reach. A generously drawn
        -- district always contains a house no courier will serve at the district
        -- price.
        'BEYOND_MAX_DISTANCE'
    )),
    CONSTRAINT ck_fee_resolution_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_fee_resolution_version CHECK (resolution_version >= 1),
    -- Every pairing below is an equivalence, so no row can be half-populated on
    -- either side. The "(a IS NULL AND b IS NULL) OR (...)" form leaves a
    -- three-valued-logic hole, and that exact bug shipped here once.
    CONSTRAINT ck_fee_resolution_fee_agrees CHECK (
        (outcome = 'RESOLVED') = (final_fee_minor IS NOT NULL)
    ),
    CONSTRAINT ck_fee_resolution_zone_pairing CHECK (
        (zone_id IS NULL) = (zone_version IS NULL)
    ),
    CONSTRAINT ck_fee_resolution_tariff_pairing CHECK (
        (tariff_id IS NULL) = (tariff_version IS NULL)
    ),
    CONSTRAINT ck_fee_resolution_distance_pairing CHECK (
        (distance_meters IS NULL) = (distance_source IS NULL)
        AND (distance_meters IS NULL) = (distance_mode IS NULL)
    ),
    CONSTRAINT ck_fee_resolution_distance_source CHECK (
        distance_source IS NULL
        OR distance_source IN ('RADIUS', 'ROAD', 'RADIUS_FALLBACK')
    ),
    CONSTRAINT ck_fee_resolution_distance_mode CHECK (
        distance_mode IS NULL OR distance_mode IN ('RADIUS', 'ROAD')
    ),
    -- A resolved fee must be re-derivable. Without every one of these the row
    -- records an amount nobody can defend to a tenant.
    CONSTRAINT ck_fee_resolution_resolved_is_explained CHECK (
        outcome <> 'RESOLVED'
        OR (zone_id IS NOT NULL AND tariff_id IS NOT NULL
            AND distance_meters IS NOT NULL AND computed_fee_minor IS NOT NULL)
    ),
    CONSTRAINT ck_fee_resolution_amounts CHECK (
        (provider_quote_minor IS NULL OR provider_quote_minor >= 0)
        AND (computed_fee_minor IS NULL OR computed_fee_minor >= 0)
        AND (final_fee_minor IS NULL OR final_fee_minor >= 0)
    ),
    CONSTRAINT ck_fee_resolution_distance CHECK (
        distance_meters IS NULL OR distance_meters >= 0
    ),
    CONSTRAINT ck_fee_resolution_evidence CHECK (jsonb_typeof(evidence) = 'object'),
    CONSTRAINT fk_fee_resolution_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT fk_fee_resolution_location FOREIGN KEY (tenant_id, location_id)
        REFERENCES tenant.locations (tenant_id, id)
);

-- "Show me the evidence for this quote" is the operations console's whole reason
-- for this table.
CREATE INDEX ix_fee_resolutions_quote
    ON fulfillment.delivery_fee_resolutions (tenant_id, quote_id)
    WHERE quote_id IS NOT NULL;

-- "Which addresses are we turning away, and from where" — the refusal report that
-- tells a tenant where to draw the next zone.
CREATE INDEX ix_fee_resolutions_refusals
    ON fulfillment.delivery_fee_resolutions (tenant_id, location_id, created_at)
    WHERE outcome <> 'RESOLVED';

COMMENT ON TABLE fulfillment.delivery_fee_resolutions IS
    'ADR 0037. Why one address was charged one fee, with the zone version, tariff version, band, time rule, distance and distance source that decided it. Carries no address text and no coordinates (ADR 0029).';

-- ---------------------------------------------------------------------------
-- The fee is a quote line
-- ---------------------------------------------------------------------------
--
-- ADR 0037 is explicit that the delivery charge is a line and not an adjustment,
-- and the reason is fiscal rather than aesthetic: this market requires the
-- delivery charge on its own receipt line, which means it needs a classification
-- code and a tax share of its own. An adjustment has neither.
--
-- pricing.quote_lines was built in V0019 assuming every line is a catalogue
-- variant, so source_variant_id is NOT NULL. That is relaxed here, paired with a
-- line type, so the two kinds of line cannot be confused: a fee line has no
-- variant and an item line must have one, stated as an equivalence in both
-- directions.
ALTER TABLE pricing.quote_lines
    ADD COLUMN line_type varchar(16) NOT NULL DEFAULT 'ITEM',
    ADD COLUMN mxik_code varchar(32),
    ALTER COLUMN source_variant_id DROP NOT NULL;

ALTER TABLE pricing.quote_lines
    ADD CONSTRAINT ck_quote_line_type CHECK (line_type IN ('ITEM', 'DELIVERY_FEE')),
    ADD CONSTRAINT ck_quote_line_variant_agrees CHECK (
        (line_type = 'ITEM') = (source_variant_id IS NOT NULL)
    ),
    ADD CONSTRAINT ck_quote_line_mxik_not_blank CHECK (
        mxik_code IS NULL OR length(btrim(mxik_code)) > 0
    );

COMMENT ON COLUMN pricing.quote_lines.line_type IS
    'ADR 0037. A DELIVERY_FEE line carries no catalogue variant. The delivery charge is a line rather than an adjustment because this market requires it on its own receipt line, with its own classification and tax share.';
COMMENT ON COLUMN pricing.quote_lines.mxik_code IS
    'ADR 0038 (Proposed), placeholder. ИКПУ/MXIK for this line. Null on every line today: item lines inherit from the catalogue at fiscalization, and the classification of a delivery charge is an open finance and legal input on ADR 0037.';

-- ADR 0018 reserved stage 6 for delivery benefits and the vocabulary was never
-- extended. Both values arrive with the fee they act on: a waiver that cannot be
-- named would have to be expressed as a fee computed at zero, and a zero with no
-- adjustment cannot be told apart from a broken tariff lookup.
ALTER TABLE pricing.quote_adjustments
    DROP CONSTRAINT ck_adjustment_type;

ALTER TABLE pricing.quote_adjustments
    ADD CONSTRAINT ck_adjustment_type CHECK (
        adjustment_type IN ('BASE_PRICE', 'MODIFIER', 'ITEM_DISCOUNT', 'ORDER_DISCOUNT',
                            'FEE', 'TAX', 'ROUNDING',
                            -- ADR 0037 stage 8: the zone's free_delivery_from_minor
                            -- threshold, compared against the post-discount goods
                            -- subtotal so adding the fee cannot cross the threshold
                            -- that removes it.
                            'DELIVERY_FEE_WAIVER',
                            -- ADR 0037 stage 9: a promotion's free-delivery grant,
                            -- capped at whatever fee the waiver left, so two
                            -- waivers never sum the fee below zero.
                            'DELIVERY_FEE_BENEFIT')
    );

GRANT USAGE ON SCHEMA fulfillment TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.regions TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.service_zones TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.service_zone_versions TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.zone_location_bindings TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.delivery_tariffs TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.location_tariff_bindings TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.delivery_tariff_versions TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.delivery_tariff_bands TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.delivery_tariff_time_rules TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.delivery_fee_resolutions TO qoida_application;
