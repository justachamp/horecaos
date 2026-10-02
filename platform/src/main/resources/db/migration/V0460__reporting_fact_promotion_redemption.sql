-- ADR 0140 / ADR 0043 (row 7.9): promotion spend is visible after the fact.
--
-- One row per (order, promotion), built at day close by DayCloseService through
-- the `pricing.api` port `PromotionRedemptionSource`, never by a report reading
-- `pricing` tables (ADR 0023). Its sources are the new ledger (automatic
-- promotions) and `coupon_redemptions` (promo codes); benefit grants join later.
--
-- `customer_subject_hash` is the ADR 0029 keyed hash and never an account id,
-- which is the honest limit the record states: a manager cannot open the customer
-- from a redemption row. The coupon's code word never reaches the fact; it
-- carries `coupon_id` only.

CREATE TABLE reporting.fact_promotion_redemption (
    tenant_id uuid NOT NULL,
    redemption_id uuid NOT NULL,
    business_date date NOT NULL,
    boundary_version integer NOT NULL,
    metric_calculation_version integer NOT NULL,

    brand_id uuid NOT NULL,
    promotion_id uuid NOT NULL,
    promotion_code varchar(64) NOT NULL,
    definition_version integer NOT NULL,
    source_kind varchar(16) NOT NULL,
    coupon_id uuid,

    order_id uuid NOT NULL,
    customer_subject_hash varchar(128),

    discount_minor bigint NOT NULL,
    markup_minor bigint NOT NULL DEFAULT 0,
    currency char(3) NOT NULL,
    redeemed_at timestamptz NOT NULL,

    built_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_fact_promotion_redemption PRIMARY KEY (tenant_id, redemption_id),
    CONSTRAINT ck_fact_promotion_redemption_source CHECK (source_kind IN ('AUTOMATIC', 'COUPON', 'GRANT')),
    CONSTRAINT ck_fact_promotion_redemption_coupon CHECK (
        (source_kind = 'COUPON') = (coupon_id IS NOT NULL)
    ),
    CONSTRAINT ck_fact_promotion_redemption_amounts CHECK (discount_minor >= 0 AND markup_minor >= 0),
    CONSTRAINT ck_fact_promotion_redemption_currency CHECK (currency ~ '^[A-Z]{3}$')
);

COMMENT ON TABLE reporting.fact_promotion_redemption IS
    'ADR 0140, ADR 0043 (7.9). Derived and rebuildable from the promotion ledger and the coupon redemptions through PromotionRedemptionSource -- see DayCloseService. One row per (order, promotion). A cancelled order stays in the log and is excluded from the summary metrics by joining fact_order.';

CREATE INDEX ix_fact_promotion_redemption_promotion ON reporting.fact_promotion_redemption
    (tenant_id, promotion_id, business_date);
CREATE INDEX ix_fact_promotion_redemption_brand ON reporting.fact_promotion_redemption
    (tenant_id, brand_id, business_date);
CREATE INDEX ix_fact_promotion_redemption_order ON reporting.fact_promotion_redemption
    (tenant_id, order_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.fact_promotion_redemption TO horecaos_application;
