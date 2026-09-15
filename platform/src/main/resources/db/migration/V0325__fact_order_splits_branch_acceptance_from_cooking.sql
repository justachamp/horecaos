-- Wave P27 (7.2): the «Этапы» tab reads seconds_to_ready as "kitchen time",
-- but V0031 defined it as CONFIRMED -> READY — wider than the spec's
-- «Приготовлен», because it also carries whatever time the order sat between
-- being confirmed and the kitchen actually starting production. A branch
-- that lets a confirmed order wait eleven minutes before anyone touches it
-- reads, on today's screen, as eleven minutes of cooking.
--
-- ordering.order_state_history already records every status transition with
-- its own occurred_at (readSourceOrders already mines it for READY, below).
-- PREPARING is one more row in that same history — the moment the order
-- left CONFIRMED for production — and needed no new source data, only two
-- more columns to hold the split:
--
--   seconds_to_accept   CONFIRMED  -> PREPARING  ("branch acceptance": the
--                                                  wait this migration adds
--                                                  a column for)
--   seconds_preparing   PREPARING  -> READY       (actual cooking — narrower
--                                                  than seconds_to_ready,
--                                                  which stays as it was for
--                                                  every existing reader)
--
-- seconds_to_ready is not removed or redefined: fact_order is derived and
-- rebuildable (V0031's own table comment), but changing what an existing,
-- possibly-signed metric's source column means without a version bump is
-- exactly the silent-redefinition ADR 0043 exists to prevent. The two new
-- columns are additive; existing readers of seconds_to_ready are unaffected.
--
-- Historical rows are left null by this ALTER, the same choice
-- V0274__fact_order_gains_an_operator_principal.sql made for
-- operator_principal_id: the honest fix for a day already closed is a
-- recut, not a backfill guessing at a PREPARING transition this column
-- never recorded.
ALTER TABLE reporting.fact_order
    ADD COLUMN seconds_to_accept integer,
    ADD COLUMN seconds_preparing integer;

COMMENT ON COLUMN reporting.fact_order.seconds_to_accept IS
    'ADR 0043 (wave P27). CONFIRMED -> PREPARING: how long a confirmed order waited before the kitchen started it — "branch acceptance". Null when the order never reached PREPARING, or on a row closed before this migration and never recut.';
COMMENT ON COLUMN reporting.fact_order.seconds_preparing IS
    'ADR 0043 (wave P27). PREPARING -> READY: actual cooking time, narrower than seconds_to_ready (CONFIRMED -> READY, unchanged). Null when the order never reached READY from PREPARING, or on a row closed before this migration and never recut.';

-- No GRANT block: fact_order already has one from V0031, and a GRANT is
-- per-table, not per-column (see V0274's own closing comment).
