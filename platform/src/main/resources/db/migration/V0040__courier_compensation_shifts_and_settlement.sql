-- ADR 0042: courier compensation, shifts, and settlement.
--
-- Couriers on an in-house fleet are engaged as registered self-employed persons.
-- Everything below follows from that one sentence. There is no withholding
-- column, no net-of-tax column and no payslip, because the tenant owes a gross
-- figure against an invoice the courier issues; the money that leaves the tenant
-- is `amount_payable_minor`, deliberately not `net_payable_minor`, because "net"
-- on a worker document means net of tax everywhere it is read and this figure is
-- not that.
--
-- ---------------------------------------------------------------------------
-- Why these tables live in the `fulfillment` schema and the code lives in a
-- `courier` module
-- ---------------------------------------------------------------------------
--
-- ADR 0042 names every table `fulfillment.courier_*`, and renaming them here
-- would make the ADR unreadable against the database it describes. The Java
-- module is separate for the same reason ADR 0041 separated the kitchen: what
-- fulfillment holds today is zones, tariffs and the customer's delivery charge,
-- which changes when pricing changes; what this holds is an engagement, hours,
-- and a ledger, which changes when labour arrangements or finance change. They
-- share a schema and no reason to change together.
--
-- ---------------------------------------------------------------------------
-- What this migration deliberately does not create, and why
-- ---------------------------------------------------------------------------
--
-- `courier_roster_entries`. ADR 0042 lists it and this migration omits it,
-- together with `courier_shifts.roster_entry_id`. A roster entry is an offer of
-- work whose only two consumers are the `ENFORCED_WITH_ROSTER` shift gate and
-- ADR 0036's capacity signal, and neither exists yet. A nullable foreign key no
-- writer ever populates is the "configuration that silently does nothing"
-- V0030 refused three times; the column arrives with the code that reads it.
--
-- `courier_assignment_attempts`. ADR 0014 owns the attempt, including the
-- conditional-update ceiling on concurrent assignments. Creating a second
-- attempt table here so that this ADR could snapshot a policy onto it would give
-- dispatch two rows to disagree about. What this migration does instead is
-- snapshot the enforcement policy onto the shift and onto the earning, both of
-- which it owns, so no stored decision is re-read against a later policy.
--
-- `reporting.fact_delivery`. ADR 0043 owns that grain and V0031 has not created
-- it yet. The two columns ADR 0042 asks it for -- `courier_cost_som` and
-- `cost_basis` -- are ADR 0043's to add; `fulfillment.delivery_cost_lines` below
-- is the source they will be projected from.

-- ---------------------------------------------------------------------------
-- 1. The courier, their type, and their engagement
-- ---------------------------------------------------------------------------

-- The vehicle class and the dispatch ceilings that come with it. Tenant-owned:
-- a scooter is a scooter at every branch, and the difference between branches is
-- which rate card applies, not what a scooter is.
CREATE TABLE fulfillment.courier_types (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    code varchar(32) NOT NULL,
    display_name varchar(120) NOT NULL,
    vehicle_class varchar(16) NOT NULL,
    -- The distance band this type may be offered. An order outside the band is
    -- not an error, it is a courier who should not be asked to take it.
    min_distance_meters integer NOT NULL DEFAULT 0,
    max_distance_meters integer,
    max_concurrent_assignments smallint NOT NULL DEFAULT 1,
    offer_ttl_seconds integer NOT NULL DEFAULT 60,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_courier_type_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT uq_courier_type_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_courier_type_identity UNIQUE (id, tenant_id),
    CONSTRAINT ck_courier_type_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_courier_type_vehicle CHECK (vehicle_class IN (
        'FOOT', 'BICYCLE', 'SCOOTER', 'MOTORCYCLE', 'CAR')),
    CONSTRAINT ck_courier_type_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_courier_type_band CHECK (
        max_distance_meters IS NULL OR max_distance_meters > min_distance_meters),
    CONSTRAINT ck_courier_type_concurrency CHECK (max_concurrent_assignments BETWEEN 1 AND 10),
    CONSTRAINT ck_courier_type_ttl CHECK (offer_ttl_seconds BETWEEN 15 AND 900)
);

COMMENT ON COLUMN fulfillment.courier_types.max_concurrent_assignments IS
    'ADR 0042. The ceiling dispatch enforces with a conditional update rather than a count-then-insert, because counting first races two dispatchers into a third order.';
COMMENT ON COLUMN fulfillment.courier_types.max_distance_meters IS
    'NULL means unbounded. A car has no upper band; a courier on foot does.';

-- The person. Their name is personal data under ADR 0029 and is therefore
-- envelope-encrypted; everything an operations screen sorts, filters or counts
-- by is held in clear beside it.
CREATE TABLE fulfillment.couriers (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    courier_type_id uuid NOT NULL,
    -- The IAM subject the courier signs in as. This is what makes "a courier
    -- reads their own ledger and nobody else's" answerable in SQL.
    principal_subject varchar(128) NOT NULL,
    display_reference varchar(32) NOT NULL,
    protected_full_name text NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_courier_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT fk_courier_type FOREIGN KEY (courier_type_id, tenant_id)
        REFERENCES fulfillment.courier_types (id, tenant_id),
    CONSTRAINT uq_courier_principal UNIQUE (tenant_id, principal_subject),
    CONSTRAINT uq_courier_reference UNIQUE (tenant_id, display_reference),
    CONSTRAINT uq_courier_identity UNIQUE (id, tenant_id),
    CONSTRAINT ck_courier_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

COMMENT ON COLUMN fulfillment.couriers.display_reference IS
    'ADR 0029. A non-personal handle -- "K-014" -- that a dispatch board, an event payload and a log line may carry. Without one, every screen that needs to name a courier has a reason to reveal their name.';
COMMENT ON COLUMN fulfillment.couriers.protected_full_name IS
    'ADR 0029 PERSONAL, envelope-encrypted and bound to this row by AAD. Revealed under a declared purpose, which is audited.';

-- One engagement per courier per tenant. A courier working for two tenants is
-- verified twice and may be ACTIVE in one and SUSPENDED_COMPLIANCE in the other;
-- that is duplicated effort and the only model consistent with ADR 0029's
-- per-tenant key scope.
CREATE TABLE fulfillment.courier_engagements (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    courier_id uuid NOT NULL,
    engagement_type varchar(24) NOT NULL,
    status varchar(32) NOT NULL,
    engaged_from date NOT NULL,
    engaged_until date,

    protected_registration_ref text,
    registration_valid_until date,
    registration_verified_at timestamptz,
    registration_verified_by varchar(128),
    verification_method varchar(24),
    evidence_media_id uuid,
    reverification_due_on date,
    warning_state varchar(16) NOT NULL DEFAULT 'VALID',
    warning_state_changed_at timestamptz,
    suspension_reason_code varchar(48),
    suspended_at timestamptz,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_engagement_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id),
    CONSTRAINT fk_engagement_evidence FOREIGN KEY (evidence_media_id)
        REFERENCES media.assets (asset_id),
    CONSTRAINT uq_engagement_identity UNIQUE (id, tenant_id),
    -- SELF_EMPLOYED is the only implemented value. EMPLOYEE is named in ADR 0042
    -- and refused here on purpose: a tenant that employs its couriers needs
    -- withholding, net pay and a payslip, none of which this schema has, and the
    -- honest answer is a rejected INSERT rather than a statement that quietly
    -- means something else.
    CONSTRAINT ck_engagement_type CHECK (engagement_type = 'SELF_EMPLOYED'),
    CONSTRAINT ck_engagement_status CHECK (status IN (
        'PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED_COMPLIANCE',
        'SUSPENDED_OPERATIONAL', 'ENDED')),
    CONSTRAINT ck_engagement_warning CHECK (warning_state IN ('VALID', 'EXPIRING', 'LAPSED')),
    CONSTRAINT ck_engagement_method CHECK (
        verification_method IS NULL OR verification_method IN ('MANUAL_ATTESTATION', 'REGISTRY_LOOKUP')),
    CONSTRAINT ck_engagement_dates CHECK (engaged_until IS NULL OR engaged_until >= engaged_from),
    -- Pair completeness stated as an equality, not as a disjunction: an
    -- attestation instant with no attesting principal is an attestation nobody
    -- can be asked about, and `(a IS NULL AND b IS NULL) OR (...)` leaves the
    -- mixed case reachable through the three-valued hole.
    CONSTRAINT ck_engagement_verification_pair CHECK (
        (registration_verified_at IS NULL) = (registration_verified_by IS NULL)
        AND (registration_verified_at IS NULL) = (verification_method IS NULL)),
    -- There is no path from onboarding straight to dispatchable. An ACTIVE
    -- engagement carries a recorded verification, an attested validity date, and
    -- the date that verification decays.
    CONSTRAINT ck_engagement_active_is_verified CHECK (
        status <> 'ACTIVE' OR (
            registration_verified_at IS NOT NULL
            AND protected_registration_ref IS NOT NULL
            AND registration_valid_until IS NOT NULL
            AND reverification_due_on IS NOT NULL)),
    CONSTRAINT ck_engagement_suspension_pair CHECK (
        (suspended_at IS NULL) = (suspension_reason_code IS NULL))
);

COMMENT ON COLUMN fulfillment.courier_engagements.protected_registration_ref IS
    'ADR 0029 PERSONAL_SENSITIVE. The self-employment registration identifier, envelope-encrypted and therefore not queryable. Revealed only by the accountant export, under a declared purpose, audited.';
COMMENT ON COLUMN fulfillment.courier_engagements.registration_valid_until IS
    'ADR 0042. Held in clear, deliberately. "Which couriers expire this month" is the only reason to hold this data at all and an encrypted date cannot answer it. A date beside a courier row is a much smaller disclosure than the number it belongs to.';
COMMENT ON COLUMN fulfillment.courier_engagements.reverification_due_on IS
    'The earlier of the attested validity date and the verification instant plus courier.registration.reverification_days. A manual attestation is evidence about a past instant, not a standing fact, so it decays even when the attested date has not passed.';
COMMENT ON COLUMN fulfillment.courier_engagements.warning_state IS
    'VALID -> EXPIRING -> LAPSED. Only LAPSED changes what dispatch may do; EXPIRING exists so that the change is not a surprise on a Friday evening.';

-- One live engagement per courier. Two would make "is this courier dispatchable"
-- depend on which row a query read first, which is the whole question.
CREATE UNIQUE INDEX ux_engagement_one_live
    ON fulfillment.courier_engagements (tenant_id, courier_id)
    WHERE status <> 'ENDED';

-- The sweeper's index, and the operations screen's. Both ask the same question:
-- whose registration falls due before a date.
CREATE INDEX ix_engagement_expiry
    ON fulfillment.courier_engagements (tenant_id, reverification_due_on)
    WHERE status IN ('ACTIVE', 'SUSPENDED_COMPLIANCE');

-- Which rung of the ADR 0020 ladder has already been rung, for whom. Without
-- this the sweeper either notifies every hour or holds its state in a variable
-- that a restart forgets.
CREATE TABLE fulfillment.courier_registration_notices (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    engagement_id uuid NOT NULL,
    rung_days smallint NOT NULL,
    audience varchar(16) NOT NULL,
    valid_until date NOT NULL,
    sent_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_notice_engagement FOREIGN KEY (engagement_id, tenant_id)
        REFERENCES fulfillment.courier_engagements (id, tenant_id),
    CONSTRAINT ck_notice_audience CHECK (audience IN ('COURIER', 'MANAGER')),
    CONSTRAINT ck_notice_rung CHECK (rung_days IN (30, 14, 7, 1, 0)),
    -- Keyed by the date being warned about as well as the rung, so re-verifying
    -- to a new expiry date starts a fresh ladder instead of being silenced by
    -- the old one.
    CONSTRAINT uq_notice_rung UNIQUE (engagement_id, valid_until, rung_days, audience)
);

COMMENT ON COLUMN fulfillment.courier_registration_notices.rung_days IS
    'Days remaining when this notice fired: 30, 14, 7, 1 to the courier, 14 downwards to the branch manager, 0 for the lapse itself. A courier who ignores the message is the tenant''s problem too, which is why the ladder escalates.';

-- ---------------------------------------------------------------------------
-- 2. Rate cards
-- ---------------------------------------------------------------------------
--
-- Versioned, priority-ordered, typed components and no scripting, for the reason
-- ADR 0018 gives about pricing rules: a tenant-authored expression is a program
-- nobody reviews running against money somebody is owed.

CREATE TABLE fulfillment.courier_rate_cards (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid,
    location_id uuid,
    courier_type_id uuid,
    code varchar(32) NOT NULL,
    card_version integer NOT NULL DEFAULT 1,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    currency char(3) NOT NULL,
    effective_from timestamptz,
    effective_to timestamptz,
    activated_by varchar(128),
    activated_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_rate_card_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT fk_rate_card_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_rate_card_courier_type FOREIGN KEY (courier_type_id, tenant_id)
        REFERENCES fulfillment.courier_types (id, tenant_id),
    CONSTRAINT uq_rate_card_version UNIQUE (tenant_id, code, card_version),
    CONSTRAINT uq_rate_card_identity UNIQUE (id, tenant_id),
    CONSTRAINT ck_rate_card_status CHECK (status IN ('DRAFT', 'ACTIVE', 'SUPERSEDED', 'ARCHIVED')),
    CONSTRAINT ck_rate_card_currency CHECK (currency ~ '^[A-Z]{3}$'),
    -- A location-scoped card must name its brand, or the foreign key above
    -- cannot tell which brand's branch it means.
    CONSTRAINT ck_rate_card_scope CHECK (location_id IS NULL OR brand_id IS NOT NULL),
    CONSTRAINT ck_rate_card_activation_pair CHECK (
        (activated_at IS NULL) = (activated_by IS NULL)
        AND (activated_at IS NULL) = (effective_from IS NULL)),
    CONSTRAINT ck_rate_card_active_is_activated CHECK (
        status <> 'ACTIVE' OR activated_at IS NOT NULL),
    CONSTRAINT ck_rate_card_window CHECK (
        effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from)
);

COMMENT ON COLUMN fulfillment.courier_rate_cards.card_version IS
    'Stamped onto every earning at acceptance. A statement line names the version it was computed under, so raising rates in October does not silently restate September.';

CREATE TABLE fulfillment.courier_rate_components (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    rate_card_id uuid NOT NULL,
    component_type varchar(24) NOT NULL,
    priority smallint NOT NULL DEFAULT 0,
    amount_minor bigint NOT NULL,
    band_from_meters integer,
    band_to_meters integer,
    minimum_paid_seconds integer,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_rate_component_card FOREIGN KEY (rate_card_id, tenant_id)
        REFERENCES fulfillment.courier_rate_cards (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT ck_rate_component_type CHECK (component_type IN (
        'PER_SHIFT_FIXED', 'PER_ORDER', 'PER_KM_BAND', 'PER_ORDER_MINIMUM')),
    CONSTRAINT ck_rate_component_amount CHECK (amount_minor >= 0),
    -- A band component carries a lower bound; an unbounded upper bound is NULL,
    -- which is what lets the top band cover "and everything beyond".
    CONSTRAINT ck_rate_component_band CHECK (
        (component_type = 'PER_KM_BAND') = (band_from_meters IS NOT NULL)),
    CONSTRAINT ck_rate_component_band_order CHECK (
        band_to_meters IS NULL OR band_from_meters IS NULL OR band_to_meters > band_from_meters),
    CONSTRAINT ck_rate_component_minimum_seconds CHECK (
        minimum_paid_seconds IS NULL OR minimum_paid_seconds >= 0)
);

COMMENT ON COLUMN fulfillment.courier_rate_components.amount_minor IS
    'Integer minor units. For UZS a minor unit is a whole som, so this is som and never tiyin. For PER_KM_BAND it is the amount per whole kilometre of the distance falling inside this band.';
COMMENT ON COLUMN fulfillment.courier_rate_components.minimum_paid_seconds IS
    'PER_SHIFT_FIXED only. A shift opened, spent on break and closed earns nothing fixed, which is the point: paid seconds exclude breaks.';

-- Band coverage -- zero to unbounded, no gap and no overlap -- is validated in
-- code at activation rather than by a constraint. An exclusion constraint can
-- reject an overlap but cannot see a gap, and a gap is the failure that matters:
-- an order at exactly the boundary earns nothing, and the courier finds it
-- before the tenant does.
CREATE INDEX ix_rate_components_card
    ON fulfillment.courier_rate_components (tenant_id, rate_card_id, component_type, band_from_meters);

-- ---------------------------------------------------------------------------
-- 3. Settlement periods
-- ---------------------------------------------------------------------------
--
-- Created before shifts and earnings because both stamp a period identifier at
-- insert. Deriving the period by a date query at close time is how an entry
-- written a second after midnight lands in the wrong month.

CREATE TABLE fulfillment.courier_settlement_periods (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    courier_id uuid NOT NULL,
    engagement_id uuid NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'OPEN',
    currency char(3) NOT NULL,

    gross_earnings_minor bigint NOT NULL DEFAULT 0,
    adjustments_minor bigint NOT NULL DEFAULT 0,
    cash_held_minor bigint NOT NULL DEFAULT 0,
    amount_payable_minor bigint NOT NULL DEFAULT 0,

    delivered_count integer NOT NULL DEFAULT 0,
    on_time_count integer NOT NULL DEFAULT 0,
    distance_meters bigint NOT NULL DEFAULT 0,
    paid_seconds bigint NOT NULL DEFAULT 0,
    shift_count integer NOT NULL DEFAULT 0,

    compliance_flag boolean NOT NULL DEFAULT false,
    statement_hash char(64),
    closed_by varchar(128),
    closed_at timestamptz,
    settled_at timestamptz,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_period_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id),
    CONSTRAINT fk_period_engagement FOREIGN KEY (engagement_id, tenant_id)
        REFERENCES fulfillment.courier_engagements (id, tenant_id),
    CONSTRAINT uq_period_start UNIQUE (tenant_id, courier_id, period_start),
    CONSTRAINT uq_period_identity UNIQUE (id, tenant_id),
    CONSTRAINT ck_period_status CHECK (status IN ('OPEN', 'CLOSING', 'CLOSED', 'SETTLED')),
    CONSTRAINT ck_period_bounds CHECK (period_end >= period_start),
    CONSTRAINT ck_period_currency CHECK (currency ~ '^[A-Z]{3}$'),
    -- The statement's arithmetic, held by the database rather than by whichever
    -- screen renders it. Two screens showing two different "К оплате" for one
    -- courier is the failure ADR 0042 exists to prevent, and the cheapest place
    -- to prevent it is the place both screens read from.
    CONSTRAINT ck_period_payable CHECK (
        amount_payable_minor = gross_earnings_minor + adjustments_minor - cash_held_minor),
    CONSTRAINT ck_period_close_pair CHECK (
        (closed_at IS NULL) = (closed_by IS NULL)
        AND (closed_at IS NULL) = (statement_hash IS NULL)),
    CONSTRAINT ck_period_closed_is_hashed CHECK (
        status NOT IN ('CLOSED', 'SETTLED') OR statement_hash IS NOT NULL),
    CONSTRAINT ck_period_settled_pair CHECK (
        (status = 'SETTLED') = (settled_at IS NOT NULL)),
    CONSTRAINT ck_period_counts CHECK (
        delivered_count >= 0 AND on_time_count >= 0 AND on_time_count <= delivered_count
        AND distance_meters >= 0 AND paid_seconds >= 0 AND shift_count >= 0)
);

COMMENT ON COLUMN fulfillment.courier_settlement_periods.amount_payable_minor IS
    'ADR 0042. Gross plus adjustments less cash held. Deliberately not named net_payable_minor: "net" on a worker settlement document means net of tax everywhere it is read, and no tax has been deducted from this figure and none will be.';
COMMENT ON COLUMN fulfillment.courier_settlement_periods.cash_held_minor IS
    'A settlement mechanic, not a deduction from earnings. It appears in its own block on the statement because merging it into the earnings figure is how a courier concludes he was paid less than he earned.';
COMMENT ON COLUMN fulfillment.courier_settlement_periods.compliance_flag IS
    'Set when any work in the period fell after a registration lapse. It never withholds payment -- the work was done and the money is owed -- it requires ADR 0027 four-eyes approval before the payout is authorised, so an accountant sees the exposure before the transfer rather than after.';

-- One OPEN period per courier. The period is what an entry is stamped with at
-- insert, so "which one" must have exactly one answer.
CREATE UNIQUE INDEX ux_period_one_open
    ON fulfillment.courier_settlement_periods (tenant_id, courier_id)
    WHERE status = 'OPEN';

CREATE INDEX ix_period_settled_at
    ON fulfillment.courier_settlement_periods (settled_at)
    WHERE status = 'SETTLED';

-- The closed statement, stored whole and never recomputed. A report that
-- recomputes a figure is a report that can disagree with the document a courier
-- was paid against.
CREATE TABLE fulfillment.courier_settlement_statements (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    settlement_period_id uuid NOT NULL,
    statement_hash char(64) NOT NULL,
    document jsonb NOT NULL,
    generated_at timestamptz NOT NULL DEFAULT now(),
    generated_by varchar(128) NOT NULL,

    CONSTRAINT fk_statement_period FOREIGN KEY (settlement_period_id, tenant_id)
        REFERENCES fulfillment.courier_settlement_periods (id, tenant_id),
    CONSTRAINT uq_statement_period UNIQUE (settlement_period_id),
    CONSTRAINT ck_statement_hash CHECK (statement_hash ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE fulfillment.courier_settlement_statements IS
    'ADR 0042. The immutable hashed statement produced at close. INSERT and SELECT only: a closed period is never reopened, because reopening changes a figure somebody has already been paid against.';

-- ---------------------------------------------------------------------------
-- 4. Shifts and breaks
-- ---------------------------------------------------------------------------
--
-- Shift authority is per transition, and two of those transitions are held by
-- the database. A manager who can open a shift can create paid hours for
-- somebody who was at home, and a manager who can end a break is directing a
-- self-employed person's rest periods -- which is the fact pattern that
-- reclassifies the engagement.

CREATE TABLE fulfillment.courier_shifts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    courier_id uuid NOT NULL,
    engagement_id uuid NOT NULL,
    status varchar(24) NOT NULL,
    duty_state varchar(16) NOT NULL DEFAULT 'AVAILABLE',
    opened_at timestamptz NOT NULL,
    closed_at timestamptz,
    open_source varchar(16) NOT NULL DEFAULT 'COURIER',
    close_source varchar(16),
    close_reason_code varchar(48),

    protected_open_point text,
    protected_close_point text,

    paid_seconds bigint,
    break_seconds bigint NOT NULL DEFAULT 0,
    variance_seconds bigint,

    enforcement_mode varchar(16) NOT NULL,
    enforcement_policy_id uuid,
    enforcement_policy_version integer,

    approval_request_id uuid,
    settlement_period_id uuid,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_shift_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_shift_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id),
    CONSTRAINT fk_shift_engagement FOREIGN KEY (engagement_id, tenant_id)
        REFERENCES fulfillment.courier_engagements (id, tenant_id),
    CONSTRAINT fk_shift_period FOREIGN KEY (settlement_period_id, tenant_id)
        REFERENCES fulfillment.courier_settlement_periods (id, tenant_id),
    CONSTRAINT uq_shift_identity UNIQUE (id, tenant_id),
    CONSTRAINT ck_shift_status CHECK (status IN (
        'OPEN', 'CLOSE_REQUESTED', 'RECONCILING', 'AWAITING_APPROVAL', 'CLOSED',
        'AUTO_CLOSED', 'SETTLED')),
    CONSTRAINT ck_shift_duty_state CHECK (duty_state IN (
        'AVAILABLE', 'ON_BREAK', 'AT_CAPACITY', 'UNREACHABLE')),
    -- Only the courier opens. Stated as a constraint and not only as a service
    -- rule, because the service rule is one refactor away from being bypassed
    -- and the consequence is fabricated paid hours.
    CONSTRAINT ck_shift_open_source CHECK (open_source = 'COURIER'),
    CONSTRAINT ck_shift_close_source CHECK (
        close_source IS NULL OR close_source IN ('COURIER', 'MANAGER', 'SWEEPER')),
    CONSTRAINT ck_shift_close_pair CHECK ((closed_at IS NULL) = (close_source IS NULL)),
    -- Ending service and sending somebody home is the tenant's to decide; doing
    -- it without saying why is not.
    CONSTRAINT ck_shift_manager_close_reason CHECK (
        close_source <> 'MANAGER' OR close_reason_code IS NOT NULL),
    CONSTRAINT ck_shift_enforcement CHECK (enforcement_mode IN ('ENFORCED', 'ADVISORY', 'OFF')),
    CONSTRAINT ck_shift_enforcement_pair CHECK (
        (enforcement_policy_id IS NULL) = (enforcement_policy_version IS NULL)),
    CONSTRAINT ck_shift_seconds CHECK (
        break_seconds >= 0
        AND (paid_seconds IS NULL OR paid_seconds >= 0)),
    CONSTRAINT ck_shift_paid_when_closed CHECK (
        status NOT IN ('CLOSED', 'AUTO_CLOSED', 'AWAITING_APPROVAL', 'SETTLED')
        OR paid_seconds IS NOT NULL)
);

COMMENT ON COLUMN fulfillment.courier_shifts.paid_seconds IS
    'Wall time from open to close, less time spent ON_BREAK. Never taken from ADR 0014 courier_availability, which is a dispatch toggle and must never become a source of paid hours.';
COMMENT ON COLUMN fulfillment.courier_shifts.enforcement_mode IS
    'ADR 0030 courier.shift.enforcement, snapshotted at open together with the policy version. Without the snapshot, tightening the policy in October makes September''s shifts look illegal.';
COMMENT ON COLUMN fulfillment.courier_shifts.duty_state IS
    'AT_CAPACITY and UNREACHABLE are derived from active assignment count and telemetry staleness and are never settable. A settable derived state is one that will disagree with what it derives from.';
COMMENT ON COLUMN fulfillment.courier_shifts.protected_open_point IS
    'ADR 0029 PERSONAL_SENSITIVE, envelope-encrypted. Held to answer "was the courier at the branch when they opened", never disclosed to a customer -- product decided on 2026-08-23 that customers see status milestones only.';

-- One live shift per courier. A second open shift is a second set of paid hours
-- for the same wall clock.
CREATE UNIQUE INDEX ux_shift_one_live
    ON fulfillment.courier_shifts (tenant_id, courier_id)
    WHERE status IN ('OPEN', 'CLOSE_REQUESTED', 'RECONCILING');

CREATE INDEX ix_shift_location_open
    ON fulfillment.courier_shifts (tenant_id, location_id, status, opened_at);

CREATE TABLE fulfillment.courier_shift_breaks (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    shift_id uuid NOT NULL,
    started_at timestamptz NOT NULL,
    ended_at timestamptz,
    ended_by_source varchar(16),

    CONSTRAINT fk_break_shift FOREIGN KEY (shift_id, tenant_id)
        REFERENCES fulfillment.courier_shifts (id, tenant_id),
    CONSTRAINT ck_break_pair CHECK ((ended_at IS NULL) = (ended_by_source IS NULL)),
    CONSTRAINT ck_break_order CHECK (ended_at IS NULL OR ended_at >= started_at),
    -- The courier ends their own break. A shift close ends an open break as a
    -- side effect and records SHIFT_CLOSE, which is a shift transition rather
    -- than somebody deciding a rest period is over.
    CONSTRAINT ck_break_ended_by CHECK (
        ended_by_source IS NULL OR ended_by_source IN ('COURIER', 'SHIFT_CLOSE'))
);

CREATE UNIQUE INDEX ux_break_one_open
    ON fulfillment.courier_shift_breaks (shift_id)
    WHERE ended_at IS NULL;

-- ---------------------------------------------------------------------------
-- 5. Earnings at delivery
-- ---------------------------------------------------------------------------

CREATE TABLE fulfillment.courier_assignment_earnings (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    courier_id uuid NOT NULL,
    shift_id uuid,
    shipment_id uuid NOT NULL,
    assignment_attempt_id uuid NOT NULL,
    legal_entity_id uuid,
    location_id uuid NOT NULL,
    business_date date NOT NULL,

    rate_card_id uuid NOT NULL,
    rate_card_version integer NOT NULL,
    courier_type_id uuid NOT NULL,

    distance_meters integer NOT NULL,
    distance_source varchar(24) NOT NULL,
    on_time_outcome varchar(16) NOT NULL,
    promised_delivery_end timestamptz,
    grace_seconds integer NOT NULL DEFAULT 0,
    on_time_policy_version integer NOT NULL DEFAULT 1,
    delivered_at timestamptz NOT NULL,
    kitchen_handover_at timestamptz,
    pickup_window_end timestamptz,

    fixed_minor bigint NOT NULL DEFAULT 0,
    per_order_minor bigint NOT NULL DEFAULT 0,
    per_km_minor bigint NOT NULL DEFAULT 0,
    minimum_topup_minor bigint NOT NULL DEFAULT 0,
    total_minor bigint NOT NULL,
    currency char(3) NOT NULL,

    geo_unverified boolean NOT NULL DEFAULT false,
    protected_pickup_point text,
    protected_delivery_point text,
    points_purged_at timestamptz,

    settlement_period_id uuid NOT NULL,
    computed_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_earning_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id),
    CONSTRAINT fk_earning_shift FOREIGN KEY (shift_id, tenant_id)
        REFERENCES fulfillment.courier_shifts (id, tenant_id),
    CONSTRAINT fk_earning_rate_card FOREIGN KEY (rate_card_id, tenant_id)
        REFERENCES fulfillment.courier_rate_cards (id, tenant_id),
    CONSTRAINT fk_earning_courier_type FOREIGN KEY (courier_type_id, tenant_id)
        REFERENCES fulfillment.courier_types (id, tenant_id),
    CONSTRAINT fk_earning_period FOREIGN KEY (settlement_period_id, tenant_id)
        REFERENCES fulfillment.courier_settlement_periods (id, tenant_id),
    -- A delivered order accrues exactly once, whatever the delivery event does.
    -- Duplicate delivery events are ordinary, and the second one must not pay.
    CONSTRAINT uq_earning_attempt UNIQUE (tenant_id, assignment_attempt_id),
    CONSTRAINT ck_earning_distance CHECK (distance_meters >= 0),
    CONSTRAINT ck_earning_distance_source CHECK (distance_source IN (
        'ROUTING', 'HAVERSINE_FACTORED', 'MANUAL')),
    CONSTRAINT ck_earning_on_time CHECK (on_time_outcome IN (
        'ON_TIME', 'LATE', 'LATE_EXCUSED', 'UNKNOWN')),
    -- UNKNOWN is exactly the case where no promise was recorded, and any other
    -- outcome without one would be a guess presented as a fact.
    CONSTRAINT ck_earning_unknown_has_no_promise CHECK (
        (on_time_outcome = 'UNKNOWN') = (promised_delivery_end IS NULL)),
    CONSTRAINT ck_earning_components CHECK (
        fixed_minor >= 0 AND per_order_minor >= 0 AND per_km_minor >= 0
        AND minimum_topup_minor >= 0
        AND total_minor = fixed_minor + per_order_minor + per_km_minor + minimum_topup_minor),
    CONSTRAINT ck_earning_currency CHECK (currency ~ '^[A-Z]{3}$'),
    -- Both coordinates go together or neither does, and a purge instant means
    -- both are gone. A row with one point left is a retention rule that ran
    -- halfway.
    CONSTRAINT ck_earning_points_pair CHECK (
        (protected_pickup_point IS NULL) = (protected_delivery_point IS NULL)),
    CONSTRAINT ck_earning_purged CHECK (
        points_purged_at IS NULL OR protected_pickup_point IS NULL)
);

COMMENT ON COLUMN fulfillment.courier_assignment_earnings.distance_meters IS
    'The routing distance quoted at assignment, not the GPS track length. The track pays for detours, for circling the block and for drift in courtyards, and neither party can see the figure before the trip. ADR 0045 owns the track; it is evidence and may trigger review, and it never pays.';
COMMENT ON COLUMN fulfillment.courier_assignment_earnings.on_time_outcome IS
    'Computed once, at delivery, from values snapshotted at acceptance. No report recomputes it. LATE_EXCUSED is a late delivery whose kitchen handover fell after the plan''s pickup window closed -- penalising a courier for a late kitchen is how a tenant loses its couriers.';
COMMENT ON COLUMN fulfillment.courier_assignment_earnings.legal_entity_id IS
    'ADR 0038 resolves this from the location on the business date. Nullable until ADR 0038 ships its registry: a courier working two branches of two entities is owed by both and the expense is booked twice even though the transfer is one, and the statement''s per-entity subtotal is where that shows.';
COMMENT ON COLUMN fulfillment.courier_assignment_earnings.protected_delivery_point IS
    'ADR 0029 PERSONAL_SENSITIVE. Deleted 30 days after the containing settlement period reaches SETTLED. What survives is what the accrual was computed from -- the outcome, the flag, the distance and its source -- none of which is personal data.';
COMMENT ON COLUMN fulfillment.courier_assignment_earnings.total_minor IS
    'Integer minor units, whole som for UZS. Never derived from the customer delivery charge: a free-delivery promotion pays the courier for the eleven kilometres he drove, and the gap is margin.';

CREATE INDEX ix_earning_period ON fulfillment.courier_assignment_earnings
    (tenant_id, settlement_period_id);
CREATE INDEX ix_earning_shipment ON fulfillment.courier_assignment_earnings
    (tenant_id, shipment_id);
-- The retention sweeper's index: rows that still hold a coordinate.
CREATE INDEX ix_earning_unpurged ON fulfillment.courier_assignment_earnings
    (tenant_id, settlement_period_id)
    WHERE protected_delivery_point IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 6. The ledger
-- ---------------------------------------------------------------------------
--
-- One append-only ledger per courier, and the balance on it is what the tenant
-- owes that courier. One balance, not a wage balance and a cash balance: a
-- courier holding 900 000 som of the tenant's cash while being owed 400 000 som
-- is one net position, and a design reporting two is wrong before it is
-- inconvenient. There is no COMMISSION entry type and no prepaid float, so no
-- arrangement exists in which a courier owes the tenant money for order flow.

CREATE TABLE fulfillment.courier_ledger_entries (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    courier_id uuid NOT NULL,
    settlement_period_id uuid NOT NULL,
    legal_entity_id uuid,
    entry_type varchar(32) NOT NULL,
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    source_type varchar(48) NOT NULL,
    source_id uuid,
    origin varchar(16) NOT NULL,
    reason_code varchar(48),
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    idempotency_key varchar(160) NOT NULL,
    approval_request_id uuid,
    adjusts_entry_id uuid,
    created_by varchar(128) NOT NULL,

    CONSTRAINT fk_ledger_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id),
    CONSTRAINT fk_ledger_period FOREIGN KEY (settlement_period_id, tenant_id)
        REFERENCES fulfillment.courier_settlement_periods (id, tenant_id),
    CONSTRAINT fk_ledger_adjusts FOREIGN KEY (adjusts_entry_id)
        REFERENCES fulfillment.courier_ledger_entries (id),
    CONSTRAINT uq_ledger_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_ledger_entry_type CHECK (entry_type IN (
        'DELIVERY_EARNING', 'SHIFT_EARNING', 'BONUS', 'PENALTY',
        'CASH_COLLECTED', 'CASH_HANDED_OVER', 'CASH_VARIANCE', 'PAYOUT',
        'PRIOR_PERIOD_ADJUSTMENT', 'CORRECTION')),
    CONSTRAINT ck_ledger_origin CHECK (origin IN ('RULE', 'MANUAL', 'SYSTEM')),
    CONSTRAINT ck_ledger_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_ledger_amount_nonzero CHECK (amount_minor <> 0),
    -- Signs are fixed per type. An earning that arrives negative, or a payout
    -- that arrives positive, is a caller with the sign backwards, and the
    -- symptom without this constraint is a balance nobody can explain.
    CONSTRAINT ck_ledger_sign CHECK (
        CASE entry_type
            WHEN 'DELIVERY_EARNING' THEN amount_minor > 0
            WHEN 'SHIFT_EARNING' THEN amount_minor > 0
            WHEN 'BONUS' THEN amount_minor > 0
            WHEN 'CASH_HANDED_OVER' THEN amount_minor > 0
            WHEN 'PENALTY' THEN amount_minor < 0
            WHEN 'CASH_COLLECTED' THEN amount_minor < 0
            WHEN 'PAYOUT' THEN amount_minor < 0
            ELSE true
        END),
    -- A manual adjustment names a person and a reason from the managed registry.
    -- A manager who can silently debit a courier's pay is a labour dispute and a
    -- fraud vector in one instrument.
    CONSTRAINT ck_ledger_manual_has_reason CHECK (
        origin <> 'MANUAL' OR reason_code IS NOT NULL),
    CONSTRAINT ck_ledger_manual_penalty_approved CHECK (
        NOT (entry_type = 'PENALTY' AND origin = 'MANUAL') OR approval_request_id IS NOT NULL),
    CONSTRAINT ck_ledger_prior_period_reference CHECK (
        (entry_type = 'PRIOR_PERIOD_ADJUSTMENT') = (adjusts_entry_id IS NOT NULL))
);

COMMENT ON TABLE fulfillment.courier_ledger_entries IS
    'ADR 0042 and ADR 0027. Append-only: the application role holds INSERT and SELECT and nothing else, so a history that can be edited is not one. A correction is a new entry, and so is a prior-period adjustment.';
COMMENT ON COLUMN fulfillment.courier_ledger_entries.amount_minor IS
    'Signed integer minor units, whole som for UZS. Positive is owed to the courier -- earnings, bonuses, confirmed cash handovers. Negative is owed by them -- cash collected, penalties, payouts made.';
COMMENT ON COLUMN fulfillment.courier_ledger_entries.settlement_period_id IS
    'Stamped when the entry is written, never derived by a date query at close time. An entry arriving after close lands in the next open period as a PRIOR_PERIOD_ADJUSTMENT that keeps its original occurred_at.';
COMMENT ON COLUMN fulfillment.courier_ledger_entries.idempotency_key IS
    'What makes a replayed delivery event, a retried cash confirmation and a re-sent payout write once. Unique per tenant, because two tenants may legitimately derive the same key from their own identifiers.';
COMMENT ON COLUMN fulfillment.courier_ledger_entries.occurred_at IS
    'When the fact happened, which a prior-period adjustment retains from the entry it corrects. recorded_at is when this row was written, and the two differ exactly when something arrived late.';

CREATE INDEX ix_ledger_period ON fulfillment.courier_ledger_entries
    (tenant_id, settlement_period_id, entry_type);
CREATE INDEX ix_ledger_courier ON fulfillment.courier_ledger_entries
    (tenant_id, courier_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- 7. Cash at close
-- ---------------------------------------------------------------------------

CREATE TABLE fulfillment.courier_cash_handovers (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    shift_id uuid NOT NULL,
    courier_id uuid NOT NULL,
    location_id uuid NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    currency char(3) NOT NULL,
    expected_minor bigint NOT NULL,
    declared_minor bigint,
    confirmed_minor bigint,
    variance_minor bigint,
    declared_at timestamptz,
    confirmed_by varchar(128),
    confirmed_at timestamptz,
    reason_code varchar(48),
    override_reason varchar(200),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_handover_shift FOREIGN KEY (shift_id, tenant_id)
        REFERENCES fulfillment.courier_shifts (id, tenant_id),
    CONSTRAINT fk_handover_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id),
    CONSTRAINT uq_handover_shift UNIQUE (shift_id),
    CONSTRAINT uq_handover_identity UNIQUE (id, tenant_id),
    CONSTRAINT ck_handover_status CHECK (status IN (
        'PENDING', 'DECLARED', 'CONFIRMED', 'VARIANCE_RAISED', 'OVERRIDDEN')),
    CONSTRAINT ck_handover_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_handover_amounts CHECK (
        expected_minor >= 0
        AND (declared_minor IS NULL OR declared_minor >= 0)
        AND (confirmed_minor IS NULL OR confirmed_minor >= 0)),
    CONSTRAINT ck_handover_declared_pair CHECK (
        (declared_minor IS NULL) = (declared_at IS NULL)),
    CONSTRAINT ck_handover_confirmed_pair CHECK (
        (confirmed_minor IS NULL) = (confirmed_at IS NULL)
        AND (confirmed_minor IS NULL) = (confirmed_by IS NULL)),
    -- A cashier cannot confirm what nobody declared: the two figures are
    -- different people's statements about the same bag, and confirming first
    -- collapses them into one.
    CONSTRAINT ck_handover_confirm_after_declare CHECK (
        confirmed_at IS NULL OR declared_at IS NOT NULL),
    CONSTRAINT ck_handover_override_reason CHECK (
        (status = 'OVERRIDDEN') = (override_reason IS NOT NULL))
);

COMMENT ON COLUMN fulfillment.courier_cash_handovers.expected_minor IS
    'What the courier was told to collect, summed from the CASH_COLLECTED entries of the shift: order total less anything already captured and less any loyalty amount. The app displays the same figure, for exactly this reason.';
COMMENT ON COLUMN fulfillment.courier_cash_handovers.variance_minor IS
    'Declared minus expected, plus confirmed minus declared. Recorded as its own CASH_VARIANCE ledger entry with a reason code and never absorbed into another figure.';

-- ---------------------------------------------------------------------------
-- 8. Adjustment reasons and payouts
-- ---------------------------------------------------------------------------

CREATE TABLE fulfillment.courier_adjustment_reasons (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    code varchar(48) NOT NULL,
    kind varchar(16) NOT NULL,
    outcome_basis varchar(32) NOT NULL,
    display_name varchar(160) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_adjustment_reason_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT uq_adjustment_reason_code UNIQUE (tenant_id, code),
    CONSTRAINT ck_adjustment_reason_kind CHECK (kind IN ('BONUS', 'PENALTY')),
    CONSTRAINT ck_adjustment_reason_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    -- Every code names a delivery outcome, never a behaviour. Routinely
    -- sanctioning how a self-employed person conducts themselves is the fact
    -- pattern that reclassifies the engagement, and a free-text reason field is
    -- how that arrives one code at a time. So the basis is closed, and the
    -- registry is narrow on purpose.
    CONSTRAINT ck_adjustment_reason_basis CHECK (outcome_basis IN (
        'ON_TIME_RATE', 'LATE_DELIVERY', 'CASH_VARIANCE', 'GEO_UNVERIFIED_RATE',
        'DELIVERED_VOLUME', 'ORDER_UNDELIVERED', 'ORDER_DAMAGED'))
);

CREATE TABLE fulfillment.courier_payouts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    courier_id uuid NOT NULL,
    settlement_period_id uuid NOT NULL,
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    method varchar(24) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'AUTHORISED',
    external_reference varchar(128),
    authorised_by varchar(128) NOT NULL,
    authorised_at timestamptz NOT NULL DEFAULT now(),
    approval_request_id uuid,
    paid_by varchar(128),
    paid_at timestamptz,

    CONSTRAINT fk_payout_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id),
    CONSTRAINT fk_payout_period FOREIGN KEY (settlement_period_id, tenant_id)
        REFERENCES fulfillment.courier_settlement_periods (id, tenant_id),
    CONSTRAINT uq_payout_period UNIQUE (settlement_period_id),
    CONSTRAINT ck_payout_method CHECK (method IN (
        'CASH_AT_BRANCH', 'BANK_TRANSFER', 'CARD_TRANSFER')),
    CONSTRAINT ck_payout_status CHECK (status IN ('AUTHORISED', 'PAID', 'CANCELLED')),
    CONSTRAINT ck_payout_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payout_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_payout_paid_pair CHECK ((paid_at IS NULL) = (paid_by IS NULL)),
    CONSTRAINT ck_payout_paid_status CHECK ((status = 'PAID') = (paid_at IS NOT NULL))
);

COMMENT ON TABLE fulfillment.courier_payouts IS
    'ADR 0042. HorecaOS computes, approves and records the payout; it does not move the money. Disbursement is a later ADR 0013 capability and this row is its seam. A large share of courier pay here settles by the courier keeping cash he already collected, and CASH_AT_BRANCH is that arrangement written down rather than left off the books.';

-- ---------------------------------------------------------------------------
-- 9. Two cost paths
-- ---------------------------------------------------------------------------
--
-- A shipment has one or more cost lines, never one column. An order booked with
-- Noor, cancelled when Noor's courier did not arrive, and delivered by Alisher
-- carries a partner cancellation charge and an internal earning, and both are
-- real. A single cost field silently discards one of them, and the discarded one
-- is always the surprising one.

CREATE TABLE fulfillment.delivery_cost_lines (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    shipment_id uuid NOT NULL,
    legal_entity_id uuid,
    business_date date NOT NULL,
    cost_path varchar(16) NOT NULL,
    cost_basis varchar(16) NOT NULL,
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    source_type varchar(48) NOT NULL,
    source_id uuid,
    courier_id uuid,
    provider_code varchar(32),
    recognised_at timestamptz NOT NULL DEFAULT now(),
    -- ADR 0042 draws this as `superseded_by` on the row being replaced. It is
    -- inverted here, and the reason is the word append-only: stamping a pointer
    -- onto an existing row needs UPDATE, and a table the application may UPDATE
    -- is not append-only whatever the column is called. The successor names its
    -- predecessor instead, the grant below is INSERT and SELECT, and "the live
    -- lines" is the set nothing supersedes.
    supersedes_line_id uuid,
    recorded_by varchar(128) NOT NULL,

    CONSTRAINT fk_cost_line_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT fk_cost_line_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id),
    CONSTRAINT fk_cost_line_supersedes FOREIGN KEY (supersedes_line_id)
        REFERENCES fulfillment.delivery_cost_lines (id),
    CONSTRAINT uq_cost_line_supersedes UNIQUE (supersedes_line_id),
    CONSTRAINT ck_cost_line_path CHECK (cost_path IN ('INTERNAL', 'PARTNER')),
    CONSTRAINT ck_cost_line_basis CHECK (cost_basis IN ('ACCRUED', 'INVOICED', 'SETTLED')),
    CONSTRAINT ck_cost_line_currency CHECK (currency ~ '^[A-Z]{3}$'),
    -- An internal line names its courier and a partner line names its provider,
    -- stated as equalities so the mixed rows -- a partner line with a courier, an
    -- internal line with neither -- are unreachable rather than merely unlikely.
    CONSTRAINT ck_cost_line_internal_courier CHECK (
        (cost_path = 'INTERNAL') = (courier_id IS NOT NULL)),
    CONSTRAINT ck_cost_line_partner_provider CHECK (
        (cost_path = 'PARTNER') = (provider_code IS NOT NULL)),
    -- INVOICED is meaningless on the internal path: a self-employed courier's
    -- accrual becomes SETTLED when the period closes, and there is no invoice
    -- from HorecaOS to itself in between.
    CONSTRAINT ck_cost_line_internal_basis CHECK (
        cost_path <> 'INTERNAL' OR cost_basis IN ('ACCRUED', 'SETTLED'))
);

COMMENT ON TABLE fulfillment.delivery_cost_lines IS
    'ADR 0042. Append-only. A delivery-cost total is taken over a single stated basis, never across bases: the internal figure exists at delivery and the partner figure exists when the invoice arrives, days or weeks later, so a same-day report summing both under-states partner cost and then jumps when invoices land, with no reader able to tell whether the jump is a cost increase or an arrival.';
COMMENT ON COLUMN fulfillment.delivery_cost_lines.cost_basis IS
    'Not a status: a claim about the number. ACCRUED internal is computed at delivery from the snapshotted rate card; ACCRUED partner is the booked price on the winning assignment attempt and is an estimate. INVOICED is a matched partner invoice line. SETTLED is a closed statement or a paid invoice.';
COMMENT ON COLUMN fulfillment.delivery_cost_lines.amount_minor IS
    'Integer minor units, whole som for UZS. Shift-fixed cost and period bonuses are deliberately absent: they do not decompose to an order, and a fabricated allocation key is a number that looks reproducible and is not.';

CREATE INDEX ix_cost_line_shipment ON fulfillment.delivery_cost_lines
    (tenant_id, shipment_id);
CREATE INDEX ix_cost_line_basis ON fulfillment.delivery_cost_lines
    (tenant_id, business_date, cost_basis, cost_path);

-- ---------------------------------------------------------------------------
-- 10. Partner invoices
-- ---------------------------------------------------------------------------

CREATE TABLE fulfillment.partner_delivery_invoices (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    provider_code varchar(32) NOT NULL,
    provider_invoice_ref varchar(128) NOT NULL,
    legal_entity_id uuid,
    period_start date NOT NULL,
    period_end date NOT NULL,
    total_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'IMPORTED',
    imported_by varchar(128) NOT NULL,
    imported_at timestamptz NOT NULL DEFAULT now(),
    matched_at timestamptz,
    version integer NOT NULL DEFAULT 1,

    CONSTRAINT fk_partner_invoice_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT uq_partner_invoice_ref UNIQUE (tenant_id, provider_code, provider_invoice_ref),
    CONSTRAINT uq_partner_invoice_identity UNIQUE (id, tenant_id),
    CONSTRAINT ck_partner_invoice_status CHECK (status IN (
        'IMPORTED', 'MATCHED', 'DISPUTED', 'PAID')),
    CONSTRAINT ck_partner_invoice_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_partner_invoice_period CHECK (period_end >= period_start)
);

CREATE TABLE fulfillment.partner_delivery_invoice_lines (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    invoice_id uuid NOT NULL,
    provider_shipment_ref varchar(128) NOT NULL,
    shipment_id uuid,
    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    charge_type varchar(24) NOT NULL,
    match_status varchar(24) NOT NULL DEFAULT 'PENDING',
    variance_minor bigint,
    reason_code varchar(48),
    matched_at timestamptz,

    CONSTRAINT fk_partner_line_invoice FOREIGN KEY (invoice_id, tenant_id)
        REFERENCES fulfillment.partner_delivery_invoices (id, tenant_id),
    CONSTRAINT uq_partner_line_ref UNIQUE (invoice_id, provider_shipment_ref, charge_type),
    CONSTRAINT ck_partner_line_charge CHECK (charge_type IN (
        'DELIVERY', 'CANCELLATION', 'WAITING', 'SURCHARGE', 'ADJUSTMENT')),
    CONSTRAINT ck_partner_line_match CHECK (match_status IN (
        'PENDING', 'MATCHED', 'VARIANCE', 'UNBILLED', 'UNMATCHED_LINE')),
    CONSTRAINT ck_partner_line_currency CHECK (currency ~ '^[A-Z]{3}$'),
    -- UNMATCHED_LINE is exactly the case where HorecaOS has no shipment for what
    -- the partner billed, and every other settled status has one. Stated as two
    -- implications rather than one equality because PENDING is a third case --
    -- imported and not yet run through matching -- and folding it into either
    -- side would make an unmatched import indistinguishable from a settled
    -- finding.
    CONSTRAINT ck_partner_line_unmatched CHECK (
        match_status <> 'UNMATCHED_LINE' OR shipment_id IS NULL),
    CONSTRAINT ck_partner_line_matched_has_shipment CHECK (
        match_status NOT IN ('MATCHED', 'VARIANCE', 'UNBILLED') OR shipment_id IS NOT NULL),
    CONSTRAINT ck_partner_line_variance CHECK (
        (match_status = 'VARIANCE') = (variance_minor IS NOT NULL))
);

COMMENT ON COLUMN fulfillment.partner_delivery_invoice_lines.match_status IS
    'UNMATCHED_LINE is the direction reconciliation reports usually omit -- the partner billed for something HorecaOS has no shipment for -- and the only one that can hide a charge for a delivery that never happened. It is never netted into a total.';
COMMENT ON COLUMN fulfillment.partner_delivery_invoice_lines.variance_minor IS
    'Invoiced less accrued. Above the ADR 0030 threshold it raises an operations task and blocks nothing: disputing a partner invoice is a human activity, and the platform records evidence for it rather than pretending to automate it.';

CREATE INDEX ix_partner_line_shipment ON fulfillment.partner_delivery_invoice_lines
    (tenant_id, shipment_id) WHERE shipment_id IS NOT NULL;
CREATE INDEX ix_partner_line_status ON fulfillment.partner_delivery_invoice_lines
    (tenant_id, match_status);

-- ---------------------------------------------------------------------------
-- 11. Grants
-- ---------------------------------------------------------------------------
--
-- The ledger, the statement, and the cost lines hold no UPDATE and no DELETE for
-- the application role. Between them they are the evidence a disputed payout is
-- answered from, and evidence that the application can rewrite is not evidence.
-- ADR 0027's grant test asserts exactly this by attempting an UPDATE as the
-- application role and requiring it to fail.

GRANT USAGE ON SCHEMA fulfillment TO horecaos_application;

GRANT SELECT, INSERT, UPDATE ON fulfillment.courier_types TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON fulfillment.couriers TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON fulfillment.courier_engagements TO horecaos_application;
GRANT SELECT, INSERT ON fulfillment.courier_registration_notices TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.courier_rate_cards TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.courier_rate_components TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON fulfillment.courier_settlement_periods TO horecaos_application;
GRANT SELECT, INSERT ON fulfillment.courier_settlement_statements TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON fulfillment.courier_shifts TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON fulfillment.courier_shift_breaks TO horecaos_application;
-- UPDATE on earnings is granted for one column and one reason: the ADR 0029
-- retention sweeper nulls the two confirmation coordinates thirty days after the
-- period settles. Everything the accrual was computed from is untouched by it.
GRANT SELECT, INSERT, UPDATE ON fulfillment.courier_assignment_earnings TO horecaos_application;
GRANT SELECT, INSERT ON fulfillment.courier_ledger_entries TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON fulfillment.courier_cash_handovers TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON fulfillment.courier_adjustment_reasons TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON fulfillment.courier_payouts TO horecaos_application;
GRANT SELECT, INSERT ON fulfillment.delivery_cost_lines TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON fulfillment.partner_delivery_invoices TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON fulfillment.partner_delivery_invoice_lines TO horecaos_application;
