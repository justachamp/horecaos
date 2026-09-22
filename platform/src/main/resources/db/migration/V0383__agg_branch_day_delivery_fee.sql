-- Wave 8 w7-reports (7.2c): the daily summary («Сводка») needs a
-- delivery-fee-inclusive total column, matching Delever's report 1
-- (statistics.md §2.2). reporting.fact_order.delivery_fee_som has existed
-- since V0031 -- it is already folded into gross_revenue_som (see
-- DayCloseService#toFact's own comment: gross = total + discount, and
-- ADR 0019's total already includes the fee) -- but reporting.agg_branch_day,
-- the pre-aggregated table the typed /queries pipeline actually reads, has
-- never carried its own sum of it. Without this column there is no way to
-- answer "how much of that gross figure was delivery fee" at the
-- day x location x channel x fulfilment x legal-entity grain /queries groups
-- by, so a report wanting both the fee-inclusive total (already gross.v1)
-- and the fee-exclusive one (gross.v1 minus this) had no second number to
-- subtract.
--
-- NOT NULL DEFAULT 0 rather than nullable: every row this build has ever
-- closed had SOME delivery-fee total, even when it is genuinely zero (a
-- DINE_IN or PICKUP order with no delivery leg). A closed aggregate is never
-- silently recomputed (DayCloseService's own settle-recut only alerts, never
-- overwrites -- see aRecutThatDisagreesAlertsAndDoesNotOverwrite), so every
-- agg_branch_day row written BEFORE this migration reads zero here even on a
-- day that genuinely had delivery orders. This is disclosed on the
-- delivery_fee.v1 metric's own openQuestion field rather than left for
-- someone to discover by diffing two reports, and effectiveFrom on that
-- definition names the date this column starts being real.
ALTER TABLE reporting.agg_branch_day
    ADD COLUMN delivery_fee_som bigint NOT NULL DEFAULT 0;

COMMENT ON COLUMN reporting.agg_branch_day.delivery_fee_som IS
    'Sum of reporting.fact_order.delivery_fee_som for the slice''s COMPLETED orders -- already part of gross_som, never add the two together. Zero on any row closed before this column existed (2026-09-22), which is a backfill gap disclosed on the delivery_fee.v1 metric definition, not a claim that no delivery fee was charged that day.';

-- No new GRANT: reporting.agg_branch_day already carries
-- "GRANT SELECT, INSERT, UPDATE, DELETE ... TO horecaos_application" from
-- V0031, which covers every column, this one included.
