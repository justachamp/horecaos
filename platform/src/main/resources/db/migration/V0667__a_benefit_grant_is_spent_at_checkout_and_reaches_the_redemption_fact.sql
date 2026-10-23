-- ADR 0140 (status line "benefit grants as a redemption fact source"), ADR 0044, ADR 0072; gap-map rows
-- 6.1 and 5.4 (batch 21, wave w6-promotions-reviews-approvals).
--
-- A one-off coded benefit grant (V0265) could be minted and could never be spent: BenefitGrantService#redeem had
-- no caller outside tests. A presented code now resolves to the customer's grant in the quote, the grant is
-- claimed at checkout with the same conditional UPDATE V0265 built, and what it gave lands in the day-close
-- redemption fact as source_kind GRANT.

-- ---------------------------------------------------------------- the grant row

-- What the grant took off the order it was spent on, written by the claim and moved in place by an amendment
-- (the grant is the ledger row for a one-off code: there is one spend, so there is no separate ledger table).
ALTER TABLE pricing.benefit_grants
    ADD COLUMN redeemed_discount_minor bigint,
    ADD COLUMN redeemed_currency char(3);

ALTER TABLE pricing.benefit_grants
    ADD CONSTRAINT ck_benefit_grant_redeemed_amount CHECK (
        (redeemed_discount_minor IS NULL AND redeemed_currency IS NULL)
        OR (redeemed_discount_minor >= 0 AND redeemed_currency ~ '^[A-Z]{3}$')
    );

COMMENT ON COLUMN pricing.benefit_grants.redeemed_discount_minor IS
    'ADR 0140. What the grant took off the order it was spent on, in minor units; set by the claim at checkout, restated by an amendment, cleared when a failed checkout gives the grant back.';

-- A checkout that fails after claiming gives the grant back by the quote it claimed under, and an amendment finds
-- the grant an order holds by the order. Both are point reads on a REDEEMED row.
CREATE INDEX ix_benefit_grants_claimed_quote
    ON pricing.benefit_grants (tenant_id, quote_id)
    WHERE status = 'REDEEMED' AND quote_id IS NOT NULL;

CREATE INDEX ix_benefit_grants_held_order
    ON pricing.benefit_grants (tenant_id, order_id)
    WHERE status = 'REDEEMED';

-- The day close reads the grants spent in a window.
CREATE INDEX ix_benefit_grants_redeemed_at
    ON pricing.benefit_grants (tenant_id, redeemed_at)
    WHERE status = 'REDEEMED';

-- --------------------------------------------------------- the redemption fact

-- A grant has no promotion and no definition version: promotion_id is empty for it and grant_id says which grant.
-- The grant id is the redemption id (one spend per grant), so the fact stays one row per (order, redemption).
-- promotion_code keeps its NOT NULL: a grant row carries 'GRANT:' and the grant's source, which is a label and
-- never the code word.
ALTER TABLE reporting.fact_promotion_redemption
    ALTER COLUMN promotion_id DROP NOT NULL;

ALTER TABLE reporting.fact_promotion_redemption
    ADD COLUMN grant_id uuid;

ALTER TABLE reporting.fact_promotion_redemption
    ADD CONSTRAINT ck_fact_promotion_redemption_grant CHECK (
        (source_kind = 'GRANT') = (grant_id IS NOT NULL)
    ),
    ADD CONSTRAINT ck_fact_promotion_redemption_promotion CHECK (
        (source_kind = 'GRANT') = (promotion_id IS NULL)
    );

COMMENT ON COLUMN reporting.fact_promotion_redemption.grant_id IS
    'ADR 0140. The benefit grant a GRANT redemption spent; null for AUTOMATIC and COUPON. Never the code word.';

-- No new GRANT: this widens a table that already grants to horecaos_application (V0265, V0460).
