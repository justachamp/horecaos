-- T14 (ADR 0134) adversarial review, batch 6: ABC/XYZ classification ranks
-- revenue.gross.v1 -- a money metric, ADR 0038 grain DAY_LOCATION_LEGAL_ENTITY
-- -- straight out of reporting.fact_order_line, which has never carried a
-- legal_entity_id at all. On a tenant trading as more than one taxpayer, every
-- run's revenue ranking and ABC class combined gross revenue across entities
-- with no way to narrow and no refusal, unlike every other money read this
-- build ships (ReportQueryService's CombinedEntityTotalException family).
--
-- The same shape V0368 already used for occurred_at: fact_order carries its
-- own legal_entity_id (V0031), DayCloseService writes both facts for the same
-- order under the same business_date in the same close, so the (tenant_id,
-- business_date, order_id) join is exact, not an approximation, and the value
-- is copied at write time rather than joined back at read time.
--
-- Kept nullable, matching fact_order.legal_entity_id's own nullability: null
-- means no fiscal identity was recorded on the order, which is its own group
-- and never folded into an entity that did not sell it (see that column's own
-- comment in V0031). Unlike occurred_at, this is NOT forced NOT NULL after
-- backfill -- fact_order's column itself allows null, and a line without a
-- matching order fact should read the same "unknown identity" way a line on
-- an order with no recorded legal_entity_id already does, not report a
-- fabricated entity.
ALTER TABLE reporting.fact_order_line
    ADD COLUMN legal_entity_id uuid;

UPDATE reporting.fact_order_line l
   SET legal_entity_id = o.legal_entity_id
  FROM reporting.fact_order o
 WHERE o.tenant_id = l.tenant_id
   AND o.business_date = l.business_date
   AND o.order_id = l.order_id;

COMMENT ON COLUMN reporting.fact_order_line.legal_entity_id IS
    'ADR 0038, copied from the sibling reporting.fact_order row''s own legal_entity_id at close time -- the same choice occurred_at (V0368) already makes, so ProductClassificationService can filter or refuse a run by legal entity without a join back to fact_order. Null means no fiscal identity was recorded on the order, its own group, never folded into a real entity.';

-- No new GRANT: reporting.fact_order_line already carries
-- "GRANT SELECT, INSERT, UPDATE, DELETE ... TO horecaos_application" from
-- V0031, which covers every column, this one included.
