-- Gap-map row 6.5: LATE_ORDER_APOLOGY, and the contact policy's written reason (ADR 0112).
--
-- 1. The trigger kind. V0414 and V0421 withheld LATE_ORDER_APOLOGY because ADR 0044 left it
--    "deliberately absent": lateness is an ADR 0013 recovery event, and a second,
--    unreconciled compensation path from marketing is how one late delivery gets both a
--    refund from support and a promo code from a trigger. ADR 0112 is Accepted and this kind
--    is built as the reconciliation, not as that second path:
--      * it sends words and states no benefit: this table has no offer, promotion or
--        accrual-rule column to state one in, which is the enforcement;
--      * it is cancelled, with the reason on the run row, when payments.order_remedies holds
--        any remedy for the order;
--      * it only considers an order that has been closed for thirty minutes, so support has
--        first refusal;
--      * it is guarded once per order ("ORDER:<id>" in marketing.automation_runs.guard_key,
--        under the unique index V0415 already puts on it).
--    trigger_config carries its one threshold the way every other kind does: lateByMinutes.
--
-- 2. A written reason on a refused firing. automation_runs.refusal_reason is a code of at
--    most 32 characters. When the contact policy refuses a firing it also says which rule and
--    with what numbers ("2 already sent in the DAILY window, and this brand allows 2"), and
--    that sentence is what a marketer reads to answer "why did this guest not get it". It
--    names no contact value and no message body. Nullable, and only meaningful on a REFUSED
--    row, which the CHECK states.

ALTER TABLE marketing.automation_rules
    DROP CONSTRAINT ck_automation_rule_trigger_type;

ALTER TABLE marketing.automation_rules
    ADD CONSTRAINT ck_automation_rule_trigger_type CHECK (
        trigger_type IN ('BIRTHDAY', 'INACTIVITY', 'CART_ABANDONMENT', 'CASHBACK_CHANGE', 'LATE_ORDER_APOLOGY')
    );

COMMENT ON CONSTRAINT ck_automation_rule_trigger_type ON marketing.automation_rules IS
    'The AutomationTriggerType this build offers. V0414 offered three, V0421 added CASHBACK_CHANGE, V0497 adds LATE_ORDER_APOLOGY now that ADR 0112 is Accepted: words only, cancelled when an ADR 0013 remedy exists, once per order.';

ALTER TABLE marketing.automation_runs
    ADD COLUMN refusal_detail varchar(500);

ALTER TABLE marketing.automation_runs
    ADD CONSTRAINT ck_automation_run_refusal_detail CHECK (refusal_detail IS NULL OR status = 'REFUSED');

COMMENT ON COLUMN marketing.automation_runs.refusal_detail IS
    'ADR 0112. The sentence that explains a refusal by the contact policy: which rule and the numbers it was applied with. Free of any contact value or message body.';
