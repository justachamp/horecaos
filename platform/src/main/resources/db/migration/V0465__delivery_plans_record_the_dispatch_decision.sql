-- ADR 0142 (Accepted 2026-10-01): dispatch rules.
--
-- An operator can now decide which partner serves which zone, source or branch,
-- when auto-dispatch fires relative to prep time, and what the fallback order
-- is. The rule document (fulfillment.dispatch_rules, an ADR 0030 policy) is
-- evaluated ONCE, when the plan is created, and the result is stored on the plan
-- so that editing a rule can never reroute an order that is already in flight
-- and "why did this order go to Noor" is a lookup rather than an investigation.
--
--   dispatch_policy_id / dispatch_policy_version
--       The pinned ADR 0030 identity of the rule document the plan ran under, a
--       pair like policy_id / policy_version (which stay the identity of the
--       fulfillment.sourcing TIMING document). Null when no document was
--       published and the built-in default applied.
--   dispatch_rule_id
--       The id of the rule that matched, null when the document's default (or the
--       built-in one) did. Operator-visible and unique within its document.
--   dispatch_decision
--       The resolved action and the skips: mode, partner order/exclude/selection,
--       dispatch start basis and offset, grouping, the computed source_at, and any
--       installation the rule named that had no active binding at the branch.
--       Installation ids and rule ids only: no coordinates, no names, no money
--       (ADR 0029).
--
-- sourcing_mode is written from the decision, and its CHECK admits the fifth mode:
-- PARTNER_FIRST asks the partner lane first and offers the in-house fleet only when
-- that lane ended with a definite answer. V0054 is applied, so the constraint is
-- dropped and re-added rather than edited.
ALTER TABLE fulfillment.delivery_plans
    ADD COLUMN dispatch_policy_id uuid,
    ADD COLUMN dispatch_policy_version integer,
    ADD COLUMN dispatch_rule_id varchar(64),
    ADD COLUMN dispatch_decision jsonb;

ALTER TABLE fulfillment.delivery_plans
    ADD CONSTRAINT ck_plan_dispatch_policy_pair
        CHECK ((dispatch_policy_id IS NULL) = (dispatch_policy_version IS NULL)),
    ADD CONSTRAINT ck_plan_dispatch_decision
        CHECK (dispatch_decision IS NULL OR jsonb_typeof(dispatch_decision) = 'object');

ALTER TABLE fulfillment.delivery_plans
    DROP CONSTRAINT ck_plan_mode;

ALTER TABLE fulfillment.delivery_plans
    ADD CONSTRAINT ck_plan_mode CHECK (sourcing_mode IN (
        'FLEET_FIRST', 'FLEET_ONLY', 'PARTNER_ONLY', 'PARTNER_FIRST', 'MANUAL'));

-- The usage read ("plans per rule id over the last N days") is a tenant-scoped
-- range scan on creation time; without this it walks every plan the tenant ever had.
CREATE INDEX ix_plan_tenant_created
    ON fulfillment.delivery_plans (tenant_id, created_at);

COMMENT ON COLUMN fulfillment.delivery_plans.dispatch_policy_id IS
    'ADR 0142. The pinned fulfillment.dispatch_rules policy document the plan was evaluated under; null when none was published and the built-in default applied. A pair with dispatch_policy_version.';
COMMENT ON COLUMN fulfillment.delivery_plans.dispatch_rule_id IS
    'ADR 0142. The id of the dispatch rule that matched at plan creation, null when the document default (or the built-in default) did.';
COMMENT ON COLUMN fulfillment.delivery_plans.dispatch_decision IS
    'ADR 0142. The resolved dispatch action and the skipped installations, stored once at plan creation. Every later sourcing tick applies this and never re-reads the rule document. Carries no coordinates and no names (ADR 0029).';
