-- ADR 0112: the per-guest half of campaign management.
--
-- A scenario is an ordered list of steps evaluated per guest against that guest's own
-- state row, never against the whole audience at once. Four tables:
--
--   scenario_steps               what to do, in order; immutable once the campaign leaves DRAFT
--   scenario_participant_state   where each guest is, one row per (campaign, guest)
--   scenario_step_decisions      every choice, and for every block a written reason
--   presented_offers             what an in-app surface is to show a guest
--
-- Every table carries tenant_id and brand_id, and a scenario is brand-scoped exactly as
-- marketing.campaigns already is. None of them holds a contact value, a rendered body or
-- a customer's name: a guest is an account id, a step names a template by key, and a
-- decision names its reason by code and a sentence a marketer can read. marketing keeps
-- no private call queue and no private copy of a contact log (ADR 0112 Principle 1).

-- ---------------------------------------------------------------------- steps

CREATE TABLE marketing.scenario_steps (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    sequence smallint NOT NULL,

    channel varchar(24) NOT NULL,
    offer_id uuid,
    template_key varchar(64) NOT NULL,
    template_version integer,

    wait_after_previous_seconds integer NOT NULL DEFAULT 0,

    -- Closed sets, so the evaluator has no expression language to be wrong about:
    -- whether to go on to this step at all, and whether the whole scenario is over for
    -- the guest. Both read a fact (has the guest ordered since entering) and nothing else.
    continuation_condition varchar(32) NOT NULL DEFAULT 'ALWAYS',
    stop_condition varchar(32) NOT NULL DEFAULT 'NONE',

    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_scenario_step_sequence UNIQUE (campaign_id, sequence),
    CONSTRAINT uq_scenario_step_identity UNIQUE (id, tenant_id),
    CONSTRAINT fk_scenario_step_campaign FOREIGN KEY (campaign_id, tenant_id)
        REFERENCES marketing.campaigns (id, tenant_id),
    CONSTRAINT fk_scenario_step_offer FOREIGN KEY (offer_id, tenant_id)
        REFERENCES marketing.offers (id, tenant_id),
    CONSTRAINT ck_scenario_step_sequence CHECK (sequence >= 1),
    CONSTRAINT ck_scenario_step_channel CHECK (
        channel IN ('SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP', 'IN_APP', 'CALL_CENTRE')
    ),
    CONSTRAINT ck_scenario_step_wait CHECK (wait_after_previous_seconds >= 0),
    CONSTRAINT ck_scenario_step_continuation CHECK (
        continuation_condition IN ('ALWAYS', 'NO_ORDER_SINCE_ENTRY')
    ),
    CONSTRAINT ck_scenario_step_stop CHECK (stop_condition IN ('NONE', 'ORDER_PLACED_SINCE_ENTRY')),
    -- An in-app step shows an offer; there is nothing to show without one.
    CONSTRAINT ck_scenario_step_in_app_offer CHECK (channel <> 'IN_APP' OR offer_id IS NOT NULL)
);

-- ---------------------------------------------------------------- participants

CREATE TABLE marketing.scenario_participant_state (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,

    current_step_sequence smallint NOT NULL DEFAULT 1,
    wait_until timestamptz,

    -- Decided once, at entry, and never resampled: a guest who unsubscribes mid-scenario
    -- stops being measured rather than being replaced (ADR 0112, accepted trade-off).
    in_control_group boolean NOT NULL,

    outcome varchar(40),

    entered_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    PRIMARY KEY (campaign_id, customer_account_id),
    CONSTRAINT fk_scenario_participant_campaign FOREIGN KEY (campaign_id, tenant_id)
        REFERENCES marketing.campaigns (id, tenant_id),
    CONSTRAINT fk_scenario_participant_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT ck_scenario_participant_step CHECK (current_step_sequence >= 1),
    CONSTRAINT ck_scenario_participant_outcome CHECK (
        outcome IS NULL OR outcome IN (
            'COMPLETED', 'STOPPED_BY_CONDITION', 'STOPPED_BY_CONSENT_WITHDRAWN', 'STOPPED_BY_SUPPRESSION')
    ),
    -- A withheld guest is never processed, and a finished one is never waiting.
    CONSTRAINT ck_scenario_participant_waiting CHECK (
        (outcome IS NOT NULL OR in_control_group) = (wait_until IS NULL)
    )
);

-- What the runner scans: guests who are due. Partial on exactly that set, because the
-- overwhelming majority of rows are finished or withheld.
CREATE INDEX ix_scenario_participants_due
    ON marketing.scenario_participant_state (wait_until)
    WHERE outcome IS NULL AND NOT in_control_group;

CREATE INDEX ix_scenario_participants_account
    ON marketing.scenario_participant_state (tenant_id, customer_account_id);

-- ------------------------------------------------------------------ decisions
--
-- Append-only. One row for every choice action selection makes, and for every BLOCK a
-- reason code and a sentence: "why did this guest not get step 2" is the question a
-- tenant actually asks, and a decision with no row has no answer. At most one SENT row per
-- (campaign, guest, step), which is what makes a redelivered tick harmless: the step is
-- sent once however many times it is decided.

CREATE TABLE marketing.scenario_step_decisions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,
    step_sequence smallint NOT NULL,

    decision varchar(8) NOT NULL,
    refusal_reason varchar(40),
    reason_text varchar(500),
    resolved_channel varchar(24),

    -- The ADR 0020 intent a messaging step created, which is the "attempt id" ADR 0112
    -- names: the one handle the delivery path has for what was asked of it.
    attempt_id uuid,
    -- Command-delivery receipt for a CALL_CENTRE step; the queue itself is ADR 0111's.
    acknowledged_at timestamptz,
    decided_at timestamptz NOT NULL,

    CONSTRAINT fk_scenario_decision_campaign FOREIGN KEY (campaign_id, tenant_id)
        REFERENCES marketing.campaigns (id, tenant_id),
    CONSTRAINT ck_scenario_decision CHECK (decision IN ('SENT', 'BLOCKED')),
    CONSTRAINT ck_scenario_decision_reason CHECK (
        (decision = 'BLOCKED') = (refusal_reason IS NOT NULL)
        AND (decision <> 'BLOCKED' OR reason_text IS NOT NULL)
    ),
    CONSTRAINT ck_scenario_decision_reason_value CHECK (
        refusal_reason IS NULL OR refusal_reason IN (
            'ACCOUNT_NOT_ACTIVE', 'CONSENT_WITHHELD', 'SUPPRESSED', 'FREQUENCY_CAP_REACHED',
            'NO_VERIFIED_ENDPOINT', 'CAMPAIGN_HALTED',
            'SCENARIO_CONFLICT', 'SCENARIO_PRIORITY_LOST', 'SCENARIO_STOPPED')
    ),
    CONSTRAINT ck_scenario_decision_step CHECK (step_sequence >= 1)
);

CREATE UNIQUE INDEX ux_scenario_decision_sent_once
    ON marketing.scenario_step_decisions (campaign_id, customer_account_id, step_sequence)
    WHERE decision = 'SENT';

CREATE INDEX ix_scenario_decisions_campaign
    ON marketing.scenario_step_decisions (campaign_id, decided_at DESC);

CREATE INDEX ix_scenario_decisions_guest
    ON marketing.scenario_step_decisions (tenant_id, customer_account_id, decided_at DESC);

-- ----------------------------------------------------------- presented offers
--
-- What a storefront or the Telegram mini-app polls to show a banner. A banner has no
-- delivery attempt to count against the messaging frequency cap, so it has its own count
-- here, capped by the ADR 0030 key marketing.in_app.show_cap_per_day. It holds an
-- account id and nothing that identifies the person, but it is one more table with a
-- customer reference on it, which ADR 0112 names as a widened surface.

CREATE TABLE marketing.presented_offers (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    campaign_id uuid,
    offer_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,
    surface varchar(24) NOT NULL,

    shown_count integer NOT NULL DEFAULT 0,
    last_shown_at timestamptz,
    -- The calendar day (brand timezone) shown_today counts, so a cap per day resets by
    -- itself when the day turns rather than by a job.
    shown_day date,
    shown_today integer NOT NULL DEFAULT 0,
    dismissed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_presented_offer_offer FOREIGN KEY (offer_id, tenant_id)
        REFERENCES marketing.offers (id, tenant_id),
    CONSTRAINT fk_presented_offer_campaign FOREIGN KEY (campaign_id, tenant_id)
        REFERENCES marketing.campaigns (id, tenant_id),
    CONSTRAINT fk_presented_offer_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT ck_presented_offer_surface CHECK (surface IN ('STOREFRONT', 'TELEGRAM_MINI_APP')),
    CONSTRAINT ck_presented_offer_counts CHECK (shown_count >= 0 AND shown_today >= 0)
);

-- One presentation per offer per guest per surface per campaign. A null campaign (an
-- offer shown outside any scenario) is one slot, which a plain unique constraint would
-- not make it, since nulls never compare equal.
CREATE UNIQUE INDEX ux_presented_offer_slot
    ON marketing.presented_offers (
        tenant_id, customer_account_id, offer_id, surface,
        COALESCE(campaign_id, '00000000-0000-0000-0000-000000000000'::uuid));

CREATE INDEX ix_presented_offers_guest
    ON marketing.presented_offers (tenant_id, brand_id, customer_account_id, surface)
    WHERE dismissed_at IS NULL;

GRANT SELECT, INSERT, DELETE ON marketing.scenario_steps TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON marketing.scenario_participant_state TO horecaos_application;
-- Insert and read only, plus the one column the command-delivery receipt writes. A
-- decision the application can rewrite is not a record of what was decided.
GRANT SELECT, INSERT ON marketing.scenario_step_decisions TO horecaos_application;
GRANT UPDATE (acknowledged_at) ON marketing.scenario_step_decisions TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON marketing.presented_offers TO horecaos_application;
