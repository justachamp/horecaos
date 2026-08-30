-- ADR 0018 stages 3 and 4: promotions, coupons, and the reservation that makes a
-- coupon limit hold under concurrency.
--
-- V0019 deliberately left these tables out, saying "their tables arrive with the
-- decisions that need them". Two of those decisions are now made and both are
-- encoded here rather than in Java, because both decide money:
--
--   Stacking is best-one-wins per group. Within one `stacking_group` exactly one
--   promotion applies -- the one that benefits the customer most -- and different
--   groups combine. An `exclusive` promotion suppresses every other promotion in
--   the cart. The engine implements this; the columns are what it reads.
--
--   A rule is data, never code. `attributes_json` carries the operands of a
--   condition or an action whose *type* is a checked enumeration, so the set of
--   things a promotion can do is fixed at schema level. ADR 0018 rejects a
--   scripting engine outright: arbitrary code on the pricing path is
--   unreviewable, non-deterministic across versions, and a code-execution
--   surface driven by control-plane input.
--
-- All money is integer minor units. For UZS that is whole som (ADR 0018).

-- ------------------------------------------------------------------ promotions

CREATE TABLE pricing.promotions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    -- The operator's own stable handle. Unique per brand so an operator can say
    -- "suspend SUMMER20" without holding a UUID.
    code varchar(64) NOT NULL,
    name varchar(200) NOT NULL,

    -- Which pipeline stage applies it. ITEM is stage 3 and lands on matching
    -- lines; ORDER and DELIVERY are stage 4 and land on the cart.
    scope varchar(16) NOT NULL,

    -- DRAFT is being written. VALIDATED passed the rule checker and may be
    -- submitted for approval. ACTIVE is applied to carts. SUSPENDED is a
    -- reversible stop. ARCHIVED is history and is never applied again.
    --
    -- The engine reads ACTIVE and nothing else, so a promotion that fails
    -- validation cannot reach a customer by any route.
    status varchar(16) NOT NULL DEFAULT 'DRAFT',

    -- Best-one-wins is decided within a group. Two promotions an operator
    -- intends as alternatives share a group; two they intend to combine do not.
    stacking_group varchar(64) NOT NULL,

    -- Suppresses every other promotion in the cart, in any group. The escape
    -- hatch for "this offer is instead of everything, not as well as".
    exclusive boolean NOT NULL DEFAULT false,

    -- Settles a tie deterministically when two promotions in one group produce
    -- the same benefit. Row order must never decide a discount.
    priority integer NOT NULL DEFAULT 0,

    -- True when the promotion applies only if the customer presented one of its
    -- coupon codes. False is an automatic promotion, which is what a menu
    -- strikethrough is built from.
    requires_coupon boolean NOT NULL DEFAULT false,

    -- The most this promotion may ever take off, whatever its action computes.
    -- Null is uncapped, which is legitimate for a fixed-amount action and is a
    -- mistake waiting to happen for a percentage one; the validator says so.
    maximum_discount_minor bigint,
    currency char(3) NOT NULL,

    valid_from timestamptz NOT NULL,
    valid_until timestamptz,

    -- Bumped whenever a condition or an action changes. Recorded on every quote
    -- adjustment this promotion produces, so an old quote is explainable against
    -- the rule that actually priced it rather than against today's rule.
    definition_version integer NOT NULL DEFAULT 1,

    -- ADR 0031 optimistic concurrency for the authoring endpoints.
    version integer NOT NULL DEFAULT 1,

    validated_at timestamptz,
    activated_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_promotion_scope CHECK (scope IN ('ITEM', 'ORDER', 'DELIVERY')),
    CONSTRAINT ck_promotion_status CHECK (
        status IN ('DRAFT', 'VALIDATED', 'ACTIVE', 'SUSPENDED', 'ARCHIVED')
    ),
    CONSTRAINT ck_promotion_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_promotion_window CHECK (valid_until IS NULL OR valid_until > valid_from),
    -- Zero is not a cap, it is a promotion that does nothing. Catching it here
    -- beats an operator wondering why their offer has no effect.
    CONSTRAINT ck_promotion_maximum CHECK (
        maximum_discount_minor IS NULL OR maximum_discount_minor > 0
    ),
    -- A promotion that reached a customer was validated first. This is the
    -- database's half of "the engine reads ACTIVE and nothing else".
    CONSTRAINT ck_promotion_validated CHECK (
        status NOT IN ('ACTIVE', 'SUSPENDED') OR validated_at IS NOT NULL
    ),
    CONSTRAINT ck_promotion_activated CHECK (
        status <> 'ACTIVE' OR activated_at IS NOT NULL
    ),
    CONSTRAINT fk_promotion_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT uq_promotion_code UNIQUE (tenant_id, brand_id, code),
    -- Exactly the columns every child table references, per the rule that a
    -- foreign key must match a unique constraint on its own columns.
    CONSTRAINT uq_promotion_identity UNIQUE (id, tenant_id, brand_id)
);

-- The engine's own read: every promotion that could apply to this brand now.
-- Partial on ACTIVE because that is the only status it ever asks for, and a
-- brand accumulates archived promotions indefinitely.
CREATE INDEX ix_promotions_active
    ON pricing.promotions (tenant_id, brand_id, scope, priority DESC)
    WHERE status = 'ACTIVE';

-- ------------------------------------------------------- conditions and actions

CREATE TABLE pricing.promotion_conditions (
    promotion_id uuid NOT NULL,
    sequence integer NOT NULL,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    -- The checked vocabulary. Adding a type is a migration and a code change
    -- together, which is the point: an operator cannot invent a predicate.
    condition_type varchar(32) NOT NULL,

    -- The operands only. A PRODUCT condition carries {"productIds": [...]}; a
    -- SUBTOTAL_AT_LEAST carries {"amountMinor": 50000}. JSONB because the operand
    -- shape genuinely differs per type; the validator checks each against its
    -- type before a promotion may leave DRAFT.
    attributes_json jsonb NOT NULL DEFAULT '{}'::jsonb,

    PRIMARY KEY (promotion_id, sequence),
    CONSTRAINT ck_promotion_condition_type CHECK (
        condition_type IN (
            'PRODUCT', 'CATEGORY', 'VARIANT',
            'QUANTITY_AT_LEAST', 'SUBTOTAL_AT_LEAST',
            'CHANNEL', 'LOCATION', 'FULFILLMENT_MODE',
            'DAY_OF_WEEK', 'TIME_OF_DAY',
            'FIRST_ORDER', 'CUSTOMER_SEGMENT'
        )
    ),
    CONSTRAINT fk_promotion_condition FOREIGN KEY (promotion_id, tenant_id, brand_id)
        REFERENCES pricing.promotions (id, tenant_id, brand_id) ON DELETE CASCADE
);

CREATE TABLE pricing.promotion_actions (
    promotion_id uuid NOT NULL,
    sequence integer NOT NULL,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    action_type varchar(32) NOT NULL,

    -- Operands: a percentage in basis points, a fixed amount in minor units, or
    -- a bounded quantity. Integers throughout -- ADR 0018 forbids a rate that is
    -- a float, because two machines would round it differently.
    attributes_json jsonb NOT NULL DEFAULT '{}'::jsonb,

    PRIMARY KEY (promotion_id, sequence),
    CONSTRAINT ck_promotion_action_type CHECK (
        action_type IN (
            'ITEM_PERCENTAGE_DISCOUNT', 'ITEM_FIXED_DISCOUNT', 'ITEM_FIXED_PRICE',
            'ORDER_PERCENTAGE_DISCOUNT', 'ORDER_FIXED_DISCOUNT',
            'FREE_DELIVERY', 'REDUCED_DELIVERY',
            'FREE_ITEM'
        )
    ),
    CONSTRAINT fk_promotion_action FOREIGN KEY (promotion_id, tenant_id, brand_id)
        REFERENCES pricing.promotions (id, tenant_id, brand_id) ON DELETE CASCADE
);

-- --------------------------------------------------------------------- coupons

CREATE TABLE pricing.coupon_codes (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    promotion_id uuid NOT NULL,

    -- The code is stored as a SHA-256 of its normalized (upper-cased, trimmed)
    -- form and never in the clear. A coupon code is a bearer secret: anybody who
    -- reads the table can spend it, and a database dump or a log line must not
    -- be a pile of working discounts. Lookup is by hash, which is exactly what a
    -- customer presenting a code allows.
    normalized_code_hash char(64) NOT NULL,

    -- What an operator needs in order to recognise a row they cannot read back.
    -- Four characters is enough to find a code in a list and not enough to guess
    -- one.
    code_hint varchar(8),

    status varchar(16) NOT NULL DEFAULT 'ACTIVE',

    -- Null is unlimited. The counter below is what actually enforces it.
    maximum_redemptions integer,
    maximum_per_customer integer NOT NULL DEFAULT 1,

    -- Reserved *and* redeemed. A reservation held by a live quote has already
    -- taken a slot, because ADR 0018 refuses to let a discount the customer was
    -- shown vanish at checkout under concurrency.
    consumed_count integer NOT NULL DEFAULT 0,

    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_coupon_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'EXHAUSTED', 'ARCHIVED')),
    CONSTRAINT ck_coupon_window CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_coupon_hash CHECK (normalized_code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_coupon_maximum CHECK (maximum_redemptions IS NULL OR maximum_redemptions > 0),
    CONSTRAINT ck_coupon_per_customer CHECK (maximum_per_customer > 0),
    -- The global limit, as a database rule rather than a Java pre-check. Under
    -- concurrent reservation the row lock serialises the increment and this
    -- constraint is what refuses the one that would go over -- a service-layer
    -- count would read a stale total and let both through.
    CONSTRAINT ck_coupon_consumed CHECK (
        consumed_count >= 0
        AND (maximum_redemptions IS NULL OR consumed_count <= maximum_redemptions)
    ),
    CONSTRAINT fk_coupon_promotion FOREIGN KEY (promotion_id, tenant_id, brand_id)
        REFERENCES pricing.promotions (id, tenant_id, brand_id),
    CONSTRAINT uq_coupon_identity UNIQUE (id, tenant_id)
);

-- One live code per brand. Scoped to the brand rather than globally so two
-- brands may each run SUMMER20 without one of them failing to create it.
CREATE UNIQUE INDEX ux_coupon_code
    ON pricing.coupon_codes (tenant_id, brand_id, normalized_code_hash)
    WHERE status <> 'ARCHIVED';

CREATE INDEX ix_coupons_promotion ON pricing.coupon_codes (tenant_id, promotion_id);

-- Per-customer limits, as their own rows so the cap is a database rule too.
--
-- `maximum_per_customer` is copied from the coupon rather than joined, because a
-- CHECK cannot reach another table. The authoring path rewrites every row of a
-- coupon in the same transaction that changes its cap, so the copy is not a
-- snapshot that drifts.
CREATE TABLE pricing.coupon_customer_usage (
    coupon_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,
    consumed_count integer NOT NULL DEFAULT 0,
    maximum_per_customer integer NOT NULL,

    PRIMARY KEY (coupon_id, customer_account_id),
    CONSTRAINT ck_coupon_usage_count CHECK (
        consumed_count >= 0 AND consumed_count <= maximum_per_customer
    ),
    CONSTRAINT ck_coupon_usage_maximum CHECK (maximum_per_customer > 0),
    CONSTRAINT fk_coupon_usage_coupon FOREIGN KEY (coupon_id, tenant_id)
        REFERENCES pricing.coupon_codes (id, tenant_id) ON DELETE CASCADE
);

-- Every reservation, release and redemption, kept as rows rather than as a
-- counter alone. The counter says how many slots are gone; this says who has
-- them and why, which is what a dispute about a coupon is answered from.
CREATE TABLE pricing.coupon_redemptions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    coupon_id uuid NOT NULL,
    promotion_id uuid NOT NULL,

    -- Null for a coupon spent by a caller with no account. The per-customer cap
    -- simply does not apply to those, and pretending otherwise by inventing an
    -- identifier would make two strangers share a limit.
    customer_account_id uuid,

    -- The quote that holds the reservation. ADR 0018: usage is reserved with the
    -- quote and never first at order creation, so the discount a customer was
    -- shown is one they can still spend fifteen minutes later.
    quote_id uuid NOT NULL,
    order_id uuid,

    status varchar(16) NOT NULL DEFAULT 'RESERVED',
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,

    reserved_at timestamptz NOT NULL DEFAULT now(),
    redeemed_at timestamptz,
    released_at timestamptz,

    CONSTRAINT ck_redemption_status CHECK (status IN ('RESERVED', 'REDEEMED', 'RELEASED')),
    CONSTRAINT ck_redemption_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_redemption_amount CHECK (amount_minor >= 0),
    CONSTRAINT ck_redemption_redeemed CHECK (
        (status <> 'REDEEMED') OR (redeemed_at IS NOT NULL AND order_id IS NOT NULL)
    ),
    CONSTRAINT ck_redemption_released CHECK (
        (status <> 'RELEASED') OR released_at IS NOT NULL
    ),
    CONSTRAINT fk_redemption_coupon FOREIGN KEY (coupon_id, tenant_id)
        REFERENCES pricing.coupon_codes (id, tenant_id),
    -- Denormalized from the coupon so a redemption can be read and reported on
    -- without a join, and carried as a real foreign key rather than a loose id:
    -- an unconstrained promotion_id is how a redemption ends up naming a
    -- promotion of another brand and nothing notices.
    CONSTRAINT fk_redemption_promotion FOREIGN KEY (promotion_id, tenant_id, brand_id)
        REFERENCES pricing.promotions (id, tenant_id, brand_id),
    CONSTRAINT fk_redemption_quote FOREIGN KEY (quote_id, tenant_id)
        REFERENCES pricing.quotes (id, tenant_id)
);

-- One live reservation per coupon per quote. A retried pricing request must
-- re-use its reservation rather than take a second slot -- which is how a
-- customer refreshing the cart page could otherwise exhaust their own coupon.
CREATE UNIQUE INDEX ux_redemption_quote
    ON pricing.coupon_redemptions (coupon_id, quote_id)
    WHERE status <> 'RELEASED';

CREATE INDEX ix_redemptions_customer
    ON pricing.coupon_redemptions (tenant_id, customer_account_id)
    WHERE customer_account_id IS NOT NULL;

-- Drives the sweep that releases reservations whose quote expired unaccepted.
CREATE INDEX ix_redemptions_reserved
    ON pricing.coupon_redemptions (reserved_at)
    WHERE status = 'RESERVED';

-- ---------------------------------------------------------------------- grants
--
-- Explicit, and in this migration. A GRANT ... ON ALL TABLES IN SCHEMA covers
-- only the tables that existed when it ran, so V0019's grants do not reach any
-- table above. Nine migrations have made exactly this mistake; V0035 exists to
-- repair them, and the failure is invisible until the application connects.

GRANT SELECT, INSERT, UPDATE ON pricing.promotions TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON pricing.promotion_conditions TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON pricing.promotion_actions TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON pricing.coupon_codes TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON pricing.coupon_customer_usage TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON pricing.coupon_redemptions TO horecaos_application;
