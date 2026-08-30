-- ADR 0041: kitchen execution, production routing, and kitchen release.
--
-- ADR 0019 gave the commercial order one PREPARING -> READY pair, which is the
-- right shape for a contract with a customer and useless as a description of a
-- kitchen. A kitchen needs three facts the order cannot carry: which station owns
-- which line, whether the grill is done while the cold line is not, and when the
-- food should hit the pass rather than when the customer was promised it. This
-- migration creates the second aggregate that holds them, beside the order and
-- never inside it.
--
-- Nothing here writes ordering.orders. Ticket progress reaches the order only as
-- a proposal through the ADR 0019 command path, exactly as POS and delivery do.
--
-- ---------------------------------------------------------------------------
-- What the legacy `kitchens` table actually is, and what follows from it
-- ---------------------------------------------------------------------------
--
-- ADR 0041's context says "the legacy `kitchens` table is location-owned" and
-- docs/domains/legacy-profile-findings.md section 4 says it is "a preparation-
-- station classification shared across the estate, which is precisely ADR 0041's
-- production routing". Both were checked against the source before this file was
-- written, and both are wrong in ways that change what can be imported.
--
-- `app/models/product.py` declares `Kitchen` with exactly six columns: an i18n
-- name, an i18n description, a status, a priority, a nullable image, and the
-- timestamps every model has. It carries no `vendor_id`, no `company_id`, and no
-- branch reference of any kind, so it is certainly not location-owned — the ADR's
-- sentence is mistaken. The findings document is right that far.
--
-- It is also not a station classification. `Category` in the same file has the
-- same six columns, and the two are served by the same dashboard CRUD, embedded
-- side by side in `ProductSchema`, and joined onto every product eagerly. A
-- preparation station has no marketing description and no photograph; a browse
-- facet has both. `kitchens` is a second estate-wide catalogue taxonomy beside
-- `categories` — in this market, "кухня" as in узбекская / европейская — and no
-- code anywhere in the legacy application routes, filters, groups, or reports by
-- it. The seed underlines it: all six seeded products share one `kitchen_id`,
-- which is byte-for-byte the same UUID as a category id.
--
-- The consequence is concrete and it is a scope fact rather than an opinion.
-- There is no station data in the legacy estate to TRANSFORM. Production routing
-- is greenfield: every station below and every rule pointing at one has to be
-- authored, per location, before a branch can run a screen. Seeding stations from
-- three cuisine rows would give twelve branches an identical three-station layout
-- that matches no kitchen any of them has, and the first symptom would be dishes
-- appearing on the wrong pass during service. The `kitchens` rows themselves are
-- a catalogue-taxonomy question and belong to ADR 0016, not here.

CREATE SCHEMA IF NOT EXISTS kitchen;

COMMENT ON SCHEMA kitchen IS
    'ADR 0041. Production stations, routing rules, and the production ticket that runs beside the commercial order. Never a writer of ordering.orders.';

-- ---------------------------------------------------------------------------
-- Stations
-- ---------------------------------------------------------------------------
--
-- Location-owned rows carrying a code-owned role. ADR 0041 keeps the role closed
-- for the reason ADR 0025 keeps capabilities in code: free text means one branch
-- spells the role `гриль` and another `Grill`, and the brand layer below then
-- silently routes to neither.
CREATE TABLE kitchen.stations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    -- The operator's stable handle for this station. Renaming the display name
    -- mid-service must not invalidate a routing rule or a device's filter.
    code varchar(32) NOT NULL,
    role varchar(16) NOT NULL,
    -- ADR 0041: a ticket renders in the kitchen's locale, not the customer's, so
    -- all three are carried on the row rather than resolved per reader.
    display_name_ru varchar(120) NOT NULL,
    display_name_uz varchar(120) NOT NULL,
    display_name_en varchar(120) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    -- Where an unroutable line goes. Exactly one per location, enforced below.
    is_fallback boolean NOT NULL DEFAULT false,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_station_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_station_role CHECK (role IN (
        'HOT', 'COLD', 'GRILL', 'BAR', 'BAKERY', 'PACKING', 'EXPO')),
    CONSTRAINT ck_station_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_station_version CHECK (version >= 1),
    -- An archived station cannot be the place unroutable lines land: the fallback
    -- is the one screen that must always exist.
    CONSTRAINT ck_station_fallback_active CHECK (NOT is_fallback OR status = 'ACTIVE'),
    CONSTRAINT fk_station_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT uq_station_code UNIQUE (tenant_id, location_id, code),
    -- Lets a routing rule name a station and a location in one foreign key, so a
    -- rule at one branch can never point at another branch's grill.
    CONSTRAINT uq_station_identity UNIQUE (id, tenant_id, location_id)
);

COMMENT ON COLUMN kitchen.stations.role IS
    'ADR 0041. Closed and code-owned. The brand routing layer assigns a catalogue node to a role, and the role resolves to whichever station at the location carries it, so a role spelled freely would route to nothing.';
COMMENT ON COLUMN kitchen.stations.is_fallback IS
    'ADR 0041. Where a line that resolved through no rule lands. A line on no screen is a dish nobody cooks and a customer who waits for it, so routing never returns nothing.';

-- One fallback per location. Two would make "where did this unrouted dish go"
-- depend on which row a query happened to read first.
CREATE UNIQUE INDEX ux_station_single_fallback
    ON kitchen.stations (tenant_id, location_id)
    WHERE is_fallback;

-- One active station per role per location, because the brand layer resolves a
-- role to "the location's station carrying it". With two grills that resolution
-- has no answer, and the failure would appear as a dish that lands on a different
-- screen each service rather than as a configuration error anybody can see.
CREATE UNIQUE INDEX ux_station_single_active_role
    ON kitchen.stations (tenant_id, location_id, role)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_stations_location ON kitchen.stations (tenant_id, location_id, sort_order);

-- ---------------------------------------------------------------------------
-- Routing rules, in two layers
-- ---------------------------------------------------------------------------
--
-- ADR 0041 rejected both single-layer options. A brand attribute alone cannot
-- describe two branches of one chain where one has a grill and a bar and the
-- other has a single hot line. A location-owned mapping alone makes every new
-- branch re-map the whole menu by hand — 400 items across 12 branches is 4,800
-- assignments, and the ones nobody does pile onto the fallback screen.
--
-- Both tables address a catalogue node the same way: three nullable columns with
-- a real foreign key each, exactly one of them populated. The alternative — a
-- `node_type` discriminator over one untyped `node_id` — is shorter and has no
-- referential integrity at all, so a rule would happily survive the deletion of
-- the dish it routes and send a line to a station for a variant that no longer
-- exists. Every foreign key here carries the tenant and brand alongside the id,
-- so a rule cannot address another tenant's menu.

CREATE TABLE kitchen.brand_routing_rules (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    variant_id uuid,
    product_id uuid,
    category_id uuid,

    station_role varchar(16) NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_brand_rule_role CHECK (station_role IN (
        'HOT', 'COLD', 'GRILL', 'BAR', 'BAKERY', 'PACKING', 'EXPO')),
    CONSTRAINT ck_brand_rule_version CHECK (version >= 1),
    -- Exactly one node, counted rather than enumerated: a disjunction of seven
    -- IS NULL clauses is the same rule written in a form nobody can check.
    CONSTRAINT ck_brand_rule_one_node CHECK (
        (variant_id IS NOT NULL)::integer
        + (product_id IS NOT NULL)::integer
        + (category_id IS NOT NULL)::integer = 1),
    CONSTRAINT fk_brand_rule_variant FOREIGN KEY (variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id),
    CONSTRAINT fk_brand_rule_product FOREIGN KEY (product_id, tenant_id, brand_id)
        REFERENCES catalog.products (id, tenant_id, brand_id),
    CONSTRAINT fk_brand_rule_category FOREIGN KEY (category_id, tenant_id, brand_id)
        REFERENCES catalog.categories (id, tenant_id, brand_id)
);

COMMENT ON TABLE kitchen.brand_routing_rules IS
    'ADR 0041. The brand layer: a catalogue node goes to a station role, which each location resolves to its own station. Authored once for the menu rather than once per branch.';

CREATE UNIQUE INDEX ux_brand_rule_variant
    ON kitchen.brand_routing_rules (tenant_id, brand_id, variant_id) WHERE variant_id IS NOT NULL;
CREATE UNIQUE INDEX ux_brand_rule_product
    ON kitchen.brand_routing_rules (tenant_id, brand_id, product_id) WHERE product_id IS NOT NULL;
CREATE UNIQUE INDEX ux_brand_rule_category
    ON kitchen.brand_routing_rules (tenant_id, brand_id, category_id) WHERE category_id IS NOT NULL;

CREATE TABLE kitchen.location_routing_rules (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,

    variant_id uuid,
    product_id uuid,
    category_id uuid,

    station_id uuid NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_location_rule_version CHECK (version >= 1),
    CONSTRAINT ck_location_rule_one_node CHECK (
        (variant_id IS NOT NULL)::integer
        + (product_id IS NOT NULL)::integer
        + (category_id IS NOT NULL)::integer = 1),
    CONSTRAINT fk_location_rule_station FOREIGN KEY (station_id, tenant_id, location_id)
        REFERENCES kitchen.stations (id, tenant_id, location_id),
    CONSTRAINT fk_location_rule_variant FOREIGN KEY (variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id),
    CONSTRAINT fk_location_rule_product FOREIGN KEY (product_id, tenant_id, brand_id)
        REFERENCES catalog.products (id, tenant_id, brand_id),
    CONSTRAINT fk_location_rule_category FOREIGN KEY (category_id, tenant_id, brand_id)
        REFERENCES catalog.categories (id, tenant_id, brand_id)
);

COMMENT ON TABLE kitchen.location_routing_rules IS
    'ADR 0041. The location layer, which overrides the brand layer for any node. This is where a branch whose layout differs from the chain says so, without re-mapping the menu it shares.';

CREATE UNIQUE INDEX ux_location_rule_variant
    ON kitchen.location_routing_rules (tenant_id, location_id, variant_id) WHERE variant_id IS NOT NULL;
CREATE UNIQUE INDEX ux_location_rule_product
    ON kitchen.location_routing_rules (tenant_id, location_id, product_id) WHERE product_id IS NOT NULL;
CREATE UNIQUE INDEX ux_location_rule_category
    ON kitchen.location_routing_rules (tenant_id, location_id, category_id) WHERE category_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- The production ticket
-- ---------------------------------------------------------------------------
CREATE TABLE kitchen.tickets (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    order_id uuid NOT NULL,

    -- The short number the pass calls out. Copied from
    -- ordering.orders.public_order_number rather than allocated from a counter of
    -- this table's own, which ADR 0041's "reset per location per business day"
    -- would otherwise imply. A second counter would drift from the first the
    -- moment an order was created without a ticket or a ticket was voided, and
    -- the cook would then be shouting a number that is not the one on the
    -- customer's receipt. One number, one allocator, copied where it is read.
    sequence_label varchar(24) NOT NULL,

    fulfilment_mode varchar(16) NOT NULL,
    channel_code varchar(64) NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'HELD',
    release_mode varchar(20) NOT NULL,
    release_at timestamptz,
    released_at timestamptz,

    prep_estimate_seconds integer,
    -- When the food must be on the pass. Derived once, at ticket creation, from
    -- the order's stored promise: promised_at minus the travel component, because
    -- a delivery order promised for 20:00 with 20 minutes on the road is due at
    -- the pass at 19:40. Null when the order carries no promise at all, which
    -- V0023 records honestly as NOT_PROMISED rather than fabricating a number.
    target_ready_at timestamptz,

    started_at timestamptz,
    ready_at timestamptz,
    handed_over_at timestamptz,

    -- Pinned when the ticket is created. Changing the rules later does not
    -- re-route a ticket already fired: a cook does not want a dish moving off
    -- their screen mid-service.
    routing_version integer NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_ticket_status CHECK (status IN (
        'HELD', 'FIRED', 'IN_PRODUCTION', 'READY', 'HANDED_OVER', 'VOIDED')),
    CONSTRAINT ck_ticket_release_mode CHECK (release_mode IN (
        'AUTO_ON_CONFIRM', 'SCHEDULED', 'MANUAL_HOLD')),
    CONSTRAINT ck_ticket_mode CHECK (fulfilment_mode IN ('DELIVERY', 'PICKUP', 'DINE_IN')),
    CONSTRAINT ck_ticket_versions CHECK (version >= 1 AND routing_version >= 1),
    CONSTRAINT ck_ticket_prep_estimate CHECK (
        prep_estimate_seconds IS NULL OR prep_estimate_seconds > 0),

    -- A scheduled release is the only mode that names an instant, and it must.
    -- Stated as an equivalence so neither half can drift: a SCHEDULED ticket with
    -- no instant never fires, and an instant on a mode that ignores it is a fire
    -- time an operator edited and the scheduler will not honour.
    CONSTRAINT ck_ticket_scheduled_has_instant CHECK (
        (release_mode = 'SCHEDULED') = (release_at IS NOT NULL)),

    -- Each of the next four is a one-directional implication with no NULL
    -- operand on either side, rather than an equivalence, because VOIDED can be
    -- reached from any live status and keeps whatever instants it had reached.
    CONSTRAINT ck_ticket_held_not_released CHECK (
        status <> 'HELD' OR released_at IS NULL),
    CONSTRAINT ck_ticket_fired_is_released CHECK (
        status NOT IN ('FIRED', 'IN_PRODUCTION', 'READY', 'HANDED_OVER')
        OR released_at IS NOT NULL),
    CONSTRAINT ck_ticket_production_started CHECK (
        status NOT IN ('IN_PRODUCTION', 'READY', 'HANDED_OVER') OR started_at IS NOT NULL),
    CONSTRAINT ck_ticket_ready_recorded CHECK (
        status NOT IN ('READY', 'HANDED_OVER') OR ready_at IS NOT NULL),
    -- A recall clears ready_at, so a ticket back in production must not still
    -- claim an instant at which it was ready.
    CONSTRAINT ck_ticket_recall_clears_ready CHECK (
        status NOT IN ('HELD', 'FIRED', 'IN_PRODUCTION') OR ready_at IS NULL),
    -- HANDED_OVER is terminal, so this one is safe as an equivalence.
    CONSTRAINT ck_ticket_handover CHECK (
        (status = 'HANDED_OVER') = (handed_over_at IS NOT NULL)),

    CONSTRAINT fk_ticket_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT fk_ticket_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    -- One ticket per order. ADR 0041 says one per order per location and an order
    -- has exactly one location, so the location adds nothing to the key and would
    -- only make a second ticket at a second branch expressible.
    CONSTRAINT uq_ticket_order UNIQUE (tenant_id, order_id),
    CONSTRAINT uq_ticket_identity UNIQUE (id, tenant_id),
    -- Lets a ticket item name its ticket and its branch in one key, which is half
    -- of what stops an item at one branch being worked from another's screen.
    CONSTRAINT uq_ticket_location_identity UNIQUE (id, tenant_id, location_id)
);

COMMENT ON TABLE kitchen.tickets IS
    'ADR 0041. The kitchen aggregate. Proposes order transitions through the ADR 0019 command path and never writes ordering.orders.';
COMMENT ON COLUMN kitchen.tickets.released_at IS
    'ADR 0041. When the kitchen was actually told to start, which is neither the confirmation nor the promise. Without it a preorder placed at 11:00 for 20:00 prints on the line at 11:00 and the food is thrown away.';
COMMENT ON COLUMN kitchen.tickets.target_ready_at IS
    'ADR 0041. When the food is due at the pass: the order promise less its travel component. Distinct from the promise, which is when the customer gets it.';
COMMENT ON COLUMN kitchen.tickets.routing_version IS
    'ADR 0041. The routing generation this ticket resolved under, so editing rules during service never moves a fired dish to another screen.';

-- The board: what is live at this branch, worst overrun first once the
-- application derives it. Partial because a handed-over ticket is never on the
-- board and those are the rows that accumulate for ever.
CREATE INDEX ix_tickets_board
    ON kitchen.tickets (tenant_id, location_id, target_ready_at)
    WHERE status IN ('FIRED', 'IN_PRODUCTION', 'READY');

-- The buffer, and the scheduler's claim query over it.
CREATE INDEX ix_tickets_due_for_release
    ON kitchen.tickets (release_at)
    WHERE status = 'HELD' AND release_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Ticket items
-- ---------------------------------------------------------------------------
--
-- One item per order line per routed station. ADR 0041 routes a line to exactly
-- one station — a line is one sellable variant plus its modifiers, and a combo
-- needing two stations is two lines under ADR 0016's product model — so the
-- unique key below is the one that would catch a resolver that started returning
-- two.
--
-- Deliberately no dish name and no note, against ADR 0041's sketch of this table.
-- Two reasons, and the second is a rule rather than a preference. The name
-- already has one authority in ordering.order_lines.product_name_snapshot, and a
-- second copy is a second thing to be wrong on a screen a cook plates from. The
-- note is the customer's own words and ordering.order_lines.note_encrypted holds
-- it under ADR 0029 envelope encryption; copying it here in plaintext would put
-- personal data outside the envelope, which ADR 0029 forbids outright. A display
-- resolves both through an authorized read against the order, which is what
-- ADR 0041 already says its events require.
CREATE TABLE kitchen.ticket_items (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    ticket_id uuid NOT NULL,
    -- Carried rather than joined for, so the two foreign keys below can both
    -- name it. That is what makes "this item's station is at this item's ticket's
    -- branch" a database fact instead of an application convention — the SQL half
    -- of ADR 0041's requirement that a location-scoped principal is refused at a
    -- sibling location at both boundaries.
    location_id uuid NOT NULL,
    order_line_id uuid NOT NULL,
    station_id uuid NOT NULL,
    quantity integer NOT NULL,

    -- Which of the five resolution levels put this line on this screen. The
    -- answer to "why is this on the grill", which five levels of precedence make
    -- otherwise unanswerable, and the column that makes the fallback backlog
    -- countable rather than merely visible.
    routed_by varchar(20) NOT NULL,

    status varchar(12) NOT NULL DEFAULT 'QUEUED',
    started_at timestamptz,
    ready_at timestamptz,
    cancelled_at timestamptz,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_ticket_item_status CHECK (status IN ('QUEUED', 'STARTED', 'READY', 'CANCELLED')),
    CONSTRAINT ck_ticket_item_routed_by CHECK (routed_by IN (
        'LOCATION_VARIANT', 'LOCATION_PRODUCT', 'LOCATION_CATEGORY', 'BRAND_ROLE', 'FALLBACK')),
    CONSTRAINT ck_ticket_item_quantity CHECK (quantity > 0),
    CONSTRAINT ck_ticket_item_version CHECK (version >= 1),
    CONSTRAINT ck_ticket_item_started CHECK (
        status NOT IN ('STARTED', 'READY') OR started_at IS NOT NULL),
    CONSTRAINT ck_ticket_item_ready CHECK (
        (status = 'READY') = (ready_at IS NOT NULL)),
    CONSTRAINT ck_ticket_item_cancelled CHECK (
        (status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    CONSTRAINT fk_ticket_item_ticket FOREIGN KEY (ticket_id, tenant_id, location_id)
        REFERENCES kitchen.tickets (id, tenant_id, location_id),
    CONSTRAINT fk_ticket_item_line FOREIGN KEY (order_line_id, tenant_id)
        REFERENCES ordering.order_lines (id, tenant_id),
    CONSTRAINT fk_ticket_item_station FOREIGN KEY (station_id, tenant_id, location_id)
        REFERENCES kitchen.stations (id, tenant_id, location_id),
    CONSTRAINT uq_ticket_item_line_station UNIQUE (tenant_id, order_line_id, station_id),
    CONSTRAINT uq_ticket_item_identity UNIQUE (id, tenant_id)
);

CREATE INDEX ix_ticket_items_ticket ON kitchen.ticket_items (ticket_id);

-- One station's queue, which is the only read a kitchen device ever performs.
CREATE INDEX ix_ticket_items_station_queue
    ON kitchen.ticket_items (tenant_id, station_id, status)
    WHERE status IN ('QUEUED', 'STARTED');

-- ---------------------------------------------------------------------------
-- Ticket events
-- ---------------------------------------------------------------------------
--
-- The kitchen's own history. ADR 0041 is explicit that kitchen progress with no
-- order-level meaning — the grill finished, a ticket was recalled — stays inside
-- this aggregate and never becomes a commercial fact, so this is the only place
-- those transitions are recorded and the only place "why is this ticket still on
-- the pass" can be answered from.
--
-- ADR 0041's sketch carries a device_id here. Kitchen devices are not built in
-- this slice, so the column is omitted rather than added as a nullable reference
-- to a table that does not exist: a foreign key with no target is not evidence,
-- and a device id nothing writes reads as "no device" for every row that had one.
CREATE TABLE kitchen.ticket_events (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    ticket_id uuid NOT NULL,
    ticket_item_id uuid,
    from_status varchar(16),
    to_status varchar(16) NOT NULL,
    trigger varchar(32) NOT NULL,
    actor_type varchar(24) NOT NULL,
    actor_id varchar(128),
    reason_code varchar(48),
    occurred_at timestamptz NOT NULL,
    correlation_id varchar(128),

    CONSTRAINT ck_ticket_event_trigger CHECK (trigger IN (
        -- A person at a station or on the expo screen.
        'STATION_ACTION',
        -- The order reached CONFIRMED and the ticket's release mode fired it.
        'ORDER_CONFIRMED',
        -- The release scheduler reached release_at.
        'RELEASE_SCHEDULED',
        -- Someone held, released, or re-timed the ticket by hand.
        'RELEASE_COMMAND',
        -- The roll-up moved the ticket because its items moved.
        'ITEM_ROLLUP',
        -- Routing could not resolve a line and the fallback station took it.
        'ROUTING_UNRESOLVED')),
    CONSTRAINT ck_ticket_event_actor CHECK (actor_type IN (
        'USER', 'SYSTEM_JOB', 'SERVICE')),
    CONSTRAINT fk_ticket_event_ticket FOREIGN KEY (ticket_id, tenant_id)
        REFERENCES kitchen.tickets (id, tenant_id),
    CONSTRAINT fk_ticket_event_item FOREIGN KEY (ticket_item_id, tenant_id)
        REFERENCES kitchen.ticket_items (id, tenant_id)
);

CREATE INDEX ix_ticket_events_ticket ON kitchen.ticket_events (ticket_id, occurred_at);

-- The unresolved-routing backlog: which branches are sending dishes to the
-- fallback screen, and how many. Without this the fallback quietly absorbs an
-- unmapped menu and nobody finds out until a cook asks what a ticket is for.
CREATE INDEX ix_ticket_events_routing_unresolved
    ON kitchen.ticket_events (tenant_id, occurred_at)
    WHERE trigger = 'ROUTING_UNRESOLVED';

COMMENT ON TABLE kitchen.ticket_events IS
    'ADR 0041. Every ticket and item transition with what caused it and who. The kitchen half of an order timeline, which ordering.order_state_history deliberately does not carry.';

-- ---------------------------------------------------------------------------
-- Not created here, and why
-- ---------------------------------------------------------------------------
--
-- `kitchen.station_capacity`. ADR 0041's throughput ceiling shifts release_at.
-- Nothing shifts it in this slice, so the table would be configuration no code
-- reads — an operator setting a ceiling that silently does nothing is worse than
-- no ceiling at all.
--
-- `kitchen.devices`. Device enrolment, revocation and station-bound principals
-- are rollout step 4. A device table with no principal behind it would be a
-- registration that grants nothing and revokes nothing, which is exactly the
-- shared-manager-login problem ADR 0041 created it to solve.
--
-- `kitchen.branch_suspensions`. ADR 0041 sketches this table, and it must not be
-- built: `tenant.location_service_state` from V0020 already carries a
-- FORCE_CLOSED mode with a mandatory reason_code and an effective_until, which is
-- the same fact with the same auto-expiry, under the ADR 0036 capability
-- `location.service-state.change`. A second suspension record is the mistake
-- ADR 0041 itself refuses twice — once for the stop list, once for the handover
-- challenge — and refusing it a third time here costs nothing: the kitchen device
-- calls the existing serviceability endpoint.
--
-- `kitchen.handovers`. Withdrawn by ADR 0041 itself. ADR 0040 owns
-- ordering.order_handover_challenges, and two verification records for one
-- handover means the expo screen and the partner API can disagree about whether a
-- bag was released with neither row authoritative.

GRANT USAGE ON SCHEMA kitchen TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON kitchen.stations TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON kitchen.brand_routing_rules TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON kitchen.location_routing_rules TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON kitchen.tickets TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON kitchen.ticket_items TO horecaos_application;
-- Events are append-only evidence. No UPDATE and no DELETE, for the same reason
-- ADR 0027 audit rows carry neither: a history that can be edited is not one.
GRANT SELECT, INSERT ON kitchen.ticket_events TO horecaos_application;
