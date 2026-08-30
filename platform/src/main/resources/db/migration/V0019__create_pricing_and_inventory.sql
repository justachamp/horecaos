-- ADR 0018 pricing and ADR 0017 inventory, scoped to the first cutover slice:
-- price books, tax, and the quote lifecycle; binary availability and the
-- reservation path. Promotions, coupons, benefit grants, and quantity tracking
-- are deliberately absent — their tables arrive with the decisions that need them.
--
-- All money is integer minor units. For UZS that is whole som: tiyin are
-- obsolete in practice and both payment providers settle in whole som, so a
-- stored sub-unit would be precision nobody can actually pay.

CREATE SCHEMA IF NOT EXISTS pricing;
CREATE SCHEMA IF NOT EXISTS inventory;

-- ---------------------------------------------------------------- price books

CREATE TABLE pricing.price_books (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    name varchar(200) NOT NULL,
    currency char(3) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    -- Settles overlap deterministically. Row order and wall-clock timing must
    -- never decide a price, because then the same cart prices differently twice.
    priority integer NOT NULL DEFAULT 0,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_price_book_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_price_book_window CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_price_book_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT fk_price_book_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT uq_price_book_identity UNIQUE (id, tenant_id, brand_id)
);

CREATE TABLE pricing.price_book_assignments (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    price_book_id uuid NOT NULL,
    scope_type varchar(16) NOT NULL,
    scope_id uuid,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    priority integer NOT NULL DEFAULT 0,

    CONSTRAINT ck_assignment_scope CHECK (scope_type IN ('BRAND', 'LOCATION', 'CHANNEL')),
    -- A brand-scoped assignment covers the whole brand and needs no id; the
    -- others must name what they apply to.
    CONSTRAINT ck_assignment_scope_id CHECK (
        (scope_type = 'BRAND' AND scope_id IS NULL)
        OR (scope_type <> 'BRAND' AND scope_id IS NOT NULL)
    ),
    CONSTRAINT ck_assignment_window CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT fk_assignment_price_book FOREIGN KEY (price_book_id, tenant_id, brand_id)
        REFERENCES pricing.price_books (id, tenant_id, brand_id)
);

CREATE INDEX ix_assignments_lookup
    ON pricing.price_book_assignments (tenant_id, brand_id, scope_type, scope_id);

CREATE TABLE pricing.prices (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    price_book_id uuid NOT NULL,
    priceable_type varchar(16) NOT NULL,
    priceable_id uuid NOT NULL,
    -- VAT-inclusive: this is what the customer pays, not a net figure.
    amount_minor bigint NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    version integer NOT NULL DEFAULT 1,

    CONSTRAINT ck_price_type CHECK (priceable_type IN ('VARIANT', 'MODIFIER_OPTION', 'FEE')),
    -- Zero is legitimate (a free modifier); negative is not. A negative price
    -- is a discount, and discounts are adjustments with a recorded source.
    CONSTRAINT ck_price_amount CHECK (amount_minor >= 0),
    CONSTRAINT ck_price_window CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT fk_price_book FOREIGN KEY (price_book_id, tenant_id, brand_id)
        REFERENCES pricing.price_books (id, tenant_id, brand_id)
);

-- One price per thing per book at any instant. Two overlapping rows would make
-- the winner depend on row order, which is exactly what determinism forbids.
CREATE UNIQUE INDEX ux_price_current
    ON pricing.prices (price_book_id, priceable_type, priceable_id)
    WHERE valid_until IS NULL;

CREATE INDEX ix_prices_lookup
    ON pricing.prices (tenant_id, brand_id, priceable_type, priceable_id);

-- ----------------------------------------------------------------------- tax

CREATE TABLE pricing.tax_profiles (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    jurisdiction_code varchar(16) NOT NULL,
    -- INCLUSIVE only in the first slice. EXCLUSIVE exists so a later tenant in
    -- another jurisdiction has somewhere to go, and is rejected by the engine
    -- rather than silently mishandled.
    mode varchar(16) NOT NULL,
    -- Basis points: 1200 = 12%. Integers throughout, so no rate is ever a float
    -- that rounds differently on two machines.
    rate_basis_points integer NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    version integer NOT NULL DEFAULT 1,

    CONSTRAINT ck_tax_mode CHECK (mode IN ('INCLUSIVE', 'EXCLUSIVE')),
    CONSTRAINT ck_tax_rate CHECK (rate_basis_points >= 0 AND rate_basis_points < 10000),
    CONSTRAINT ck_tax_window CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT fk_tax_profile_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id)
);

CREATE INDEX ix_tax_profiles_lookup ON pricing.tax_profiles (tenant_id, brand_id, jurisdiction_code);

-- -------------------------------------------------------------------- quotes

CREATE TABLE pricing.quotes (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    customer_account_id uuid,
    currency char(3) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',

    -- Which menu this was priced against. A quote priced from a retired
    -- publication must not be accepted against a new one.
    catalog_publication_id uuid NOT NULL,

    -- The version of the calculation itself. Changing the pipeline changes this,
    -- so an old quote is never re-derived by new code and silently disagreed with.
    calculation_version integer NOT NULL,

    -- Covers every input the total depends on. Checkout accepts only a quote
    -- whose context still hashes to this, which is what makes "the price you
    -- were shown is the price you pay" checkable rather than hoped for.
    context_hash varchar(64) NOT NULL,

    subtotal_minor bigint NOT NULL,
    tax_minor bigint NOT NULL,
    fee_minor bigint NOT NULL DEFAULT 0,
    discount_minor bigint NOT NULL DEFAULT 0,
    total_minor bigint NOT NULL,

    -- Evidence, not the business model: the normalized columns above are what
    -- anything queries.
    calculation_document jsonb NOT NULL DEFAULT '{}'::jsonb,

    expires_at timestamptz NOT NULL,
    idempotency_key varchar(255),
    created_at timestamptz NOT NULL DEFAULT now(),
    accepted_at timestamptz,

    CONSTRAINT ck_quote_status CHECK (status IN ('ACTIVE', 'ACCEPTED', 'EXPIRED', 'SUPERSEDED')),
    CONSTRAINT ck_quote_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_quote_amounts CHECK (
        subtotal_minor >= 0 AND tax_minor >= 0 AND fee_minor >= 0
        AND discount_minor >= 0 AND total_minor >= 0
    ),
    CONSTRAINT ck_quote_accepted CHECK ((status <> 'ACCEPTED') OR (accepted_at IS NOT NULL)),
    CONSTRAINT fk_quote_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_quote_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT uq_quote_identity UNIQUE (id, tenant_id)
);

-- A repeated quote request returns the first quote rather than a second one, so
-- a retried checkout cannot end up holding two reservations.
CREATE UNIQUE INDEX ux_quote_idempotency
    ON pricing.quotes (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX ix_quotes_expiry ON pricing.quotes (expires_at) WHERE status = 'ACTIVE';

CREATE TABLE pricing.quote_lines (
    quote_id uuid NOT NULL,
    line_id varchar(64) NOT NULL,
    tenant_id uuid NOT NULL,
    source_variant_id uuid NOT NULL,
    quantity integer NOT NULL,

    -- The name as it was shown. Copied, not referenced: a menu rename must not
    -- change what a historical quote says the customer was buying.
    description_snapshot varchar(255) NOT NULL,

    unit_amount_minor bigint NOT NULL,
    base_amount_minor bigint NOT NULL,
    final_amount_minor bigint NOT NULL,
    tax_amount_minor bigint NOT NULL,

    PRIMARY KEY (quote_id, line_id),
    CONSTRAINT ck_quote_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_quote_line_amounts CHECK (
        unit_amount_minor >= 0 AND base_amount_minor >= 0
        AND final_amount_minor >= 0 AND tax_amount_minor >= 0
    ),
    CONSTRAINT fk_quote_line_quote FOREIGN KEY (quote_id) REFERENCES pricing.quotes (id)
        ON DELETE CASCADE
);

-- Every change to an amount, with what caused it. Without this a customer
-- asking "why is this 2000 som" has no answer, and neither does an auditor.
CREATE TABLE pricing.quote_adjustments (
    quote_id uuid NOT NULL,
    sequence integer NOT NULL,
    tenant_id uuid NOT NULL,
    line_id varchar(64),
    adjustment_type varchar(32) NOT NULL,
    source_type varchar(32) NOT NULL,
    source_id uuid,
    source_version integer,
    amount_minor bigint NOT NULL,
    description_code varchar(64) NOT NULL,

    PRIMARY KEY (quote_id, sequence),
    CONSTRAINT ck_adjustment_type CHECK (
        adjustment_type IN ('BASE_PRICE', 'MODIFIER', 'ITEM_DISCOUNT', 'ORDER_DISCOUNT',
                            'FEE', 'TAX', 'ROUNDING')
    ),
    CONSTRAINT fk_adjustment_quote FOREIGN KEY (quote_id) REFERENCES pricing.quotes (id)
        ON DELETE CASCADE
);

-- ---------------------------------------------------------------- inventory

CREATE TABLE inventory.stock_items (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    variant_id uuid NOT NULL,

    -- BINARY and UNTRACKED are implemented; QUANTITY is accepted by the schema
    -- and refused by the service, because a half-built quantity path that
    -- silently allows overselling is worse than an explicit refusal.
    tracking_mode varchar(16) NOT NULL DEFAULT 'BINARY',
    unit_code varchar(16) NOT NULL DEFAULT 'PIECE',
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_stock_item_mode CHECK (tracking_mode IN ('QUANTITY', 'BINARY', 'UNTRACKED')),
    CONSTRAINT ck_stock_item_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT fk_stock_item_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_stock_item_variant FOREIGN KEY (variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id),
    CONSTRAINT uq_stock_item UNIQUE (tenant_id, location_id, variant_id),
    CONSTRAINT uq_stock_item_identity UNIQUE (id, tenant_id)
);

CREATE TABLE inventory.positions (
    stock_item_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    on_hand_quantity numeric(14, 3) NOT NULL DEFAULT 0,
    reserved_quantity numeric(14, 3) NOT NULL DEFAULT 0,
    -- Null for QUANTITY and UNTRACKED items; the whole state for BINARY ones.
    binary_available boolean,
    -- Increments on every change, so a consumer can tell a stale read from a
    -- current one without comparing timestamps across machines.
    position_sequence bigint NOT NULL DEFAULT 0,
    version integer NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_position_quantities CHECK (on_hand_quantity >= 0 AND reserved_quantity >= 0),
    CONSTRAINT fk_position_stock_item FOREIGN KEY (stock_item_id, tenant_id)
        REFERENCES inventory.stock_items (id, tenant_id)
);

-- Append-only. Position is derived state that can be rebuilt; this is the record
-- of what actually happened, and an UPDATE here would destroy the only evidence
-- of why a position is what it is.
CREATE TABLE inventory.movements (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    sequence_number bigint NOT NULL,
    movement_type varchar(32) NOT NULL,
    quantity_delta numeric(14, 3) NOT NULL DEFAULT 0,
    binary_state boolean,
    source_type varchar(32) NOT NULL,
    source_id uuid,
    idempotency_key varchar(255) NOT NULL,
    reason_code varchar(64),
    actor_type varchar(16) NOT NULL,
    actor_id uuid,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_movement_type CHECK (
        movement_type IN ('RECEIPT', 'SALE_COMMITMENT', 'RELEASE', 'RETURN', 'WASTE',
                          'CORRECTION', 'POS_RECONCILIATION', 'ADMIN_ADJUSTMENT',
                          'AVAILABILITY_CHANGE')
    ),
    CONSTRAINT ck_movement_actor CHECK (actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'PROVIDER')),
    CONSTRAINT fk_movement_stock_item FOREIGN KEY (stock_item_id, tenant_id)
        REFERENCES inventory.stock_items (id, tenant_id),
    CONSTRAINT uq_movement_sequence UNIQUE (tenant_id, stock_item_id, sequence_number),
    -- Replaying the same command cannot move stock twice.
    CONSTRAINT uq_movement_idempotency UNIQUE (tenant_id, stock_item_id, idempotency_key)
);

CREATE INDEX ix_movements_by_item ON inventory.movements (stock_item_id, sequence_number DESC);

CREATE TABLE inventory.reservations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    owner_type varchar(16) NOT NULL,
    owner_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'HELD',
    -- 15 minutes, matching the ADR 0018 quote TTL. A reservation outliving its
    -- quote would hold stock for a price nobody can still accept.
    expires_at timestamptz NOT NULL,
    idempotency_key varchar(255),
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_reservation_status CHECK (
        status IN ('HELD', 'COMMITTED', 'RELEASED', 'EXPIRED')
    ),
    CONSTRAINT ck_reservation_owner CHECK (owner_type IN ('QUOTE', 'ORDER')),
    CONSTRAINT fk_reservation_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    -- One live reservation per owner. Without this a retried checkout holds the
    -- same stock twice and the second release looks like a leak.
    CONSTRAINT uq_reservation_owner UNIQUE (tenant_id, owner_type, owner_id),
    CONSTRAINT uq_reservation_identity UNIQUE (id, tenant_id)
);

CREATE INDEX ix_reservations_expiry ON inventory.reservations (expires_at) WHERE status = 'HELD';

CREATE TABLE inventory.reservation_lines (
    reservation_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    quantity numeric(14, 3) NOT NULL,

    PRIMARY KEY (reservation_id, stock_item_id),
    CONSTRAINT ck_reservation_line_quantity CHECK (quantity > 0),
    CONSTRAINT fk_reservation_line_reservation FOREIGN KEY (reservation_id, tenant_id)
        REFERENCES inventory.reservations (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_reservation_line_stock_item FOREIGN KEY (stock_item_id, tenant_id)
        REFERENCES inventory.stock_items (id, tenant_id)
);

GRANT USAGE ON SCHEMA pricing, inventory TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA pricing TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA inventory TO horecaos_application;

-- The ledger is evidence. Nothing may rewrite it.
REVOKE UPDATE ON inventory.movements FROM horecaos_application;
