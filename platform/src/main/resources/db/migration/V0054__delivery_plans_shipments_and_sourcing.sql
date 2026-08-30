-- ADR 0014: the durable half of delivery sourcing.
--
-- Six tables the ADR 0014 checklist names and no migration has created: the
-- plan, the physical shipment, the quotes behind a selection, the attempts that
-- produced it, the durable job that wakes sourcing at the right time, and the
-- exception raised when none of it worked. `fulfillment.courier_*` and
-- `fulfillment.delivery_cost_lines` (V0040) already carry `shipment_id` and
-- `assignment_attempt_id` columns pointing at rows that did not exist; section 7
-- turns those into real foreign keys.
--
-- Why a plan and a shipment are separate tables: planning, quoting and sourcing
-- all happen before any physical shipment exists, and one plan may produce
-- several assignment attempts. ADR 0014 rejects merging them by name.

-- ---------------------------------------------------------------------------
-- 1. The plan
-- ---------------------------------------------------------------------------

CREATE TABLE fulfillment.delivery_plans (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    order_id uuid NOT NULL,

    status varchar(32) NOT NULL DEFAULT 'PLANNED',
    sourcing_mode varchar(16) NOT NULL DEFAULT 'FLEET_FIRST',
    service_level varchar(24) NOT NULL DEFAULT 'STANDARD',

    -- Snapshotted at checkout and never silently increased afterwards. Held on
    -- the plan so that "what did the customer pay for delivery" is answerable
    -- without re-running ADR 0037 against today's zones.
    customer_delivery_fee_minor bigint NOT NULL DEFAULT 0,
    currency char(3) NOT NULL,
    delivery_fee_resolution_id uuid,

    -- ADR 0014's time model. Every instant is UTC and the branch zone is beside
    -- them, because a branch whose hours wrap past midnight is rendered against
    -- it and a plan that cannot say which local day it belongs to is one an
    -- operator cannot find.
    confirmed_at timestamptz NOT NULL,
    preparation_seconds integer NOT NULL,
    estimated_ready_at timestamptz NOT NULL,
    pickup_window_start timestamptz NOT NULL,
    pickup_window_end timestamptz NOT NULL,
    promised_delivery_start timestamptz,
    promised_delivery_end timestamptz,
    source_at timestamptz NOT NULL,
    latest_assignment_at timestamptz NOT NULL,
    branch_zone varchar(64) NOT NULL,
    calculation_version integer NOT NULL DEFAULT 1,

    distance_meters integer,
    distance_source varchar(24),

    -- ADR 0030 identity, snapshotted so that changing the lead times in October
    -- cannot restate why a September plan fell back when it did.
    policy_id uuid,
    policy_version integer,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_plan_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_plan_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT uq_plan_identity UNIQUE (id, tenant_id),
    CONSTRAINT ck_plan_status CHECK (status IN (
        'PLANNED', 'WAITING_TO_SOURCE', 'SOURCING', 'BOOKING', 'RETRY_PENDING',
        'SCHEDULED', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED',
        'MANUAL_ACTION_REQUIRED', 'CANCELLED')),
    CONSTRAINT ck_plan_mode CHECK (sourcing_mode IN (
        'FLEET_FIRST', 'FLEET_ONLY', 'PARTNER_ONLY', 'MANUAL')),
    CONSTRAINT ck_plan_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_plan_fee CHECK (customer_delivery_fee_minor >= 0),
    CONSTRAINT ck_plan_preparation CHECK (preparation_seconds >= 0),
    CONSTRAINT ck_plan_distance CHECK (distance_meters IS NULL OR distance_meters >= 0),
    CONSTRAINT ck_plan_distance_pair CHECK (
        (distance_meters IS NULL) = (distance_source IS NULL)),
    CONSTRAINT ck_plan_window CHECK (pickup_window_end >= pickup_window_start),
    CONSTRAINT ck_plan_latest_assignment CHECK (latest_assignment_at >= pickup_window_end),
    CONSTRAINT ck_plan_promise_pair CHECK (
        (promised_delivery_start IS NULL) = (promised_delivery_end IS NULL)
        AND (promised_delivery_end IS NULL OR promised_delivery_end >= promised_delivery_start)),
    CONSTRAINT ck_plan_policy_pair CHECK ((policy_id IS NULL) = (policy_version IS NULL))
);

COMMENT ON COLUMN fulfillment.delivery_plans.source_at IS
    'ADR 0014. pickup_window_start less the in-house lead time and the safety buffer, floored at confirmed_at. The scheduler''s due time, so a formula change is a visible calculation_version bump rather than a silently different queue.';
COMMENT ON COLUMN fulfillment.delivery_plans.customer_delivery_fee_minor IS
    'Integer minor units, whole som for UZS. Never raised because a partner cost more: ADR 0013 records the gap as a DELIVERY_COST_SUBSIDY and the customer''s price does not move after they agreed to it.';
COMMENT ON COLUMN fulfillment.delivery_plans.branch_zone IS
    'The location IANA timezone. Uzbekistan is UTC+5 with no DST, so nothing shifts under a stored plan; this is here so an overnight pickup window can be rendered on the local day it belongs to.';

-- One live plan per order. A second is a second set of sourcing jobs racing for
-- one order, which is how two couriers arrive.
CREATE UNIQUE INDEX ux_plan_one_live
    ON fulfillment.delivery_plans (tenant_id, order_id)
    WHERE status <> 'CANCELLED';

-- The scheduler's claim query: plans due for sourcing, oldest first.
CREATE INDEX ix_plan_due
    ON fulfillment.delivery_plans (source_at)
    WHERE status IN ('PLANNED', 'WAITING_TO_SOURCE', 'RETRY_PENDING');

CREATE INDEX ix_plan_location_status
    ON fulfillment.delivery_plans (tenant_id, location_id, status, pickup_window_start);

-- ---------------------------------------------------------------------------
-- 2. The shipment
-- ---------------------------------------------------------------------------
--
-- Physical, and it exists only once something is carrying the order. Its states
-- are about a bag moving; the plan's states are about a decision being made,
-- and collapsing the two is how "assigned" comes to mean two different things.

CREATE TABLE fulfillment.shipments (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    order_id uuid NOT NULL,
    delivery_plan_id uuid NOT NULL,

    status varchar(24) NOT NULL DEFAULT 'PENDING',
    source_type varchar(16) NOT NULL,
    courier_id uuid,
    provider_binding_id uuid,
    provider_type varchar(32),
    external_shipment_id varchar(128),
    tracking_url text,

    assigned_at timestamptz,
    picked_up_at timestamptz,
    delivered_at timestamptz,
    cancelled_at timestamptz,
    cancellation_reason_code varchar(48),

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_shipment_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_shipment_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT fk_shipment_plan FOREIGN KEY (delivery_plan_id, tenant_id)
        REFERENCES fulfillment.delivery_plans (id, tenant_id),
    CONSTRAINT fk_shipment_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id),
    CONSTRAINT fk_shipment_binding FOREIGN KEY (tenant_id, provider_binding_id)
        REFERENCES integration.bindings (tenant_id, id),
    CONSTRAINT uq_shipment_identity UNIQUE (id, tenant_id),
    CONSTRAINT ck_shipment_status CHECK (status IN (
        'PENDING', 'ASSIGNED', 'PICKUP_PENDING', 'PICKED_UP', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT ck_shipment_source CHECK (source_type IN ('INTERNAL', 'PARTNER')),
    -- Exactly one source. Stated as equalities so the mixed rows -- a partner
    -- shipment with a courier, an internal one with a provider -- are
    -- unreachable rather than merely unlikely, which is what keeps the
    -- single-winner rule meaningful across both sourcing paths.
    CONSTRAINT ck_shipment_internal_courier CHECK (
        (source_type = 'INTERNAL') = (courier_id IS NOT NULL)),
    CONSTRAINT ck_shipment_partner_binding CHECK (
        (source_type = 'PARTNER') = (provider_binding_id IS NOT NULL)
        AND (source_type = 'PARTNER') = (provider_type IS NOT NULL)),
    CONSTRAINT ck_shipment_external_is_partner CHECK (
        external_shipment_id IS NULL OR source_type = 'PARTNER'),
    CONSTRAINT ck_shipment_assigned_pair CHECK (
        status = 'PENDING' OR status = 'CANCELLED' OR assigned_at IS NOT NULL),
    CONSTRAINT ck_shipment_delivered_pair CHECK (
        (status = 'DELIVERED') = (delivered_at IS NOT NULL)),
    CONSTRAINT ck_shipment_cancelled_pair CHECK (
        (status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    CONSTRAINT ck_shipment_order_of_events CHECK (
        (picked_up_at IS NULL OR assigned_at IS NULL OR picked_up_at >= assigned_at)
        AND (delivered_at IS NULL OR picked_up_at IS NULL OR delivered_at >= picked_up_at))
);

COMMENT ON COLUMN fulfillment.shipments.external_shipment_id IS
    'The partner''s own id. The only thing that can cancel or query a booking, which is why an uncertain create that produced one is reconciled by query and never repeated.';

-- ADR 0014's single-winner rule, held by the database rather than by
-- convention: one plan has at most one shipment that is not cancelled.
CREATE UNIQUE INDEX ux_shipment_one_active_per_plan
    ON fulfillment.shipments (tenant_id, delivery_plan_id)
    WHERE status <> 'CANCELLED';

-- A partner reference is unique per binding, so a webhook or a poll resolves to
-- exactly one shipment.
CREATE UNIQUE INDEX ux_shipment_external_reference
    ON fulfillment.shipments (tenant_id, provider_binding_id, external_shipment_id)
    WHERE external_shipment_id IS NOT NULL;

CREATE INDEX ix_shipment_courier_open
    ON fulfillment.shipments (tenant_id, courier_id, status)
    WHERE courier_id IS NOT NULL AND status NOT IN ('DELIVERED', 'CANCELLED');

-- ---------------------------------------------------------------------------
-- 3. Quotes
-- ---------------------------------------------------------------------------
--
-- Neither verified partner returns a redeemable quote object, so
-- external_quote_id and the partner's own expiry are nullable and
-- quote_validity_source records whether expires_at came from the partner or
-- from HorecaOS policy. A self-imposed TTL must never be mistaken for a partner
-- guarantee.

CREATE TABLE fulfillment.delivery_quotes (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    delivery_plan_id uuid NOT NULL,
    provider_binding_id uuid NOT NULL,

    request_id uuid NOT NULL,
    external_quote_id varchar(128),
    status varchar(16) NOT NULL DEFAULT 'RECEIVED',

    price_minor bigint,
    currency char(3),
    pickup_eta_seconds integer,
    delivery_eta_seconds integer,
    distance_meters integer,
    dead_head_meters integer,

    expires_at timestamptz,
    quote_validity_source varchar(16) NOT NULL,
    capability_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    failure_code varchar(48),
    received_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_quote_plan FOREIGN KEY (delivery_plan_id, tenant_id)
        REFERENCES fulfillment.delivery_plans (id, tenant_id),
    CONSTRAINT fk_quote_binding FOREIGN KEY (tenant_id, provider_binding_id)
        REFERENCES integration.bindings (tenant_id, id),
    CONSTRAINT uq_quote_identity UNIQUE (id, tenant_id),
    -- ADR 0014's stated key, on our own request id rather than the partner's,
    -- because neither partner issues one.
    CONSTRAINT uq_quote_request UNIQUE (provider_binding_id, request_id),
    CONSTRAINT ck_quote_status CHECK (status IN ('RECEIVED', 'REFUSED', 'EXPIRED', 'SELECTED')),
    CONSTRAINT ck_quote_validity_source CHECK (quote_validity_source IN ('PARTNER', 'HORECAOS_POLICY')),
    CONSTRAINT ck_quote_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    -- A price without a currency is a number nobody can compare, and comparing
    -- two partners is the only reason to store a quote.
    CONSTRAINT ck_quote_price_pair CHECK ((price_minor IS NULL) = (currency IS NULL)),
    CONSTRAINT ck_quote_price CHECK (price_minor IS NULL OR price_minor >= 0),
    CONSTRAINT ck_quote_received_has_price CHECK (status <> 'RECEIVED' OR price_minor IS NOT NULL),
    CONSTRAINT ck_quote_refused_has_reason CHECK (status <> 'REFUSED' OR failure_code IS NOT NULL),
    CONSTRAINT ck_quote_distances CHECK (
        (distance_meters IS NULL OR distance_meters >= 0)
        AND (dead_head_meters IS NULL OR dead_head_meters >= 0))
);

COMMENT ON COLUMN fulfillment.delivery_quotes.quote_validity_source IS
    'ADR 0014. Whether expires_at is the partner''s guarantee or a TTL HorecaOS imposed. Neither Yandex nor Noor returns an expiry, so today every row is HORECAOS_POLICY -- and the day one does, the difference is recorded rather than remembered.';
COMMENT ON COLUMN fulfillment.delivery_quotes.capability_snapshot IS
    'What the adapter declared at the moment this quote was scored. A selection re-read against today''s capability matrix is not the selection that was made.';

CREATE INDEX ix_quote_plan ON fulfillment.delivery_quotes (tenant_id, delivery_plan_id, received_at);

-- ---------------------------------------------------------------------------
-- 4. Assignment attempts
-- ---------------------------------------------------------------------------

CREATE TABLE fulfillment.assignment_attempts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    delivery_plan_id uuid NOT NULL,
    shipment_id uuid,
    sequence_number integer NOT NULL,

    source_type varchar(16) NOT NULL,
    courier_id uuid,
    provider_binding_id uuid,
    quote_id uuid,

    status varchar(24) NOT NULL DEFAULT 'REQUESTED',
    idempotency_key varchar(160) NOT NULL,
    external_assignment_id varchar(128),
    uncertain_outcome boolean NOT NULL DEFAULT false,

    -- The decision this attempt came out of, so an operator asking "why did this
    -- go to Yandex" gets the reason code and the policy that produced it rather
    -- than a reconstruction.
    decision_reason varchar(48) NOT NULL,
    policy_id uuid,
    policy_version integer,
    -- ADR 0042's gate answered the courier half; its enforcement mode is
    -- snapshotted here so a later policy change cannot restate a past offer.
    shift_enforcement_mode varchar(16),

    requested_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    accepted_at timestamptz,
    declined_at timestamptz,
    failed_at timestamptz,
    cancelled_at timestamptz,
    failure_code varchar(48),

    version integer NOT NULL DEFAULT 1,

    CONSTRAINT fk_attempt_plan FOREIGN KEY (delivery_plan_id, tenant_id)
        REFERENCES fulfillment.delivery_plans (id, tenant_id),
    CONSTRAINT fk_attempt_shipment FOREIGN KEY (shipment_id, tenant_id)
        REFERENCES fulfillment.shipments (id, tenant_id),
    CONSTRAINT fk_attempt_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id),
    CONSTRAINT fk_attempt_binding FOREIGN KEY (tenant_id, provider_binding_id)
        REFERENCES integration.bindings (tenant_id, id),
    CONSTRAINT fk_attempt_quote FOREIGN KEY (quote_id, tenant_id)
        REFERENCES fulfillment.delivery_quotes (id, tenant_id),
    CONSTRAINT uq_attempt_identity UNIQUE (id, tenant_id),
    CONSTRAINT uq_attempt_sequence UNIQUE (delivery_plan_id, sequence_number),
    -- The partner idempotency key, unique per tenant. This is what makes a
    -- replayed sourcing tick reuse a command rather than build an equivalent
    -- one, which on a partner whose create is live is the difference between a
    -- retry and a second courier.
    CONSTRAINT uq_attempt_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_attempt_source CHECK (source_type IN ('INTERNAL', 'PARTNER')),
    CONSTRAINT ck_attempt_status CHECK (status IN (
        'REQUESTED', 'OFFERED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'FAILED',
        'CANCELLED', 'UNCERTAIN')),
    -- Exactly one source reference, which is what keeps the single-winner
    -- shipment invariant meaningful across the internal and external paths.
    CONSTRAINT ck_attempt_internal_courier CHECK (
        (source_type = 'INTERNAL') = (courier_id IS NOT NULL)),
    CONSTRAINT ck_attempt_partner_binding CHECK (
        (source_type = 'PARTNER') = (provider_binding_id IS NOT NULL)),
    CONSTRAINT ck_attempt_quote_is_partner CHECK (
        quote_id IS NULL OR source_type = 'PARTNER'),
    -- An internal offer has a lifetime. A courier who is never told an offer
    -- lapsed holds an order nobody else can be given.
    CONSTRAINT ck_attempt_offer_expires CHECK (
        source_type <> 'INTERNAL' OR status <> 'OFFERED' OR expires_at IS NOT NULL),
    CONSTRAINT ck_attempt_accepted_pair CHECK ((status = 'ACCEPTED') = (accepted_at IS NOT NULL)),
    CONSTRAINT ck_attempt_accepted_has_shipment CHECK (
        status <> 'ACCEPTED' OR shipment_id IS NOT NULL),
    CONSTRAINT ck_attempt_failed_pair CHECK (
        (failed_at IS NULL) = (failure_code IS NULL)),
    CONSTRAINT ck_attempt_uncertain_flag CHECK (
        NOT uncertain_outcome OR status = 'UNCERTAIN'),
    CONSTRAINT ck_attempt_sequence CHECK (sequence_number >= 1),
    CONSTRAINT ck_attempt_policy_pair CHECK ((policy_id IS NULL) = (policy_version IS NULL)),
    CONSTRAINT ck_attempt_enforcement CHECK (
        shift_enforcement_mode IS NULL OR shift_enforcement_mode IN ('ENFORCED', 'ADVISORY', 'OFF'))
);

COMMENT ON TABLE fulfillment.assignment_attempts IS
    'ADR 0014. One row per thing sourcing asked of somebody -- an offer to a courier, a booking with a partner. The compare-and-set that makes one plan have one winner is ux_attempt_one_accepted below, and it is the only place that invariant lives.';

-- The single-winner compare-and-set. At most one accepted attempt per plan,
-- across both sourcing paths, enforced here rather than by a service that
-- counts first -- counting races two dispatchers into two couriers.
CREATE UNIQUE INDEX ux_attempt_one_accepted
    ON fulfillment.assignment_attempts (tenant_id, delivery_plan_id)
    WHERE status = 'ACCEPTED';

-- At most one live offer per plan, for the same reason.
CREATE UNIQUE INDEX ux_attempt_one_offered
    ON fulfillment.assignment_attempts (tenant_id, delivery_plan_id)
    WHERE status IN ('REQUESTED', 'OFFERED');

-- A courier's own board: what have I been offered and what am I carrying.
CREATE INDEX ix_attempt_courier
    ON fulfillment.assignment_attempts (tenant_id, courier_id, status)
    WHERE courier_id IS NOT NULL;

-- The reconciliation sweeper's index: attempts whose outcome is still unknown.
CREATE INDEX ix_attempt_uncertain
    ON fulfillment.assignment_attempts (tenant_id, requested_at)
    WHERE uncertain_outcome;

-- ---------------------------------------------------------------------------
-- 5. The durable scheduler
-- ---------------------------------------------------------------------------
--
-- PostgreSQL and not a Kafka delayed message. ADR 0014 rejects the latter by
-- name: the delay is approximate, invisible to an operator, and cannot be
-- cancelled or rescheduled when the kitchen changes its estimate.

CREATE TABLE fulfillment.delivery_sourcing_jobs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    delivery_plan_id uuid NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'PENDING',
    due_at timestamptz NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,

    -- The lease. A worker claims a job by writing its token and a deadline in
    -- one conditional update; a worker that dies leaves a lease that expires
    -- rather than a job nobody may touch.
    lease_token uuid,
    leased_until timestamptz,
    leased_by varchar(128),

    -- SourcingProgress, so a restart resumes rather than re-offering an order to
    -- a courier who already declined it.
    checkpoint jsonb NOT NULL DEFAULT '{}'::jsonb,
    last_error_code varchar(48),
    last_error_at timestamptz,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_job_plan FOREIGN KEY (delivery_plan_id, tenant_id)
        REFERENCES fulfillment.delivery_plans (id, tenant_id),
    CONSTRAINT uq_job_identity UNIQUE (id, tenant_id),
    CONSTRAINT ck_job_status CHECK (status IN (
        'PENDING', 'LEASED', 'COMPLETED', 'ABANDONED')),
    CONSTRAINT ck_job_attempts CHECK (attempt_count >= 0),
    -- A lease is a token, a holder and a deadline together. Any one of them
    -- alone is a job that either nobody can claim or everybody can.
    CONSTRAINT ck_job_lease_triple CHECK (
        (lease_token IS NULL) = (leased_until IS NULL)
        AND (lease_token IS NULL) = (leased_by IS NULL)),
    CONSTRAINT ck_job_leased_has_lease CHECK (status <> 'LEASED' OR lease_token IS NOT NULL),
    CONSTRAINT ck_job_error_pair CHECK ((last_error_code IS NULL) = (last_error_at IS NULL))
);

-- ADR 0014's "unique active job per delivery plan". Two jobs for one plan is
-- two workers sourcing the same order.
CREATE UNIQUE INDEX ux_job_one_active
    ON fulfillment.delivery_sourcing_jobs (tenant_id, delivery_plan_id)
    WHERE status IN ('PENDING', 'LEASED');

-- The claim query: due, and either unleased or holding an expired lease.
CREATE INDEX ix_job_claimable
    ON fulfillment.delivery_sourcing_jobs (due_at)
    WHERE status IN ('PENDING', 'LEASED');

-- ---------------------------------------------------------------------------
-- 6. Exceptions
-- ---------------------------------------------------------------------------

CREATE TABLE fulfillment.delivery_exceptions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    delivery_plan_id uuid NOT NULL,
    shipment_id uuid,

    reason_code varchar(48) NOT NULL,
    severity varchar(16) NOT NULL DEFAULT 'ACTION_REQUIRED',
    status varchar(16) NOT NULL DEFAULT 'OPEN',
    detail varchar(400),

    raised_at timestamptz NOT NULL DEFAULT now(),
    raised_by varchar(128) NOT NULL,
    acknowledged_at timestamptz,
    acknowledged_by varchar(128),
    resolved_at timestamptz,
    resolved_by varchar(128),
    resolution_code varchar(48),

    CONSTRAINT fk_exception_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_exception_plan FOREIGN KEY (delivery_plan_id, tenant_id)
        REFERENCES fulfillment.delivery_plans (id, tenant_id),
    CONSTRAINT fk_exception_shipment FOREIGN KEY (shipment_id, tenant_id)
        REFERENCES fulfillment.shipments (id, tenant_id),
    CONSTRAINT uq_exception_identity UNIQUE (id, tenant_id),
    CONSTRAINT ck_exception_severity CHECK (severity IN ('INFORMATIONAL', 'ACTION_REQUIRED')),
    CONSTRAINT ck_exception_status CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT ck_exception_ack_pair CHECK (
        (acknowledged_at IS NULL) = (acknowledged_by IS NULL)),
    CONSTRAINT ck_exception_resolved_triple CHECK (
        (resolved_at IS NULL) = (resolved_by IS NULL)
        AND (resolved_at IS NULL) = (resolution_code IS NULL)),
    CONSTRAINT ck_exception_resolved_status CHECK (
        (status = 'RESOLVED') = (resolved_at IS NOT NULL))
);

COMMENT ON COLUMN fulfillment.delivery_exceptions.reason_code IS
    'ADR 0014''s named cases: NO_PROVIDER, LATE_ASSIGNMENT, PROMISE_UNREACHABLE, AWAITING_RECONCILIATION, ABANDONED_HOLD, LATE_RESTAURANT, COURIER_NO_SHOW, ADDRESS_ISSUE. A closed list, because an exception screen filtered by free text is one nobody filters.';

-- One open exception per plan and reason. A sweeper that runs every minute must
-- not produce sixty rows an hour saying the same thing.
CREATE UNIQUE INDEX ux_exception_one_open
    ON fulfillment.delivery_exceptions (tenant_id, delivery_plan_id, reason_code)
    WHERE status <> 'RESOLVED';

CREATE INDEX ix_exception_open
    ON fulfillment.delivery_exceptions (tenant_id, location_id, status, raised_at);

-- ---------------------------------------------------------------------------
-- 7. The V0040 columns that referenced tables which did not exist
-- ---------------------------------------------------------------------------
--
-- V0040 wrote shipment_id and assignment_attempt_id as bare uuids because ADR
-- 0014 owned the tables and had not created them. They exist now, so the
-- references become real: an earning whose shipment cannot be found is a payment
-- nobody can trace back to a delivery.

ALTER TABLE fulfillment.courier_assignment_earnings
    ADD CONSTRAINT fk_earning_shipment FOREIGN KEY (shipment_id, tenant_id)
        REFERENCES fulfillment.shipments (id, tenant_id),
    ADD CONSTRAINT fk_earning_attempt FOREIGN KEY (assignment_attempt_id, tenant_id)
        REFERENCES fulfillment.assignment_attempts (id, tenant_id);

ALTER TABLE fulfillment.delivery_cost_lines
    ADD CONSTRAINT fk_cost_line_shipment FOREIGN KEY (shipment_id, tenant_id)
        REFERENCES fulfillment.shipments (id, tenant_id);

-- partner_delivery_invoice_lines.shipment_id stays unconstrained on purpose: a
-- partner invoice may name a shipment HorecaOS has no record of, and that
-- unmatched line is exactly what the reconciliation report exists to show.

-- ---------------------------------------------------------------------------
-- 8. Grants
-- ---------------------------------------------------------------------------
--
-- Quotes hold no UPDATE and nothing holds DELETE except the job table: they are
-- the evidence a disputed selection is answered from, and ADR 0014 requires the
-- selection to be reproducible from it.

GRANT SELECT, INSERT, UPDATE ON fulfillment.delivery_plans TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON fulfillment.shipments TO horecaos_application;
GRANT SELECT, INSERT ON fulfillment.delivery_quotes TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON fulfillment.assignment_attempts TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.delivery_sourcing_jobs TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON fulfillment.delivery_exceptions TO horecaos_application;

-- NOTE for whoever applies this: delivery_quotes is INSERT/SELECT only, which
-- means the RECEIVED -> SELECTED / EXPIRED transitions in ck_quote_status cannot
-- be written today. That is deliberate and matches how V0040 treats the ledger:
-- "selected" is derivable from the winning assignment_attempts.quote_id, and a
-- quote row the application can rewrite is not evidence. If a later reviewer
-- wants the status column to move, grant UPDATE here rather than working around
-- it in code.
