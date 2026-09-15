-- Wave W02 (7.8a): the per-department and per-product demand breakdown needs
-- an hour to bucket a line by, and fact_order_line has never carried one --
-- ADR 0043 built it at (tenant, business_date, order, line) grain only,
-- because nothing before this wave needed finer than a day. The order this
-- line belongs to already knows its own occurred_at (fact_order does), so
-- this is a copy, the same choice fact_order_line already makes for
-- product_name_snapshot and category_id: reporting facts snapshot rather than
-- join back to another fact at read time.
--
-- Backfilled from the sibling fact_order row via the (tenant_id,
-- business_date, order_id) key both tables already share -- DayCloseService
-- writes both facts for the same order in the same close, under the same
-- business_date, so the join is exact, not an approximation.
ALTER TABLE reporting.fact_order_line
    ADD COLUMN occurred_at timestamptz;

UPDATE reporting.fact_order_line l
   SET occurred_at = o.occurred_at
  FROM reporting.fact_order o
 WHERE o.tenant_id = l.tenant_id
   AND o.business_date = l.business_date
   AND o.order_id = l.order_id
   AND l.occurred_at IS NULL;

-- A line whose order fact is missing (should not happen -- see above) would
-- otherwise leave occurred_at null forever and silently fall out of every
-- hour-bucketed read. Loud now beats silent later.
ALTER TABLE reporting.fact_order_line
    ALTER COLUMN occurred_at SET NOT NULL;

COMMENT ON COLUMN reporting.fact_order_line.occurred_at IS
    'Wave W02. Copied from the sibling reporting.fact_order row''s own occurred_at at close time. Lets a line be bucketed by operating-day hour for 7.8a without a join back to fact_order.';

-- category_id has existed since V0031 but DayCloseService has only ever
-- written it null ("needs a catalogue join ... left null rather than
-- half-resolved" -- see that class's history). This wave adds the join
-- (JdbcReportingStore#readSourceLines, going forward) and backfills existing
-- rows from catalog's CURRENT product-to-category mapping -- the same
-- approximation the forward-going join makes, since neither fact_order_line
-- nor catalog.category_products is versioned by date. A product with no
-- category, or with more than one and no declared primary, still resolves
-- to at most one: catalog.category_products carries no "primary" flag, so
-- the tie-break is the lowest sort_order then the lowest category_id,
-- applied identically here and in the forward-going read.
UPDATE reporting.fact_order_line l
   SET category_id = resolved.category_id
  FROM catalog.variants v
  JOIN LATERAL (
           SELECT cp.category_id
             FROM catalog.category_products cp
            WHERE cp.tenant_id = v.tenant_id AND cp.product_id = v.product_id
            ORDER BY cp.sort_order, cp.category_id
            LIMIT 1
       ) resolved ON true
 WHERE v.tenant_id = l.tenant_id
   AND v.id = l.variant_id
   AND l.category_id IS NULL;
