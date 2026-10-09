-- ADR 0044 / ADR 0112: a late order is apologised for once, whichever rule found it.
--
-- V0497 guarded LATE_ORDER_APOLOGY "once per order" with the guard key ORDER:<id> under
-- uq_automation_run_guard (V0415), whose columns are (tenant_id, automation_rule_id,
-- customer_account_id, guard_key). That makes the apology once per order *per rule*: a brand
-- with two armed rules (a gentle one at 15 minutes late and a firm one at 45) found the same
-- 60-minute-late order through both, claimed two distinct guard rows, and sent two apologies.
-- The guard was checking the adjacent quantity.
--
-- The order is the subject (marketing.automation_runs.subject_id), and the apology it is owed
-- is one. A firing that is sent or in flight holds it; a refused or cancelled firing sent
-- nothing, so it does not (another rule, on another channel, may still reach the guest).
-- PENDING is included because a firing claims its row before it decides, and two sweeps racing
-- for one order must meet at this index and not after both have sent.
--
-- No existing row can violate it: LATE_ORDER_APOLOGY first existed in V0497 and has not been
-- released.

CREATE UNIQUE INDEX ux_automation_run_one_apology_per_order
    ON marketing.automation_runs (tenant_id, subject_id)
    WHERE trigger_type = 'LATE_ORDER_APOLOGY' AND status IN ('PENDING', 'FIRED');

COMMENT ON INDEX marketing.ux_automation_run_one_apology_per_order IS
    'V0581. One late-order apology per order across every rule: a sent or in-flight firing holds the order; a refused or cancelled one does not.';
