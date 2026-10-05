-- ADR 0136 / ADR 0043 (rows 4.2a, 7.7): "sales by combo", read from the reporting facts.
--
-- A combo is sold as several ordinary order lines that share a combo_selection_id, and the
-- container the customer bought is never a line (V0446). reporting.fact_order_line was built
-- from those lines and kept none of the grouping, so a report asking "how many Lunch boxes
-- were sold, and for how much" had nothing to count: the components appear as ordinary dishes
-- in the per-variant sales and the combo they were bought in is gone.
--
-- Four columns, copied from the order line at day close the way occurred_at and
-- legal_entity_id are (V0368, V0370), so the report reads the fact alone (ADR 0023: no
-- reporting read reaches into ordering):
--
--   combo_selection_id          groups the component lines of one purchase
--   combo_container_variant_id  which combo it was
--   combo_quantity              how many combos that purchase was (the same on every component
--                               line of one selection: the components' own quantities are this
--                               times what each pick asked for, so they cannot be summed back
--                               into "how many combos")
--   combo_name_snapshot         the name the combo was sold under, copied and never joined, so a
--                               combo renamed next month does not rename last month's report
--
-- Set together or not at all, and null on every row that is not a combo component -- which is
-- the vast majority, forever. Nothing about an existing row, reader or constraint changes.

ALTER TABLE reporting.fact_order_line
    ADD COLUMN combo_selection_id uuid,
    ADD COLUMN combo_container_variant_id uuid,
    ADD COLUMN combo_quantity integer,
    ADD COLUMN combo_name_snapshot varchar(255);

ALTER TABLE reporting.fact_order_line
    ADD CONSTRAINT ck_fact_order_line_combo CHECK (
        (combo_selection_id IS NULL) = (combo_container_variant_id IS NULL)
        AND (combo_selection_id IS NULL) = (combo_quantity IS NULL)
        AND (combo_selection_id IS NULL) = (combo_name_snapshot IS NULL)
        AND (combo_quantity IS NULL OR combo_quantity > 0)
    );

-- An amendment never edits a line: it closes it (ordering.order_lines.revision_to) and appends
-- its replacement. Day close read every row of an order, closed ones included, so an amended
-- order's facts held both versions of each rewritten line and a sum over them counted the
-- order twice. The reader now takes live lines only (JdbcReportingStore#readSourceLines); the
-- facts already built from a closed line are history that no live order has, and are removed so
-- the days that are never rebuilt agree with the days that are.
DELETE FROM reporting.fact_order_line f
 USING ordering.order_lines l
 WHERE l.tenant_id = f.tenant_id
   AND l.id = f.line_id
   AND l.revision_to IS NOT NULL;

-- Combos exist since batch 17 (V0443-V0447), so a pilot database can already hold facts built
-- before these columns did. The same values are on the order line they were built from, and day
-- close writes them the next time it rebuilds that day; filling them now means the report is
-- right for days that are never rebuilt.
UPDATE reporting.fact_order_line f
   SET combo_selection_id = l.combo_selection_id,
       combo_container_variant_id = l.combo_container_variant_id,
       combo_quantity = l.combo_quantity,
       combo_name_snapshot = l.combo_name_snapshot
  FROM ordering.order_lines l
 WHERE l.tenant_id = f.tenant_id
   AND l.id = f.line_id
   AND l.combo_selection_id IS NOT NULL
   AND f.combo_selection_id IS NULL;

-- The report is "per container over a date range", so the key leads with the container. Partial,
-- like ordering's ix_order_lines_combo_selection: the table is dominated by lines that are no
-- combo's, and an index over them would be a second copy of the table for a question they
-- cannot answer.
CREATE INDEX ix_fact_order_line_combo
    ON reporting.fact_order_line (tenant_id, business_date, combo_container_variant_id)
    WHERE combo_selection_id IS NOT NULL;

COMMENT ON COLUMN reporting.fact_order_line.combo_selection_id IS
    'ADR 0136. Copied from ordering.order_lines at day close. A report wanting how many combos were sold takes one combo_quantity per distinct value, never a sum over the component lines.';
COMMENT ON COLUMN reporting.fact_order_line.combo_container_variant_id IS
    'ADR 0136. The combo the component line was bought as part of. Null on every line that is no combo''s.';
COMMENT ON COLUMN reporting.fact_order_line.combo_quantity IS
    'ADR 0136. How many combos the purchase was; the same on every component line of one selection.';
COMMENT ON COLUMN reporting.fact_order_line.combo_name_snapshot IS
    'ADR 0136. The combo''s name as it was sold. Copied, never joined.';
