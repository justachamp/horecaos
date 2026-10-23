-- ADR 0140 / ADR 0043 / ADR 0038 (batch 21, wave w6-promotions-reviews-approvals): the promo.* metrics.
--
-- A promotion's spend is money, and ADR 0038 refuses a money metric below a legal-entity grain: one tenant can
-- trade as two companies on the same evening, and a discount total that adds them reconciles to neither tax
-- filing. The redemption fact (V0460) held the brand and the order and nothing else, which is why the promo.*
-- metrics ADR 0140 names could not be registered. The order's branch and legal entity are already in
-- reporting.fact_order (V0031), written from the payment intent that priced the order, so the fact copies them
-- from there at day close instead of asking the pricing module for facts it does not hold.
--
-- Both columns are null until the order's own fact exists (an order whose business day closes after its
-- redemption's does), and legal_entity_id stays null for an order with no fiscal identity recorded: the money
-- reports treat that as a bucket of its own and never fold it into an entity (V0031's comment).

ALTER TABLE reporting.fact_promotion_redemption
    ADD COLUMN location_id uuid,
    ADD COLUMN legal_entity_id uuid;

COMMENT ON COLUMN reporting.fact_promotion_redemption.location_id IS
    'ADR 0140. The branch of the order this redemption belongs to, copied from reporting.fact_order at day close.';
COMMENT ON COLUMN reporting.fact_promotion_redemption.legal_entity_id IS
    'ADR 0038, ADR 0140. The legal entity that took the order''s money, copied from reporting.fact_order at day close; null where no fiscal identity was recorded.';

-- Rows written by batch 17 and 18 take the grain from their orders' facts, so a range that crosses this
-- migration reads one consistent grain.
UPDATE reporting.fact_promotion_redemption r
   SET location_id = o.location_id,
       legal_entity_id = o.legal_entity_id
  FROM reporting.fact_order o
 WHERE o.tenant_id = r.tenant_id
   AND o.order_id = r.order_id;

CREATE INDEX ix_fact_promotion_redemption_entity
    ON reporting.fact_promotion_redemption (tenant_id, legal_entity_id, business_date);

-- No new GRANT: this widens a table that already grants to horecaos_application (V0460).
