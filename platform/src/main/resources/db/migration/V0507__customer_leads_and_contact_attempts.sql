-- ADR 0111 (Accepted 2026-10-07): a guest has one card, and every contact with them is history.
--
-- The customers module already owns identity, contact points, consent, the blacklist and erasure.
-- What it has no representation for is a guest who has phoned in, filled a form, asked for a
-- callback or enquired about catering and who is not yet an account with an order behind them
-- (a LEAD), and what it cannot say about the telephone is who called whom, when, and what came of
-- it (a CONTACT ATTEMPT). Two additive tables; nothing is backfilled because nothing existed.
--
--   customer.leads             a not-yet-customer contact, brand-scoped, with the six-state status
--                              machine the decision draws and a branch hand-off that is a field,
--                              not a workflow (assigned_location_id).
--   customer.contact_attempts  an append-only journal of voice contact with a lead or a customer.
--                              The application role holds INSERT and SELECT and nothing else, so
--                              a call outcome cannot be quietly rewritten afterwards -- the same
--                              reason customer.consent_decisions is append-only.
--
-- Personal data is envelope-encrypted (ADR 0029): the phone, the name and the operator's free-text
-- notes. phone_lookup_hash is the same keyed hash contact_points.normalized_hash holds, taken under
-- the same domain, so a lead's number matches an existing account's number as a HINT an operator
-- confirms -- never as an identity key (ADR 0015). phone_masked is the only part of the number a
-- list may show, written once at creation so no list ever decrypts.

CREATE TABLE customer.leads (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    status varchar(24) NOT NULL DEFAULT 'NEW',
    source varchar(32) NOT NULL,

    phone_lookup_hash varchar(64) NOT NULL,
    phone_encrypted text NOT NULL,
    phone_masked varchar(24) NOT NULL,
    display_name_encrypted text,
    notes_encrypted text,

    -- Set once the guest is identified or merged into an account. Never an automatic match.
    customer_account_id uuid,

    -- The branch hand-off. A branch, not an operator: there is no per-operator load figure to
    -- route against, and inventing one would be a number nobody could defend (ADR 0111 §5).
    assigned_location_id uuid,
    assigned_at timestamptz,

    -- When a CALLBACK_SCHEDULED lead is to be rung back. Set exactly while the status says so.
    callback_due_at timestamptz,

    -- A campaign scenario's CALL_CENTRE step (ADR 0112) lands here as one lead. A bare id pair, no
    -- foreign key across the module boundary, and no marketing table duplicates this row.
    origin_campaign_id uuid,
    origin_step_sequence integer,

    -- CONVERTED points at whichever terminal fact actually happened -- exactly one of the two.
    converted_order_id uuid,
    converted_reservation_id uuid,

    -- DECLINED and LOST carry a coded reason, never free text: a reason typed in a box is where a
    -- guest's name ends up in a report.
    closed_reason varchar(32),

    created_by varchar(128) NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_lead_status CHECK (
        status IN ('NEW', 'CONTACTED', 'CALLBACK_SCHEDULED', 'CONVERTED', 'DECLINED', 'LOST')),
    CONSTRAINT ck_lead_source CHECK (
        source IN ('STOREFRONT', 'TELEGRAM_BOT', 'SITE', 'RESERVATION', 'CALLBACK_REQUEST',
                   'AGGREGATOR_FIRST_ORDER', 'B2B_CATERING_ENQUIRY', 'CAMPAIGN_SCENARIO')),
    -- A lead is CONVERTED exactly when an order or a reservation says so, and never both.
    CONSTRAINT ck_lead_converted CHECK (
        (status = 'CONVERTED') = (converted_order_id IS NOT NULL OR converted_reservation_id IS NOT NULL)),
    CONSTRAINT ck_lead_converted_one CHECK (
        converted_order_id IS NULL OR converted_reservation_id IS NULL),
    CONSTRAINT ck_lead_closed CHECK (
        (status IN ('DECLINED', 'LOST')) = (closed_reason IS NOT NULL)),
    CONSTRAINT ck_lead_closed_reason CHECK (
        closed_reason IS NULL OR closed_reason IN (
            'OUT_OF_CAPACITY', 'OUT_OF_COVERAGE', 'WRONG_CUISINE_OR_MENU', 'NOT_REACHABLE',
            'NOT_INTERESTED', 'DUPLICATE', 'SPAM_OR_WRONG_NUMBER', 'OTHER')),
    CONSTRAINT ck_lead_callback CHECK (
        (status = 'CALLBACK_SCHEDULED') = (callback_due_at IS NOT NULL)),
    CONSTRAINT ck_lead_assigned_pair CHECK (
        (assigned_location_id IS NULL) = (assigned_at IS NULL)),
    CONSTRAINT ck_lead_campaign_origin CHECK (
        (source = 'CAMPAIGN_SCENARIO') = (origin_campaign_id IS NOT NULL)
        AND (origin_campaign_id IS NULL) = (origin_step_sequence IS NULL)
        AND (origin_step_sequence IS NULL OR origin_step_sequence >= 0)),
    CONSTRAINT ck_lead_version CHECK (version >= 1),

    -- The children below reference these, so a row of one tenant (or one brand) can never point
    -- at another's lead.
    CONSTRAINT uq_lead_identity UNIQUE (id, tenant_id),
    CONSTRAINT uq_lead_identity_brand UNIQUE (id, tenant_id, brand_id),
    CONSTRAINT fk_lead_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_lead_location FOREIGN KEY (tenant_id, brand_id, assigned_location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_lead_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id)
);

-- The call centre's queue: a brand's open leads, oldest first within a status.
CREATE INDEX ix_lead_queue ON customer.leads (tenant_id, brand_id, status, created_at, id);
-- A branch's own callbacks.
CREATE INDEX ix_lead_location ON customer.leads (tenant_id, assigned_location_id, status)
    WHERE assigned_location_id IS NOT NULL;
-- "Is this number already a lead, or an account?" -- the dedup hint.
CREATE INDEX ix_lead_phone ON customer.leads (tenant_id, phone_lookup_hash);
-- The leads of one customer, for the card.
CREATE INDEX ix_lead_account ON customer.leads (tenant_id, customer_account_id)
    WHERE customer_account_id IS NOT NULL;
-- A campaign step enqueues a call task under the inbox's at-least-once delivery, so the same step
-- for the same number must collapse to one lead.
CREATE UNIQUE INDEX ux_lead_campaign_step
    ON customer.leads (tenant_id, origin_campaign_id, origin_step_sequence, phone_lookup_hash)
    WHERE source = 'CAMPAIGN_SCENARIO';

CREATE TABLE customer.contact_attempts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    lead_id uuid,
    customer_account_id uuid,

    direction varchar(8) NOT NULL,
    channel varchar(16) NOT NULL DEFAULT 'PHONE',

    -- The same shape as notifications' attempt id, so one logical contact that also produced a
    -- notification can be cross-referenced. Unique per tenant: a retried submit is one attempt.
    attempt_id uuid NOT NULL,

    outcome varchar(16) NOT NULL,
    blocking_reason varchar(24),

    operator_actor_id varchar(128) NOT NULL,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),

    next_action varchar(24),
    next_action_at timestamptz,

    CONSTRAINT ck_attempt_direction CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT ck_attempt_channel CHECK (channel IN ('PHONE')),
    CONSTRAINT ck_attempt_outcome CHECK (
        outcome IN ('CONNECTED', 'NO_ANSWER', 'DECLINED', 'VOICEMAIL', 'BLOCKED')),
    CONSTRAINT ck_attempt_blocking_reason CHECK (
        blocking_reason IS NULL
        OR blocking_reason IN ('BLACKLISTED', 'OUTSIDE_QUIET_HOURS', 'NO_CONSENT', 'WRONG_NUMBER')),
    -- Both are attempts, and both say why when refused: a refusal without a reason, or a reason
    -- without a refusal, is meaningless.
    CONSTRAINT ck_attempt_blocked_pair CHECK ((outcome = 'BLOCKED') = (blocking_reason IS NOT NULL)),
    CONSTRAINT ck_attempt_subject CHECK (lead_id IS NOT NULL OR customer_account_id IS NOT NULL),
    CONSTRAINT ck_attempt_next_action CHECK (
        next_action IS NULL OR next_action IN ('CALL_AGAIN', 'AWAIT_GUEST', 'HAND_TO_BRANCH')),
    CONSTRAINT ck_attempt_next_action_at CHECK (next_action_at IS NULL OR next_action IS NOT NULL),
    CONSTRAINT uq_attempt_per_tenant UNIQUE (tenant_id, attempt_id),
    CONSTRAINT fk_attempt_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_attempt_lead FOREIGN KEY (lead_id, tenant_id, brand_id)
        REFERENCES customer.leads (id, tenant_id, brand_id),
    CONSTRAINT fk_attempt_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id)
);

CREATE INDEX ix_attempt_lead ON customer.contact_attempts (tenant_id, lead_id, occurred_at DESC)
    WHERE lead_id IS NOT NULL;
CREATE INDEX ix_attempt_account ON customer.contact_attempts (tenant_id, customer_account_id, occurred_at DESC)
    WHERE customer_account_id IS NOT NULL;

COMMENT ON TABLE customer.leads IS
    'ADR 0111. A not-yet-customer contact: a phoned-in enquiry, a callback request, a catering enquiry, a campaign scenario''s call task. Phone, name and notes are envelope-encrypted (ADR 0029); the list shows phone_masked only.';
COMMENT ON TABLE customer.contact_attempts IS
    'ADR 0111. Append-only journal of voice contact with a lead or a customer: INSERT and SELECT only for the application role, so an outcome cannot be rewritten after the fact. Carries no personal data -- ids, codes and instants.';

-- The journal is append-only by grant, exactly like customer.consent_decisions.
GRANT SELECT, INSERT, UPDATE ON customer.leads TO horecaos_application;
GRANT SELECT, INSERT ON customer.contact_attempts TO horecaos_application;
