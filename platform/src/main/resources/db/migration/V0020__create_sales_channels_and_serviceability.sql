-- ADR 0036: sales channels and location serviceability.
--
-- Two halves of one question — "what may be sold here, right now, through this
-- route" — and both were previously unowned. `channel` already existed as free
-- text on catalog.publications and as an untyped scope_id on
-- pricing.price_book_assignments, with no table saying what a channel is; and
-- ADR 0016 sketched a `sales_schedule_id` pointing at a table no decision owned,
-- which migration V0016 never created.
--
-- Everything here lives in schema `tenant` (except the per-channel item
-- suppression, which references a catalog variant under ADR 0016's composite
-- brand key and therefore has to live in `catalog`). Catalog, pricing and
-- ordering all read channels; owning them in any one of those three would make
-- that module a dependency of the other two.
--
-- Times in this migration are local wall-clock and are resolved against
-- tenant.locations.timezone by the resolver. They are deliberately `time`
-- rather than `timestamptz`: a weekly opening hour is a rule about local time,
-- and storing it as an instant would make it drift the moment a zone changes.

-- tenant.locations is keyed by (tenant_id, brand_id, id) and by id alone. The
-- channel tables below reference a location without naming its brand — a
-- channel is a tenant-level object and does not belong to a brand — so they need
-- a (tenant_id, id) key to point at. Without it those tables could only carry a
-- bare location_id, and a cross-tenant location id would insert cleanly.
ALTER TABLE tenant.locations
    ADD CONSTRAINT uq_locations_tenant_id UNIQUE (tenant_id, id);

-- ------------------------------------------------------------ channel registry

CREATE TABLE tenant.sales_channels (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    -- Tenant-chosen and stable. Publications reference a channel by code (see
    -- the catalog correction at the foot of this file), and an order snapshots
    -- it, so the code is an identifier rather than a label.
    code varchar(32) NOT NULL,
    -- The closed, code-owned set. A tenant registers its third aggregator by
    -- inserting a row, but it can never invent a type, because behaviour keys on
    -- system_type and a type an operator typed would be a behaviour nobody
    -- implemented. The set is owned by ADR 0036 alone: an ADR needing a type this
    -- list does not carry amends this list rather than adding a name of its own.
    -- The failure that prevents is two vocabularies for one concept, where POS
    -- and some later DINE_IN_POS name the same channel, behaviour matches one and
    -- not the other, and every report grouping by type undercounts both halves
    -- without anything failing.
    system_type varchar(16) NOT NULL,
    -- Admin-facing only. A channel name is never shown to a customer, so it is a
    -- single string and not a translated one.
    display_name varchar(200) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    -- "For QR and kiosk, take the hall's prices." Null means "use my own".
    -- Resolved in exactly one hop and never as a chain — see the self-reference
    -- constraint below and JdbcSalesChannelStore.
    price_plane_channel_id uuid,
    -- Seeds ordering.orders.pricing_authority when an order is created on this
    -- channel (ADR 0040). It is a default, not a second enforcement point: once
    -- the order exists, its own column is the only value the pricing path reads.
    -- Two enforcement points disagree silently — flipping this flag would
    -- otherwise retroactively change how already-booked orders are read.
    externally_priced boolean NOT NULL DEFAULT false,
    guest_orders_allowed boolean NOT NULL DEFAULT true,
    -- ADR 0026 installation backing an AGGREGATOR, TELEGRAM or KIOSK channel.
    provider_installation_id uuid,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_sales_channel_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_sales_channel_system_type CHECK (system_type IN (
        'WEB', 'IOS', 'ANDROID', 'TELEGRAM', 'KIOSK', 'QR_TABLE',
        'CALL_CENTRE', 'AGGREGATOR', 'POS')),
    -- Channels archive, never delete. Every order carries its channel forever,
    -- and a deleted row makes that order unattributable in every report.
    CONSTRAINT ck_sales_channel_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    -- A channel taking its prices from itself is either a no-op or an infinite
    -- loop depending on how the resolver is written. Neither is a configuration
    -- anyone meant.
    CONSTRAINT ck_sales_channel_price_plane_not_self CHECK (
        price_plane_channel_id IS NULL OR price_plane_channel_id <> id),
    CONSTRAINT fk_sales_channel_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    -- The price plane must be a channel of the same tenant. Matching on
    -- (tenant_id, id) rather than id alone is what makes that true at the
    -- database rather than in whichever service happens to write the row.
    CONSTRAINT fk_sales_channel_price_plane FOREIGN KEY (tenant_id, price_plane_channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id),
    -- ADR 0026 names the table integration.installations, not
    -- provider_installations as ADR 0036's sketch spells it. Matched on
    -- (tenant_id, id) so a channel cannot be backed by another tenant's
    -- aggregator account.
    CONSTRAINT fk_sales_channel_provider_installation
        FOREIGN KEY (tenant_id, provider_installation_id)
        REFERENCES integration.installations (tenant_id, id),
    CONSTRAINT uq_sales_channel_code UNIQUE (tenant_id, code),
    -- The key every child table below points at, so a channel id from another
    -- tenant cannot be bound to this tenant's locations, payment methods or
    -- fulfilment modes.
    CONSTRAINT uq_sales_channel_identity UNIQUE (tenant_id, id),
    CONSTRAINT ck_sales_channel_version CHECK (version >= 1)
);

CREATE INDEX ix_sales_channels_tenant ON tenant.sales_channels (tenant_id, status);

-- Which locations sell on this channel. Absent means the channel is not
-- available there: rule 2 of the resolver returns CHANNEL_NOT_ENABLED, and a
-- location that has never been bound is refused rather than silently enabled.
CREATE TABLE tenant.sales_channel_locations (
    tenant_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    location_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (channel_id, location_id),
    CONSTRAINT ck_channel_location_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT fk_channel_location_channel FOREIGN KEY (tenant_id, channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_channel_location_location FOREIGN KEY (tenant_id, location_id)
        REFERENCES tenant.locations (tenant_id, id)
);

-- Channel x payment method. A row that is absent means unavailable, so a channel
-- with no matrix sells nothing rather than everything.
--
-- payment_method_code is a code column and not yet a foreign key: ADR 0038 owns
-- the tenant-scoped payments.payment_methods registry, each row of which carries
-- the fiscal responsibility 0038 validates at activation, and that table does not
-- exist yet. ADR 0036's own checklist records this: point this column at
-- payments.payment_methods once 0038 lands. Until then a channel can name a code
-- with no owning legal entity, which is exactly the gap 0038 closes.
CREATE TABLE tenant.channel_payment_methods (
    tenant_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    payment_method_code varchar(32) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (channel_id, payment_method_code),
    CONSTRAINT ck_channel_payment_method_code CHECK (
        payment_method_code ~ '^[A-Z0-9][A-Z0-9_]{0,31}$'),
    CONSTRAINT fk_channel_payment_method_channel FOREIGN KEY (tenant_id, channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id) ON DELETE CASCADE
);

-- Channel x fulfilment mode. Dine-in is a mode here and never a channel: a
-- QR-table order and a waiter-entered order are both DINE_IN arriving through
-- different channels, and conflating the two is why Delever's order-type and
-- channel filters disagree.
CREATE TABLE tenant.channel_fulfillment_modes (
    tenant_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    fulfillment_mode varchar(16) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (channel_id, fulfillment_mode),
    CONSTRAINT ck_channel_fulfillment_mode CHECK (
        fulfillment_mode IN ('DELIVERY', 'PICKUP', 'DINE_IN')),
    CONSTRAINT fk_channel_fulfillment_mode_channel FOREIGN KEY (tenant_id, channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id) ON DELETE CASCADE
);

-- ----------------------------------------------------------------- serviceability

-- A named, reusable timetable. Named rather than a pair of "venue hours" and
-- "delivery hours" fields on the branch, because a fixed pair cannot express
-- pickup closing before dine-in, and thirty branches on one Ramadan timetable
-- should edit one object rather than thirty.
--
-- brand_id is NOT NULL, which departs from ADR 0036's sketch of a nullable
-- column. A nullable brand cannot be referentially checked against the brand of
-- the location a binding names — a NULL in a composite foreign key switches the
-- whole check off — so a tenant-wide schedule would have meant a binding that
-- silently accepts any schedule id at all. The ADR's own API creates schedules
-- under /brands/{brandId}, so nothing it decides needs the nullable form.
CREATE TABLE tenant.service_schedules (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    name varchar(200) NOT NULL,
    -- "Closed now" and "cannot pre-order" are different facts. A closed location
    -- whose schedule accepts scheduled orders still takes tomorrow's pre-order;
    -- merchants want the first without the second.
    accepts_scheduled_orders boolean NOT NULL DEFAULT true,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_service_schedule_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT uq_service_schedule_name UNIQUE (tenant_id, brand_id, name),
    -- The key location_service_bindings points at, which is what makes a
    -- cross-brand binding fail at the database instead of resolving to another
    -- brand's opening hours.
    CONSTRAINT uq_service_schedule_brand_identity UNIQUE (tenant_id, brand_id, id),
    CONSTRAINT ck_service_schedule_version CHECK (version >= 1)
);

-- One weekly window. Several rows may cover one day: a venue that closes for the
-- afternoon has two.
CREATE TABLE tenant.service_schedule_rules (
    schedule_id uuid NOT NULL,
    sequence integer NOT NULL,
    -- ISO-8601 numbering, 1 = Monday through 7 = Sunday, matching
    -- java.time.DayOfWeek.getValue(). Named here because the alternative
    -- convention (0 = Sunday) differs by a day for six of seven values, and that
    -- error reads as "the branch is shut on the wrong day" rather than as a bug.
    day_of_week smallint NOT NULL,
    opens_at time NOT NULL,
    closes_at time NOT NULL,

    PRIMARY KEY (schedule_id, sequence),
    CONSTRAINT ck_schedule_rule_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT fk_schedule_rule_schedule FOREIGN KEY (schedule_id)
        REFERENCES tenant.service_schedules (id) ON DELETE CASCADE
);

COMMENT ON COLUMN tenant.service_schedule_rules.closes_at IS
    'closes_at <= opens_at means the window ends on the following day. A venue '
    'open 18:00-02:00 stored as a naive range compares as 18:00 <= t < 02:00, '
    'which is empty, and the branch reads as shut all evening.';

-- A dated override for one calendar date, in the schedule's local time. One row
-- per date: two overlapping exceptions for one day would need a precedence rule
-- nobody would remember, and "closed for Navruz" is not a thing anyone says twice
-- about the same day.
CREATE TABLE tenant.service_schedule_exceptions (
    id uuid PRIMARY KEY,
    schedule_id uuid NOT NULL,
    exception_date date NOT NULL,
    closed_all_day boolean NOT NULL,
    opens_at time,
    closes_at time,
    label varchar(200) NOT NULL,
    created_by uuid,
    reason varchar(400) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_schedule_exception_date UNIQUE (schedule_id, exception_date),
    -- Either the day is closed, or it has replacement hours. A row carrying both
    -- or neither is a rule whose meaning depends on which branch of the resolver
    -- happens to read it first.
    CONSTRAINT ck_schedule_exception_hours CHECK (
        (closed_all_day AND opens_at IS NULL AND closes_at IS NULL)
        OR (NOT closed_all_day AND opens_at IS NOT NULL AND closes_at IS NOT NULL)),
    CONSTRAINT fk_schedule_exception_schedule FOREIGN KEY (schedule_id)
        REFERENCES tenant.service_schedules (id) ON DELETE CASCADE
);

-- Which timetable governs which fulfilment mode at which location. One binding
-- per (location, mode), so pickup may close before dine-in without either
-- needing its own column on the branch.
CREATE TABLE tenant.location_service_bindings (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    fulfillment_mode varchar(16) NOT NULL,
    schedule_id uuid NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (location_id, fulfillment_mode),
    CONSTRAINT ck_location_binding_mode CHECK (
        fulfillment_mode IN ('DELIVERY', 'PICKUP', 'DINE_IN')),
    CONSTRAINT fk_location_binding_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    -- Matching the brand on both sides is the whole point: a location may only be
    -- bound to a schedule of its own brand, so one brand's Ramadan timetable can
    -- never silently govern another brand's branches.
    CONSTRAINT fk_location_binding_schedule FOREIGN KEY (tenant_id, brand_id, schedule_id)
        REFERENCES tenant.service_schedules (tenant_id, brand_id, id)
);

-- The manual switch a manager hits when the fryer dies, and the kitchen ceiling.
--
-- A manual close is never a bare boolean. It carries an actor, a reason code, and
-- either an effective_until or an explicit "until I reopen it". The failure that
-- prevents is common and expensive: a branch closed at 19:00 because the fryer
-- failed and still closed on Saturday, because the person who closed it went
-- home. An elapsed effective_until returns the location to FOLLOW_SCHEDULE by
-- being read as elapsed, with no operator action and no scheduled job — a job
-- that failed would silently leave a network closed with a cause
-- indistinguishable from an outage.
CREATE TABLE tenant.location_service_state (
    location_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    mode varchar(16) NOT NULL DEFAULT 'FOLLOW_SCHEDULE',
    reason_code varchar(48),
    note varchar(400),
    effective_until timestamptz,
    -- Advisory at browse, authoritative at checkout. Null means uncapped.
    max_concurrent_orders integer,
    changed_by uuid,
    changed_at timestamptz NOT NULL DEFAULT now(),
    version integer NOT NULL DEFAULT 1,

    CONSTRAINT ck_location_service_mode CHECK (
        mode IN ('FOLLOW_SCHEDULE', 'FORCE_OPEN', 'FORCE_CLOSED')),
    -- The reason is mandatory on an override and refused on the default state.
    -- Enforced here rather than in the service because a close with no reason is
    -- the exact support conversation this table exists to end, and a second
    -- writer would not remember the rule.
    CONSTRAINT ck_location_service_reason CHECK (
        (mode = 'FOLLOW_SCHEDULE' AND reason_code IS NULL)
        OR (mode <> 'FOLLOW_SCHEDULE' AND reason_code IS NOT NULL
            AND length(btrim(reason_code)) > 0)),
    -- An expiry on the default state would describe nothing.
    CONSTRAINT ck_location_service_expiry CHECK (
        mode <> 'FOLLOW_SCHEDULE' OR effective_until IS NULL),
    CONSTRAINT ck_location_service_capacity CHECK (
        max_concurrent_orders IS NULL OR max_concurrent_orders > 0),
    CONSTRAINT fk_location_service_state_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT ck_location_service_version CHECK (version >= 1)
);

-- One in-flight order occupying a capacity slot.
--
-- ADR 0036 says the cap is counted inside ADR 0019's checkout transaction
-- against open orders. ordering.orders does not exist yet, so there is nothing to
-- count; this table is the counted set in the meantime, claimed and released by
-- the same transaction that would claim and release an order. When ADR 0019
-- lands, the count becomes a count of open orders and this table is dropped in
-- the same migration — it must never become a second, drifting answer to "how
-- busy is this kitchen".
CREATE TABLE tenant.location_capacity_holds (
    -- The cart id today, the order id once ADR 0019 exists. A primary key rather
    -- than a generated one, so a retried checkout re-claims its own slot instead
    -- of consuming a second.
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    held_at timestamptz NOT NULL DEFAULT now(),
    released_at timestamptz,

    CONSTRAINT fk_capacity_hold_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id)
);

CREATE INDEX ix_capacity_holds_open
    ON tenant.location_capacity_holds (location_id)
    WHERE released_at IS NULL;

-- Time-banded preparation durations, so a Friday rush quotes 45 minutes rather
-- than 25. A null fulfillment_mode or day_of_week means "any", so one row covers
-- the common case and a narrower row overrides it by priority.
CREATE TABLE tenant.preparation_bands (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    fulfillment_mode varchar(16),
    day_of_week smallint,
    starts_at time NOT NULL,
    ends_at time NOT NULL,
    duration_minutes integer NOT NULL,
    -- Settles overlap deterministically, for the same reason price books carry
    -- one: without it the promised time would depend on which row the planner
    -- emitted first, and the same order would quote differently twice.
    priority integer NOT NULL DEFAULT 0,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_preparation_band_mode CHECK (
        fulfillment_mode IS NULL OR fulfillment_mode IN ('DELIVERY', 'PICKUP', 'DINE_IN')),
    CONSTRAINT ck_preparation_band_day CHECK (day_of_week IS NULL OR day_of_week BETWEEN 1 AND 7),
    -- Bands do not wrap past midnight, unlike opening hours. A band is a
    -- statement about how busy the kitchen is between two clock times; the
    -- after-midnight case is two rows, and allowing a wrap here would mean two
    -- different time comparisons in one resolver.
    CONSTRAINT ck_preparation_band_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_preparation_band_duration CHECK (
        duration_minutes > 0 AND duration_minutes <= 1440),
    CONSTRAINT fk_preparation_band_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id)
);

CREATE INDEX ix_preparation_bands_lookup
    ON tenant.preparation_bands (location_id, fulfillment_mode);

-- ------------------------------------------------- per-channel item suppression

-- Default is offered; a row removes one item from one channel, optionally at one
-- location. Sparse exclusions rather than a materialised (variant, location,
-- channel) matrix: the matrix is correct and unreviewable — tens of thousands of
-- near-identical rows in which nobody can tell an intended exception from a stale
-- one — and carries the same information.
--
-- Read live, like catalog.location_offerings and for the same reason ADR 0016
-- already gives: hiding a dish must take effect now, not after revalidating and
-- republishing a whole menu.
CREATE TABLE catalog.channel_offering_exclusions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    -- Null excludes the variant on this channel across the whole brand.
    location_id uuid,
    variant_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    reason_code varchar(48) NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    -- ADR 0016's composite brand key: a channel may only exclude a variant that
    -- belongs to the brand named on the row.
    CONSTRAINT fk_channel_exclusion_variant FOREIGN KEY (variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id),
    CONSTRAINT fk_channel_exclusion_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_channel_exclusion_channel FOREIGN KEY (tenant_id, channel_id)
        REFERENCES tenant.sales_channels (tenant_id, id)
);

-- Two partial indexes rather than one, because a nullable column in a unique
-- constraint does not deduplicate: without these, "hide this dish on the kiosk"
-- could be inserted twice and a later "unhide" would remove one of them.
CREATE UNIQUE INDEX ux_channel_exclusion_at_location
    ON catalog.channel_offering_exclusions (channel_id, variant_id, location_id)
    WHERE location_id IS NOT NULL;

CREATE UNIQUE INDEX ux_channel_exclusion_brand_wide
    ON catalog.channel_offering_exclusions (channel_id, variant_id)
    WHERE location_id IS NULL;

-- --------------------------------------------- ADR 0016 correction and rollout

-- ADR 0036's rollout step. STOREFRONT is the value catalog.publications.channel
-- already defaults to and already holds, so seeding one WEB channel per tenant
-- makes every existing publication valid under the foreign key added below
-- without rewriting a single publication row.
INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
SELECT gen_random_uuid(), t.id, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE'
FROM tenant.tenants t;

-- ADR 0036 corrects ADR 0016: catalog.publications.channel becomes a reference to
-- a registered sales channel rather than free text defaulting to 'STOREFRONT'.
--
-- Free text meant a typo published a menu to a channel that does not exist, on
-- which nobody would ever see it, and nothing failed. It is a code reference and
-- not an id because a publication is read on the storefront path by channel code,
-- and because an archived channel must still render on the historical
-- publications that named it.
--
-- ADR 0016's sketched `location_offerings.sales_schedule_id` is withdrawn by the
-- same decision. V0016 never created that column, so nothing here removes it;
-- item windows reference tenant.service_schedules instead.
ALTER TABLE catalog.publications
    ADD CONSTRAINT fk_publication_channel FOREIGN KEY (tenant_id, channel)
    REFERENCES tenant.sales_channels (tenant_id, code);

COMMENT ON TABLE tenant.sales_channels IS
    'ADR 0036 tenant-owned sales channel registry with a code-owned system type';
COMMENT ON TABLE tenant.service_schedules IS
    'ADR 0036 named, reusable opening timetable bound per location and fulfilment mode';
COMMENT ON TABLE tenant.location_service_state IS
    'ADR 0036 manual open/closed override and concurrent-order ceiling per location';
COMMENT ON TABLE tenant.location_capacity_holds IS
    'ADR 0036 interim counted set for the concurrent-order cap until ADR 0019 creates ordering.orders';

GRANT USAGE ON SCHEMA tenant TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.sales_channels TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.sales_channel_locations TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.channel_payment_methods TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.channel_fulfillment_modes TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.service_schedules TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.service_schedule_rules TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.service_schedule_exceptions TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.location_service_bindings TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.location_service_state TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.location_capacity_holds TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.preparation_bands TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.channel_offering_exclusions TO qoida_application;
