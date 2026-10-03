-- ADR 0140: the three tables that make a promotion explainable, limitable and
-- reportable.
--
--   promotion_definition_versions  the rule as it was, so an old quote is
--                                  explainable against the definition that
--                                  priced it (V0093's comment promises this and
--                                  the schema did not deliver it: conditions were
--                                  overwritten in place). Append-only.
--   promotion_customer_usage       the per-customer cap, mirroring
--                                  coupon_customer_usage.
--   promotion_redemptions          one row per (order, promotion), moved in
--                                  place by an amendment, the source of report
--                                  7.9.
--
-- Every table is granted to the application role in this migration (V0035's
-- lesson: nine earlier migrations forgot, and the failure is invisible until the
-- application connects).

CREATE TABLE pricing.promotion_definition_versions (
    promotion_id uuid NOT NULL,
    definition_version integer NOT NULL,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    -- The canonical definition: the scalars, the conditions and the actions, as
    -- the engine read them. Immutable: this is evidence, not configuration.
    definition jsonb NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    recorded_by varchar(255) NOT NULL,
    -- Which lifecycle step wrote it. A validate writes the row; an activation of
    -- the same version finds it already there, because a VALIDATED promotion
    -- cannot be edited without going back to DRAFT, which bumps the version. A
    -- reorder of priorities (the only change an ACTIVE promotion accepts without
    -- going back to DRAFT) writes a new version of its own.
    reason varchar(16) NOT NULL,

    CONSTRAINT pk_promotion_definition_versions PRIMARY KEY (promotion_id, definition_version),
    CONSTRAINT ck_promotion_definition_object CHECK (jsonb_typeof(definition) = 'object'),
    CONSTRAINT ck_promotion_definition_reason CHECK (reason IN ('VALIDATED', 'ACTIVATED', 'REPRIORITISED', 'CODE_ACTIVATED')),
    CONSTRAINT fk_promotion_definition_promotion FOREIGN KEY (promotion_id, tenant_id, brand_id)
        REFERENCES pricing.promotions (id, tenant_id, brand_id)
);

CREATE INDEX ix_promotion_definition_versions_brand
    ON pricing.promotion_definition_versions (tenant_id, brand_id, promotion_id);

COMMENT ON TABLE pricing.promotion_definition_versions IS
    'ADR 0140. Immutable history of a promotion''s definition, one row per definition_version. Retention follows the order snapshot: both are evidence for orders that stay reconcilable for the financial retention period.';

CREATE TABLE pricing.promotion_customer_usage (
    promotion_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,
    consumed_count integer NOT NULL DEFAULT 0,
    -- Copied from the promotion rather than joined, because a CHECK cannot reach
    -- another table (the same device V0093 uses for coupons).
    maximum_per_customer integer NOT NULL,

    CONSTRAINT pk_promotion_customer_usage PRIMARY KEY (promotion_id, customer_account_id),
    CONSTRAINT ck_promotion_usage_count CHECK (
        consumed_count >= 0 AND consumed_count <= maximum_per_customer
    ),
    CONSTRAINT ck_promotion_usage_maximum CHECK (maximum_per_customer > 0),
    CONSTRAINT fk_promotion_usage_promotion FOREIGN KEY (promotion_id, tenant_id, brand_id)
        REFERENCES pricing.promotions (id, tenant_id, brand_id)
);

CREATE TABLE pricing.promotion_redemptions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    promotion_id uuid NOT NULL,
    -- The definition version the order was priced under. Never rewritten: an
    -- amendment keeps the definition the order was placed with (ADR 0140).
    definition_version integer NOT NULL,

    -- The checkout quote. Never rewritten; it keeps the claim idempotent, so a
    -- retried checkout cannot take a second slot.
    claimed_quote_id uuid NOT NULL,
    -- The quote behind the order's current revision. Moves in place when an
    -- amendment reprices, so there is exactly one row per (order, promotion) and
    -- report 7.9 does not double-count a redemption.
    current_quote_id uuid NOT NULL,
    last_revision integer NOT NULL DEFAULT 0,

    -- No FK to ordering.orders: the id is minted before the order row is written,
    -- inside the same checkout transaction (the same arrangement as
    -- coupon_redemptions.order_id).
    order_id uuid NOT NULL,
    customer_account_id uuid,

    -- Equal to the sum of this promotion's adjustments on the order's current
    -- revision; a test asserts it.
    discount_minor bigint NOT NULL DEFAULT 0,
    markup_minor bigint NOT NULL DEFAULT 0,
    currency char(3) NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'REDEEMED',
    redeemed_at timestamptz NOT NULL,
    released_at timestamptz,

    CONSTRAINT ck_promotion_redemption_status CHECK (status IN ('REDEEMED', 'RELEASED')),
    CONSTRAINT ck_promotion_redemption_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_promotion_redemption_amounts CHECK (discount_minor >= 0 AND markup_minor >= 0),
    CONSTRAINT ck_promotion_redemption_released CHECK (status <> 'RELEASED' OR released_at IS NOT NULL),
    CONSTRAINT fk_promotion_redemption_promotion FOREIGN KEY (promotion_id, tenant_id, brand_id)
        REFERENCES pricing.promotions (id, tenant_id, brand_id),
    CONSTRAINT fk_promotion_redemption_claimed_quote FOREIGN KEY (claimed_quote_id, tenant_id)
        REFERENCES pricing.quotes (id, tenant_id),
    CONSTRAINT fk_promotion_redemption_current_quote FOREIGN KEY (current_quote_id, tenant_id)
        REFERENCES pricing.quotes (id, tenant_id),
    CONSTRAINT uq_promotion_redemption_claim UNIQUE (tenant_id, promotion_id, claimed_quote_id),
    CONSTRAINT uq_promotion_redemption_order UNIQUE (tenant_id, order_id, promotion_id)
);

CREATE INDEX ix_promotion_redemptions_promotion
    ON pricing.promotion_redemptions (tenant_id, promotion_id, redeemed_at DESC);
CREATE INDEX ix_promotion_redemptions_order
    ON pricing.promotion_redemptions (tenant_id, order_id);

COMMENT ON TABLE pricing.promotion_redemptions IS
    'ADR 0140. One row per (order, promotion), written for every automatic promotion an order carries (limited or not) and moved in place by an amendment. The source of reporting.fact_promotion_redemption; reports never read this table directly (ADR 0023).';

GRANT SELECT, INSERT ON pricing.promotion_definition_versions TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON pricing.promotion_customer_usage TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON pricing.promotion_redemptions TO horecaos_application;
