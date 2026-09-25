-- Gap-map row 6.5. The per-recipient firing ledger V0414's own header
-- describes: "each firing writes a ... row whose guard key is a unique
-- index, so the guard is enforced by the database rather than remembered by
-- a service" (ADR 0044, Triggers).
--
-- guard_key's shape depends on the rule's trigger_type (AutomationGuardKeys):
--   BIRTHDAY          "YEAR:<yyyy>" in the brand's own timezone -- ADR 0044's
--                      literal "once per customer per year".
--   INACTIVITY         "BUCKET:<n>" -- days-since-epoch divided by the rule's
--                      own cooldown_days, floored. An approximation of a
--                      sliding cooldown, not identical to it (a customer
--                      re-crossing the threshold one day into a new bucket
--                      can be fired again sooner than a full cooldown after
--                      the last fire) -- accepted for the same reason a plain
--                      integer display_order is accepted over a linked list:
--                      simple, and a database constraint can enforce it.
--   CART_ABANDONMENT   "CART:<cart id>" -- ADR 0044's "once per cart,
--                      cancelled if the cart converts first". No cooldown
--                      bucketing: a cart is abandoned once, ever.
CREATE TABLE marketing.automation_runs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    automation_rule_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,

    trigger_type varchar(32) NOT NULL,
    guard_key varchar(200) NOT NULL,

    -- The cart id for CART_ABANDONMENT; null for a trigger with no such
    -- domain-fact subject.
    subject_id uuid,

    status varchar(16) NOT NULL,
    refusal_reason varchar(32),
    notification_id uuid,
    cancelled_reason varchar(200),

    fired_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_automation_run_guard UNIQUE (tenant_id, automation_rule_id, customer_account_id, guard_key),
    CONSTRAINT fk_automation_run_rule FOREIGN KEY (automation_rule_id, tenant_id)
        REFERENCES marketing.automation_rules (id, tenant_id),
    CONSTRAINT fk_automation_run_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    -- PENDING is transient within one AutomationFiringService transaction: the
    -- guard key is claimed (INSERT) before eligibility is even checked, exactly
    -- the "claim before act" order CampaignSendService#claimBatch already takes,
    -- so a concurrent sweep pass can never observe two rows for the same guard
    -- key. The same transaction always moves the row to FIRED, REFUSED or
    -- CANCELLED before it commits; a row still PENDING after commit is a bug,
    -- not a valid rest state.
    CONSTRAINT ck_automation_run_status CHECK (status IN ('PENDING', 'FIRED', 'REFUSED', 'CANCELLED')),
    CONSTRAINT ck_automation_run_refusal CHECK ((status = 'REFUSED') = (refusal_reason IS NOT NULL)),
    CONSTRAINT ck_automation_run_cancelled CHECK ((status = 'CANCELLED') = (cancelled_reason IS NOT NULL)),
    CONSTRAINT ck_automation_run_fired_notification CHECK (status <> 'FIRED' OR notification_id IS NOT NULL)
);

COMMENT ON TABLE marketing.automation_runs IS
    'Gap-map row 6.5, ADR 0044 Triggers. One row per attempted firing: the unique (rule, customer, guard_key) index is the cooldown/idempotency guard, enforced by Postgres rather than remembered by AutomationFiringService.';

CREATE INDEX ix_automation_run_rule ON marketing.automation_runs (tenant_id, automation_rule_id, fired_at DESC);

-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------
-- UPDATE is required: JdbcAutomationRunStore#markFired/markRefused/markCancelled
-- all move a claimed PENDING row to its final status in place, never a second
-- INSERT.
GRANT SELECT, INSERT, UPDATE ON marketing.automation_runs TO horecaos_application;
