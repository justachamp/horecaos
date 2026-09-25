-- Gap-map row 6.5's CASHBACK_CHANGE trigger (ADR 0044 Triggers; batch 12,
-- w7-marketing-triggers).
--
-- V0414's own header explained why CASHBACK_CHANGE was withheld: "no accrual
-- or debit event exists in the `loyalty` module today ... so a trigger of
-- that kind would be a rule with no producer." That producer now exists --
-- `loyalty.api.LoyaltyBalanceChanged`, a Modulith application event
-- `LoyaltyAccrualService#accrue` and `PointsRedemptionService#reserve` both
-- publish -- so the trigger kind this constraint refused is offered.
--
-- No new column. `trigger_config` (jsonb) already carries a trigger-specific
-- threshold per kind (`AutomationTriggerType.configKey()`); CASHBACK_CHANGE's
-- is `minimumChangeMinor`, validated the same way `birthdayWindowDays` and
-- the others already are. `cooldown_days` already exists and is reused
-- exactly as INACTIVITY uses it -- a fixed-width bucket
-- (`AutomationGuardKeys.cooldownBucket`) so a customer whose balance moves
-- several times in one window is notified at most once per window, the same
-- "guard/cooldown/quiet-hours semantics the existing triggers have" ADR 0044
-- already gives a sweep-driven trigger, reused unchanged for an event-driven
-- one.
--
-- LATE_ORDER_APOLOGY is deliberately still not in this list. See
-- `AutomationTriggerType`'s own doc: ADR 0044 states it is "deliberately
-- absent" -- a second, unreconciled compensation path alongside ADR 0013's
-- recovery case -- and ADR 0112, where that reconciliation would be decided,
-- is still `Proposed`. Offering it here would be re-deciding an `Accepted`
-- ADR's explicit exclusion from a migration, which CLAUDE.md's own rule
-- forbids ("never edit an `Accepted` ADR to change its decision... write a
-- new ADR").
ALTER TABLE marketing.automation_rules
    DROP CONSTRAINT ck_automation_rule_trigger_type;

ALTER TABLE marketing.automation_rules
    ADD CONSTRAINT ck_automation_rule_trigger_type CHECK (
        trigger_type IN ('BIRTHDAY', 'INACTIVITY', 'CART_ABANDONMENT', 'CASHBACK_CHANGE')
    );

COMMENT ON CONSTRAINT ck_automation_rule_trigger_type ON marketing.automation_rules IS
    'The AutomationTriggerType this build offers. V0414 offered three; V0421 adds CASHBACK_CHANGE once loyalty gained a balance-change producer. LATE_ORDER_APOLOGY stays out -- ADR 0044''s own exclusion, unresolved by ADR 0112 (Proposed).';
