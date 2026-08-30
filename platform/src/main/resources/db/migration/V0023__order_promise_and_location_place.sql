-- Two gaps that block the operations console and ADR 0037, both of them the same
-- kind of gap: a fact the business speaks about constantly that the schema has
-- no column for.
--
-- 1. `ordering.orders` cannot say when the food was promised, so "late" — the
--    single most-used word in a restaurant during service — is not expressible.
-- 2. `tenant.locations` cannot say where the branch is, so a delivery zone has
--    no origin, a courier has nothing to navigate to, and nobody can be phoned.

-- ---------------------------------------------------------------------------
-- 1. The order promise
-- ---------------------------------------------------------------------------
--
-- ADR 0036 says the promised time is assembled from a preparation band, an item
-- override and a travel component, and calls it "the most complained-about
-- number in food delivery". A number that contested has to be stored, not
-- recomputed, for three reasons:
--
--   * The inputs move. Bands are edited, items are retimed, and a promise
--     recomputed next Tuesday from today's bands would quietly rewrite what a
--     customer was told on Friday — including in the report that measures
--     whether we kept it.
--   * It is evidence. "You said 40 minutes" is a claim the platform must be able
--     to answer from the order row alone, the same way `pricing_context_hash`
--     lets it answer "you said 62 000 som".
--   * Recomputation cannot be made deterministic anyway. The band resolver keys
--     on a wall-clock instant; there is no instant to feed it later that
--     reproduces the original answer.
--
-- Lateness is deliberately NOT a column. It is `promised_at < now()` on an order
-- that has not yet been handed over — a predicate over two facts, both of which
-- are already here. A stored `late` flag would need a job to maintain it, would
-- be wrong between runs of that job, would need clearing when a promise is
-- amended, and would give two different answers to the same question depending
-- on whether the reader trusted the flag or the timestamps. Every list in the
-- operations console derives it instead.

ALTER TABLE ordering.orders
    -- When the food was promised. Absolute, not a duration, because a duration
    -- is only meaningful against the instant it was quoted from, and that
    -- instant is `created_at` only until the first amendment.
    ADD COLUMN promised_at timestamptz,
    ADD COLUMN promise_basis varchar(32) NOT NULL DEFAULT 'NOT_PROMISED',
    -- The components, kept separately so the promise can be explained rather
    -- than only asserted. A support engineer asked "why 55 minutes?" can answer
    -- from the row: 35 in the kitchen, 20 on the road.
    ADD COLUMN promise_prep_minutes integer,
    ADD COLUMN promise_travel_minutes integer;

COMMENT ON COLUMN ordering.orders.promised_at IS
    'ADR 0036. When the food was promised, decided once at checkout and never recomputed. Lateness is derived from this and the status; there is deliberately no late column.';
COMMENT ON COLUMN ordering.orders.promise_basis IS
    'ADR 0036. Which source governed the promise, so a promise can be explained and a later backfill can find the ones made without a travel component.';
COMMENT ON COLUMN ordering.orders.promise_travel_minutes IS
    'ADR 0037. The road component. NULL on a DELIVERY order means travel was not modelled at all — the zone model was not built when this order was taken — and not that travel was zero.';

ALTER TABLE ordering.orders
    ADD CONSTRAINT ck_order_promise_basis CHECK (promise_basis IN (
        -- Nothing to go on: no band covered the checkout instant and no default
        -- was configured. Honest, and visible, rather than a fabricated number.
        'NOT_PROMISED',
        -- A tenant.preparation_bands row covered the instant and governed.
        'PREPARATION_BAND',
        -- A dish's catalog.location_offerings.preparation_duration_override
        -- exceeded the band. The kitchen cooks in parallel, so an order is ready
        -- when its slowest item is, which makes this a max and not a sum.
        'ITEM_OVERRIDE',
        -- No band covered the instant. The platform fallback applied, which is a
        -- configuration gap worth reporting on rather than hiding.
        'PLATFORM_DEFAULT',
        -- ADR 0047. The customer chose a slot, so no duration produced this.
        -- Not yet written by anything.
        'SCHEDULED_SLOT',
        -- ADR 0039. A human set it, which outranks every derivation above.
        -- Not yet written by anything.
        'OPERATOR_OVERRIDE'
    ));

-- A basis without a time, or a time without a basis, is a promise nobody can
-- account for. Written as an equivalence so neither direction can drift.
ALTER TABLE ordering.orders
    ADD CONSTRAINT ck_order_promise_pairing CHECK (
        (promise_basis = 'NOT_PROMISED') = (promised_at IS NULL)
    );

-- Which components each basis must and must not carry. Every branch compares
-- with IS NULL rather than with =, so no operand can be NULL and no row can slip
-- through on three-valued logic — the failure mode that let half a coordinate
-- into customer.addresses before V0021 caught it.
ALTER TABLE ordering.orders
    ADD CONSTRAINT ck_order_promise_components CHECK (
        CASE promise_basis
            WHEN 'NOT_PROMISED' THEN
                promise_prep_minutes IS NULL AND promise_travel_minutes IS NULL
            -- A chosen or dictated time has no derivation to decompose.
            WHEN 'SCHEDULED_SLOT' THEN promise_prep_minutes IS NULL
            WHEN 'OPERATOR_OVERRIDE' THEN promise_prep_minutes IS NULL
            ELSE promise_prep_minutes IS NOT NULL
        END
    );

ALTER TABLE ordering.orders
    ADD CONSTRAINT ck_order_promise_minutes CHECK (
        (promise_prep_minutes IS NULL
            OR (promise_prep_minutes >= 0 AND promise_prep_minutes <= 1440))
        AND (promise_travel_minutes IS NULL
            OR (promise_travel_minutes >= 0 AND promise_travel_minutes <= 1440))
    );

-- The operations board's first query: what is open here, soonest promise first,
-- so the overdue end of the list is the cheap end to read. Partial on the open
-- statuses because a delivered order is never in this list, and those are the
-- rows that accumulate for ever.
--
-- The predicate is exactly the non-terminal half of OrderStatus, including
-- PAYMENT_AUTHORIZING: an order stuck in authorization past its promise is
-- precisely the one an operator has to be shown. A test asserts this set and
-- OrderStatus.terminal() have not drifted apart.
--
-- The lateness predicate itself is not indexable — it contains now() — but it
-- does not need to be: ordering by promised_at puts every late row at the head
-- of the scan.
CREATE INDEX ix_orders_open_promise
    ON ordering.orders (location_id, promised_at)
    WHERE status IN ('RECEIVED', 'PAYMENT_AUTHORIZING', 'AWAITING_APPROVAL', 'CONFIRMED',
                     'PREPARING', 'READY', 'FULFILLING');

-- ---------------------------------------------------------------------------
-- 2. Where the branch actually is
-- ---------------------------------------------------------------------------
--
-- tenant.locations has carried a display name and a timezone since V0003 and
-- nothing else physical. That blocks more than it looks like: ADR 0037's zones
-- are polygons around an origin this table cannot supply, a courier assigned to
-- a pickup has no point to navigate to, and an operator with a stuck order has
-- no number to ring.
--
-- All of it is in clear, and the contrast with customer.addresses is deliberate
-- rather than an inconsistency. A customer's address is where one identifiable
-- person sleeps, so ADR 0029 puts it inside an encrypted document and leaves
-- only the coordinate out. A restaurant's address is published by the merchant
-- on purpose — it is printed on the receipt, shown in the storefront, and handed
-- to every courier. Encrypting it would put a decrypt on the hot path of every
-- dispatch for information the merchant is actively advertising.

ALTER TABLE tenant.locations
    ADD COLUMN latitude double precision,
    ADD COLUMN longitude double precision,
    ADD COLUMN coordinate_source varchar(24) NOT NULL DEFAULT 'NOT_GEOCODED',
    -- Street and building. One line rather than parsed parts: no query filters
    -- on a house number, and Uzbek addresses do not decompose the way a
    -- street/number split assumes.
    ADD COLUMN address_line varchar(200),
    -- Район / tuman, then город / shahar. These two are separate columns because
    -- reporting genuinely groups by them — "how did the Chilonzor branches do" is
    -- a question a multi-branch tenant asks weekly.
    ADD COLUMN district varchar(120),
    ADD COLUMN city varchar(120),
    -- Ориентир. Not decoration: a large share of addresses in this market are
    -- given as a landmark, and a courier who cannot find the service entrance of
    -- a branch inside a mall loses ten minutes of somebody's promise.
    ADD COLUMN landmark varchar(200),
    ADD COLUMN contact_phone varchar(32);

COMMENT ON COLUMN tenant.locations.contact_phone IS
    'The branch line, in clear. E.164. Frequently a manager''s mobile in practice, but it is the number the merchant publishes for customers and couriers to call, and dispatch needs it without a decrypt.';
COMMENT ON COLUMN tenant.locations.landmark IS
    'Ориентир. How the address is actually given here when a street and number will not find the door.';

-- Same vocabulary as customer.addresses, minus two values, and both omissions
-- are the point.
--
-- There is no LANDMARK_ONLY. A customer's address may legitimately have no
-- point — a mahalla house described by its landmark is a finished state that
-- dispatch handles by calling. A branch is not that: it has a door on a map that
-- a human can pin, and a branch without a point cannot originate an ADR 0037
-- zone or be sourced against by distance. Allowing a "finished, no point" state
-- here would let a location opt out of being locatable and present as configured
-- while quietly excluding itself from delivery.
--
-- There is no CUSTOMER_PIN, since no customer places a restaurant, and no
-- LEGACY_UNSOURCED, since these columns are new and no row can predate them.
ALTER TABLE tenant.locations
    ADD CONSTRAINT ck_locations_coordinate_source CHECK (
        coordinate_source IN (
            'NOT_GEOCODED',   -- not attempted, or attempted and failed. Retryable.
            'GEOCODER',       -- resolved by the ADR 0015 geocoding port
            'MERCHANT_PIN',   -- the tenant placed their own pin during onboarding
            'OPERATOR_PIN'    -- Qoida support placed it, usually while on a call
        )
    );

ALTER TABLE tenant.locations
    ADD CONSTRAINT ck_locations_coordinate_source_agrees CHECK (
        (coordinate_source = 'NOT_GEOCODED') = (latitude IS NULL)
    );

ALTER TABLE tenant.locations
    ADD CONSTRAINT ck_locations_coordinates CHECK (
        (latitude IS NULL) = (longitude IS NULL)
        AND (
            latitude IS NULL
            OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
        )
    );

-- E.164, and no wider. A number stored as "71 200 00 00" or "+998 71 200-00-00"
-- cannot be dialled by a client, cannot be compared for equality, and cannot be
-- handed to an SMS provider. Not pinned to +998: a tenant may eventually run a
-- branch outside the country, and a country check is not this table's job.
ALTER TABLE tenant.locations
    ADD CONSTRAINT ck_locations_contact_phone CHECK (
        contact_phone IS NULL OR contact_phone ~ '^\+[1-9][0-9]{7,14}$'
    );

-- Present means populated. An empty string satisfies "the column is set" while
-- addressing nothing, which would let a branch report as fully configured with a
-- blank address on its receipts — the same silent-gap failure the ADR 0038
-- classification columns guard against in V0021.
ALTER TABLE tenant.locations
    ADD CONSTRAINT ck_locations_address_not_blank CHECK (
        (address_line IS NULL OR length(btrim(address_line)) > 0)
        AND (district IS NULL OR length(btrim(district)) > 0)
        AND (city IS NULL OR length(btrim(city)) > 0)
        AND (landmark IS NULL OR length(btrim(landmark)) > 0)
    );

-- Serves the onboarding gap list and the ADR 0037 prerequisite check: which live
-- branches still cannot be placed on a map. Partial, because the located ones
-- are not what anyone is looking for.
CREATE INDEX ix_locations_awaiting_coordinates
    ON tenant.locations (tenant_id)
    WHERE status = 'ACTIVE' AND latitude IS NULL;
