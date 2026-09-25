-- Gap-map row 6.5: automations. ADR 0044 names four triggers (BIRTHDAY,
-- INACTIVITY, CART_ABANDONED, POST_ORDER_REVIEW) with their guard and cadence
-- decided, and is explicit that "nothing sends without a human" -- a trigger
-- is a campaign whose audience is a rule rather than a snapshot, and it obeys
-- the same frequency cap and quiet hours a campaign does. This migration is the
-- rule an operator authors and arms; V0415 is the per-recipient firing ledger
-- that makes the guard a database constraint rather than something a service
-- has to remember.
--
-- Only three of the ADR's own trigger kinds are offered here: BIRTHDAY and
-- INACTIVITY exactly as ADR 0044 states them, and CART_ABANDONMENT for its
-- CART_ABANDONED. POST_ORDER_REVIEW is not offered because `marketing.reviews`
-- does not exist yet (ADR 0044's own implementation checklist: reviews are
-- unbuilt). CASHBACK_CHANGE is not offered: no accrual or debit event exists
-- in the `loyalty` module today (`LoyaltyAccrualService` writes a ledger entry
-- and publishes nothing), so a trigger of that kind would be a rule with no
-- producer -- exactly the "empty table that reads to the next author as though
-- the projection is broken" V0031 and V0039 warned against, restated here for
-- a trigger kind instead of a table. LATE_ORDER_APOLOGY is deliberately not
-- offered at all: ADR 0044's own Triggers section states it is "deliberately
-- absent" because a second compensation path competing with ADR 0013's
-- recovery case is unreconciled, and ADR 0112 (Proposed, not Accepted) is
-- where that decision would be revisited -- not this migration.
CREATE TABLE marketing.automation_rules (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    name varchar(200) NOT NULL,

    trigger_type varchar(32) NOT NULL,
    channel varchar(24) NOT NULL,
    consent_purpose varchar(64) NOT NULL,
    template_key varchar(64) NOT NULL,

    -- Trigger-specific numeric configuration this closed set of trigger kinds
    -- needs and nothing else does: BIRTHDAY's window in days either side of
    -- today (mirrors BIRTHDAY_WITHIN_DAYS), INACTIVITY's day-since-last-order
    -- threshold, CART_ABANDONMENT's abandonment delay in hours. A jsonb bag
    -- rather than a column per trigger kind because the columns a row needs
    -- depend on its own trigger_type and every other column would sit NULL —
    -- AutomationTriggerType's own factory validates the keys each kind
    -- requires before this row is ever written.
    trigger_config jsonb NOT NULL DEFAULT '{}'::jsonb,

    -- How long a fired guard key holds before the same customer may be fired
    -- again by this rule. BIRTHDAY's own guard is a calendar year regardless
    -- of this value (ADR 0044: "once per customer per year"); INACTIVITY and
    -- CART_ABANDONMENT bucket the cooldown into fixed-width windows rather
    -- than a true sliding "N days since last fire" -- see
    -- AutomationGuardKeys's own doc for why a bucketed guard is a database
    -- constraint and a sliding one is not.
    cooldown_days integer NOT NULL,

    -- q-rule-list's own ordering column, the same undecorated-integer shape
    -- V0400's display_order uses for its sibling reorder screen: no
    -- uniqueness constraint, ties broken by name wherever this is read.
    priority integer NOT NULL DEFAULT 0,

    -- "Nothing sends without a human": a rule is authored inert and an
    -- operator must arm it. Enforced below, not only in the service, by
    -- ck_automation_rule_human_armed.
    active boolean NOT NULL DEFAULT false,
    created_by uuid NOT NULL,
    activated_by uuid,
    activated_at timestamptz,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_automation_rule_identity UNIQUE (id, tenant_id),
    CONSTRAINT uq_automation_rule_name UNIQUE (tenant_id, brand_id, name),
    CONSTRAINT fk_automation_rule_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT ck_automation_rule_trigger_type CHECK (
        trigger_type IN ('BIRTHDAY', 'INACTIVITY', 'CART_ABANDONMENT')
    ),
    CONSTRAINT ck_automation_rule_channel CHECK (
        channel IN ('SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP')
    ),
    CONSTRAINT ck_automation_rule_cooldown_days CHECK (cooldown_days > 0),
    CONSTRAINT ck_automation_rule_priority CHECK (priority >= 0),
    -- A human armed this rule, or it is not active. Both columns are set
    -- together by AutomationRuleService#activate under CAMPAIGN_APPROVE --
    -- the same second-signature capability a campaign's own approval takes --
    -- and cleared together by #deactivate.
    CONSTRAINT ck_automation_rule_human_armed CHECK (
        (active = false) OR (activated_by IS NOT NULL AND activated_at IS NOT NULL)
    )
);

COMMENT ON TABLE marketing.automation_rules IS
    'Gap-map row 6.5, ADR 0044 Triggers. An operator-authored rule that fires unattended once armed. Never sends without a human: active requires activated_by/activated_at, set only by AutomationRuleService#activate under CAMPAIGN_APPROVE.';
COMMENT ON COLUMN marketing.automation_rules.trigger_config IS
    'Trigger-kind-specific thresholds (birthdayWindowDays | inactivityDays | abandonmentDelayHours). Validated by AutomationTriggerType against the row''s own trigger_type before every write.';


-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON marketing.automation_rules TO horecaos_application;
