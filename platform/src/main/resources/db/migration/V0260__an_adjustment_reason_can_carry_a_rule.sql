-- ADR 0042, ADR 0108: courier_adjustment_reasons gains what a rule needs to
-- evaluate itself -- an amount and a typed condition -- so that "twenty
-- deliveries in a shift -> +50,000" can be authored once instead of keyed in
-- per courier through the four-eyes path every time.
--
-- ---------------------------------------------------------------------------
-- Why these are nullable columns on the existing registry, not a new table
-- ---------------------------------------------------------------------------
--
-- A reason is the same object whether it is ever applied by a rule or only
-- ever picked from a dropdown on a manual entry: `kind` and `outcome_basis`
-- (V0040) already say what it is and what it is about. What was missing is
-- purely the rule half -- an amount to post and a condition to test -- and
-- that half is optional per reason. A code with no rule_* value is manual
-- only, exactly as every reason in production is today; a code that sets all
-- six is evaluated automatically. The all-or-nothing pairing is a CHECK
-- below, the same pattern V0040 already uses for
-- ck_engagement_verification_pair and ck_handover_confirmed_pair.
--
-- rule_threshold's unit depends on outcome_basis and is documented on the
-- column rather than added as a second closed enum: a plain count for
-- LATE_DELIVERY and DELIVERED_VOLUME, basis points (0-10000) for the two
-- *_RATE bases, and minor currency units for CASH_VARIANCE.
--
-- outcome_basis is not widened to add an "HOURS" metric ADR 0042's prose
-- mentions ("delivered count, ON_TIME count, GEO_UNVERIFIED rate, cash
-- variance count, hours") because the column V0040 shipped never included
-- one and this wave's migration budget is these two columns, not a fifth
-- outcome_basis value with no existing reader. ADR 0108 records this as an
-- open input rather than silently dropping the word from the ADR text.
--
-- rule_version exists so a later wave can stamp it onto the ledger entry a
-- rule writes, the way ADR 0042's own text asks for ("each naming its
-- origin -- the rule and its version"). It is not stamped there yet --
-- courier_ledger_entries carries no rule-version column, and adding one is
-- outside this wave's reserved migration numbers -- so today it only proves
-- that a reason's rule configuration is itself versioned. ADR 0108 records
-- the gap.
ALTER TABLE fulfillment.courier_adjustment_reasons
    ADD COLUMN rule_amount_minor bigint,
    ADD COLUMN rule_currency char(3),
    ADD COLUMN rule_comparator varchar(3),
    ADD COLUMN rule_threshold bigint,
    ADD COLUMN rule_window varchar(24),
    ADD COLUMN rule_trigger varchar(24),
    ADD COLUMN rule_version integer NOT NULL DEFAULT 1;

ALTER TABLE fulfillment.courier_adjustment_reasons
    ADD CONSTRAINT ck_adjustment_reason_rule_pair CHECK (
        (rule_amount_minor IS NULL) = (rule_currency IS NULL)
        AND (rule_amount_minor IS NULL) = (rule_comparator IS NULL)
        AND (rule_amount_minor IS NULL) = (rule_threshold IS NULL)
        AND (rule_amount_minor IS NULL) = (rule_window IS NULL)
        AND (rule_amount_minor IS NULL) = (rule_trigger IS NULL)),
    ADD CONSTRAINT ck_adjustment_reason_rule_comparator CHECK (
        rule_comparator IS NULL OR rule_comparator IN ('GTE', 'LTE')),
    ADD CONSTRAINT ck_adjustment_reason_rule_threshold CHECK (
        rule_threshold IS NULL OR rule_threshold >= 0),
    ADD CONSTRAINT ck_adjustment_reason_rule_window CHECK (
        rule_window IS NULL OR rule_window IN ('SHIFT', 'SETTLEMENT_PERIOD')),
    ADD CONSTRAINT ck_adjustment_reason_rule_trigger CHECK (
        rule_trigger IS NULL OR rule_trigger IN ('SHIFT_CLOSE', 'SETTLEMENT_PERIOD_CLOSE')),
    ADD CONSTRAINT ck_adjustment_reason_rule_currency CHECK (
        rule_currency IS NULL OR rule_currency ~ '^[A-Z]{3}$'),
    -- Same sign discipline as ck_ledger_sign (V0040): a BONUS rule cannot post
    -- a negative amount and a PENALTY rule cannot post a positive one, so a
    -- typo in the registry fails at save time rather than at first evaluation.
    ADD CONSTRAINT ck_adjustment_reason_rule_sign CHECK (
        rule_amount_minor IS NULL
        OR (kind = 'BONUS' AND rule_amount_minor > 0)
        OR (kind = 'PENALTY' AND rule_amount_minor < 0));

COMMENT ON COLUMN fulfillment.courier_adjustment_reasons.rule_amount_minor IS
    'ADR 0108. Null: manual only. Non-null: the AdjustmentRuleEvaluator posts exactly this amount, in rule_currency, when the condition holds -- never a courier-typed figure. Sign must match kind (ck_adjustment_reason_rule_sign).';
COMMENT ON COLUMN fulfillment.courier_adjustment_reasons.rule_threshold IS
    'ADR 0108. Units follow outcome_basis: a plain count for LATE_DELIVERY and DELIVERED_VOLUME, basis points (0-10000) for ON_TIME_RATE and GEO_UNVERIFIED_RATE, minor currency units for CASH_VARIANCE. ORDER_UNDELIVERED and ORDER_DAMAGED are not evaluated by AdjustmentRuleEvaluator this wave (no delivery_exceptions reader yet) and may only be used manual-only, with rule_amount_minor left null.';
COMMENT ON COLUMN fulfillment.courier_adjustment_reasons.rule_window IS
    'ADR 0108. SHIFT: the metric is computed over one closed shift, evaluated from CourierShiftService.close/approveHours. SETTLEMENT_PERIOD: computed over the whole settlement period -- built and tested (AdjustmentRuleEvaluator.evaluatePeriodClose) but not yet called from CourierSettlementService.close; see ADR 0108''s open inputs for why.';
COMMENT ON COLUMN fulfillment.courier_adjustment_reasons.rule_version IS
    'ADR 0108. Incremented whenever any rule_* column on this row changes. Not yet stamped onto the ledger entries a rule writes -- courier_ledger_entries has no column for it -- so it proves the rule config is versioned without yet making a past application immune to a later edit of this row. Recorded as an open input, not hidden.';
