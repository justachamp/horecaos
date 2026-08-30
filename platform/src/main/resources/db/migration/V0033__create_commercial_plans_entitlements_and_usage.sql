-- ADR 0021: plans, subscriptions, entitlements, and usage metering.
--
-- This migration records what a tenant is entitled to and what it has actually
-- consumed. It records nothing about money moving. ADR 0013 owns money movement
-- — intents, attempts, transactions, refunds — and an invoice, a dunning cycle,
-- a proration and a tax treatment are all deliberately absent here. What the
-- tables below can answer is "how much is owed and on what evidence"; what pays
-- it is somebody else's aggregate.
--
-- ---------------------------------------------------------------------------
-- The three decisions this schema exists to encode
-- ---------------------------------------------------------------------------
--
-- 1. An entitlement is not a feature flag. A flag is a boolean somebody toggles.
--    An entitlement carries a limit, a period the limit applies over, a consumed
--    quantity measured against it, and — the part a flag can never express — an
--    answer at the boundary. `commercial.plan_entitlements` therefore stores the
--    enforcement mode, the reset period, the warning threshold and the overage
--    unit price beside the value, because "what happens at the 101st order on a
--    100-order plan" is the only question this table exists to answer, and a
--    limit stored without an answer to it is a number nobody can act on.
--
-- 2. Metering is append-only. `commercial.usage_events` holds movements, never
--    balances, and a trigger plus a deliberately narrow GRANT refuse UPDATE and
--    DELETE on it. A counter updated in place cannot be recomputed after a
--    consumer bug, cannot be replayed, and cannot be defended to a customer
--    disputing an invoice — and it is precisely the disputed invoice that this
--    data will one day be read for. Corrections are new facts in
--    `commercial.usage_adjustments`. `commercial.usage_aggregates` is a cache of
--    the sum and is truncatable at any time without losing anything.
--
-- 3. Enforcement is separate from measurement. The mode on a plan entitlement
--    says what the commercial contract intends; whether the platform acts on it
--    is an ADR 0030 configuration value (`commercial.enforcement_ceiling`) whose
--    platform default is METER_ONLY. Nothing here refuses anything until that
--    value is deliberately raised for a named tenant.
--
-- ---------------------------------------------------------------------------
-- Why a `commercial` schema rather than more tables in `tenant`
-- ---------------------------------------------------------------------------
--
-- V0001 gave the `tenant` schema a comment mentioning entitlement data, and the
-- tenancy module could have carried these tables. It should not. The tenant
-- lifecycle and the subscription lifecycle are separate on purpose — ADR 0021 is
-- explicit that a billing problem must never delete a tenant — and putting the
-- two aggregates in one schema is the first step towards a join that couples
-- them. A tenant that stops paying keeps operating until somebody decides
-- otherwise, and that decision belongs to a row in a different schema.

CREATE SCHEMA IF NOT EXISTS commercial;

COMMENT ON SCHEMA commercial IS
    'ADR 0021 plans, subscriptions, entitlements, and the append-only usage ledger. What is owed, never how it is paid.';

-- --------------------------------------------------------------------- plans

CREATE TABLE commercial.plans (
    id uuid PRIMARY KEY,
    code varchar(64) NOT NULL,
    name varchar(200) NOT NULL,
    status varchar(16) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_plans_code UNIQUE (code),
    CONSTRAINT ck_plans_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_plans_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_plans_version CHECK (version >= 0)
);

COMMENT ON TABLE commercial.plans IS
    'ADR 0021 plan identity. The sellable name; everything that can change about a plan lives on its versions.';

COMMENT ON COLUMN commercial.plans.status IS
    'RETIRED withdraws the plan from sale. It does not end anybody''s subscription: existing tenants keep the version they are on.';

CREATE TABLE commercial.plan_versions (
    id uuid PRIMARY KEY,
    plan_id uuid NOT NULL,
    version_number integer NOT NULL,
    currency char(3) NOT NULL,
    price_minor bigint NOT NULL,
    billing_period varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    terms_reference varchar(500),
    effective_from timestamptz,
    effective_until timestamptz,
    created_by varchar(255) NOT NULL,
    approved_by varchar(255),
    activated_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_plan_version_plan FOREIGN KEY (plan_id)
        REFERENCES commercial.plans (id),
    CONSTRAINT uq_plan_version_number UNIQUE (plan_id, version_number),
    CONSTRAINT ck_plan_version_number CHECK (version_number > 0),
    CONSTRAINT ck_plan_version_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_plan_version_price CHECK (price_minor >= 0),
    CONSTRAINT ck_plan_version_billing_period CHECK (
        billing_period IN ('NONE', 'MONTHLY', 'QUARTERLY', 'YEARLY')
    ),
    CONSTRAINT ck_plan_version_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_plan_version_effective_window CHECK (
        effective_until IS NULL OR effective_from IS NULL OR effective_until > effective_from
    ),
    -- Pair completeness stated as an equality of null-ness rather than as
    -- (both null) OR (both set): the second form is three-valued and lets one
    -- half through when the other is unknown.
    CONSTRAINT ck_plan_version_activation_pair CHECK (
        (activated_at IS NULL) = (approved_by IS NULL)
    ),
    -- ADR 0027's four eyes, at the one place in this module where it is worth
    -- the friction. Activating a version publishes a price and a set of limits
    -- to every tenant that later subscribes to it, and the person who typed a
    -- figure is the last person able to notice a misplaced digit in it.
    CONSTRAINT ck_plan_version_four_eyes CHECK (
        approved_by IS NULL OR approved_by <> created_by
    )
);

CREATE INDEX ix_plan_version_plan ON commercial.plan_versions (plan_id, version_number DESC);

COMMENT ON TABLE commercial.plan_versions IS
    'ADR 0021 priced, immutable plan terms. Changing a price or a limit creates a new version and a deliberate subscription transition.';

COMMENT ON COLUMN commercial.plan_versions.price_minor IS
    'Integer minor units of `currency`. For UZS a minor unit is one whole som, so a formatter that divides by 100 shows a customer a price a hundred times too small.';

COMMENT ON COLUMN commercial.plan_versions.billing_period IS
    'The period the price covers, and the period a BILLING_PERIOD entitlement resets on. NONE is a free or internal plan.';

COMMENT ON COLUMN commercial.plan_versions.terms_reference IS
    'A pointer to the signed commercial terms this version corresponds to. Evidence, not a URL to fetch at runtime.';

CREATE TABLE commercial.plan_entitlements (
    plan_version_id uuid NOT NULL,
    entitlement_key varchar(128) NOT NULL,
    value_type varchar(16) NOT NULL,
    boolean_value boolean,
    integer_value bigint,
    decimal_value numeric(38, 10),
    string_value text,
    enforcement_mode varchar(16) NOT NULL,
    reset_period varchar(20) NOT NULL,
    warn_threshold_bps integer,
    overage_unit_price_minor bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_plan_entitlement PRIMARY KEY (plan_version_id, entitlement_key),
    CONSTRAINT fk_plan_entitlement_version FOREIGN KEY (plan_version_id)
        REFERENCES commercial.plan_versions (id) ON DELETE CASCADE,
    CONSTRAINT ck_plan_entitlement_key CHECK (
        entitlement_key ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'
    ),
    CONSTRAINT ck_plan_entitlement_value_type CHECK (
        value_type IN ('BOOLEAN', 'INTEGER', 'DECIMAL', 'STRING')
    ),
    -- Exactly one typed column carries the value, and it is the one the type
    -- names. A row whose value_type disagrees with its populated column resolves
    -- to a null limit, and a null limit reads as "unlimited".
    CONSTRAINT ck_plan_entitlement_payload CHECK (
        (value_type = 'BOOLEAN' AND boolean_value IS NOT NULL
            AND integer_value IS NULL AND decimal_value IS NULL AND string_value IS NULL)
        OR (value_type = 'INTEGER' AND integer_value IS NOT NULL
            AND boolean_value IS NULL AND decimal_value IS NULL AND string_value IS NULL)
        OR (value_type = 'DECIMAL' AND decimal_value IS NOT NULL
            AND boolean_value IS NULL AND integer_value IS NULL AND string_value IS NULL)
        OR (value_type = 'STRING' AND string_value IS NOT NULL
            AND boolean_value IS NULL AND integer_value IS NULL AND decimal_value IS NULL)
    ),
    CONSTRAINT ck_plan_entitlement_mode CHECK (
        enforcement_mode IN ('METER_ONLY', 'SOFT', 'HARD', 'DISABLED')
    ),
    CONSTRAINT ck_plan_entitlement_reset_period CHECK (
        reset_period IN ('NONE', 'DAILY', 'MONTHLY', 'BILLING_PERIOD')
    ),
    CONSTRAINT ck_plan_entitlement_warn_threshold CHECK (
        warn_threshold_bps IS NULL OR (warn_threshold_bps > 0 AND warn_threshold_bps <= 10000)
    ),
    -- A warning threshold and an overage rate are arithmetic against a counted
    -- limit. Neither has a meaning on a boolean feature, and allowing them there
    -- would produce a plan row that reads as billable and can never be billed.
    CONSTRAINT ck_plan_entitlement_numeric_only CHECK (
        value_type = 'INTEGER'
        OR (warn_threshold_bps IS NULL AND overage_unit_price_minor IS NULL)
    ),
    CONSTRAINT ck_plan_entitlement_overage_price CHECK (
        overage_unit_price_minor IS NULL OR overage_unit_price_minor >= 0
    ),
    -- Overage is what "allow and bill" means, and HARD refuses rather than
    -- bills. A price sitting on a HARD row is a rate nobody will ever charge and
    -- an operator will read it as one they will.
    CONSTRAINT ck_plan_entitlement_overage_mode CHECK (
        overage_unit_price_minor IS NULL OR enforcement_mode IN ('METER_ONLY', 'SOFT')
    )
);

COMMENT ON TABLE commercial.plan_entitlements IS
    'ADR 0021 typed entitlement values per plan version, each with its own boundary behaviour. Immutable once the version is activated.';

COMMENT ON COLUMN commercial.plan_entitlements.enforcement_mode IS
    'What happens at the boundary. METER_ONLY measures and never changes behaviour; SOFT allows and records overage; HARD refuses the capacity-increasing action; DISABLED denies feature activation while preserving existing data.';

COMMENT ON COLUMN commercial.plan_entitlements.reset_period IS
    'NONE is a standing limit measured over the tenant''s lifetime, such as a location count. The rest are allowances that start again each period.';

COMMENT ON COLUMN commercial.plan_entitlements.warn_threshold_bps IS
    'Basis points of the limit at which a check starts answering "approaching" rather than "within". 8000 warns at 80 per cent.';

COMMENT ON COLUMN commercial.plan_entitlements.overage_unit_price_minor IS
    'Minor units charged per unit consumed beyond the limit, in the plan version currency. Without it a SOFT limit is measured and cannot be invoiced, which is the whole difference between "allow and warn" and "allow and bill".';

-- Plan versions are immutable after activation, enforced where the application
-- cannot route around it.
--
-- The alternative was to trust every write path to check the status first. That
-- holds until a support script edits a price to "fix" an invoice, and then every
-- historical order priced under that version is describing terms that no longer
-- exist anywhere. The activation columns themselves are excluded, because
-- activation is the transition into immutability rather than a change under it.
CREATE OR REPLACE FUNCTION commercial.reject_activated_plan_version_change() RETURNS trigger AS $$
BEGIN
    IF OLD.activated_at IS NOT NULL
        AND (NEW.price_minor IS DISTINCT FROM OLD.price_minor
             OR NEW.currency IS DISTINCT FROM OLD.currency
             OR NEW.billing_period IS DISTINCT FROM OLD.billing_period
             OR NEW.version_number IS DISTINCT FROM OLD.version_number
             OR NEW.plan_id IS DISTINCT FROM OLD.plan_id
             OR NEW.terms_reference IS DISTINCT FROM OLD.terms_reference
             OR NEW.created_by IS DISTINCT FROM OLD.created_by
             OR NEW.approved_by IS DISTINCT FROM OLD.approved_by
             OR NEW.activated_at IS DISTINCT FROM OLD.activated_at) THEN
        RAISE EXCEPTION
            'Activated plan terms are immutable; issue a new version instead (ADR 0021)';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_plan_versions_immutable_after_activation
    BEFORE UPDATE ON commercial.plan_versions
    FOR EACH ROW EXECUTE FUNCTION commercial.reject_activated_plan_version_change();

CREATE OR REPLACE FUNCTION commercial.reject_activated_plan_entitlement_change() RETURNS trigger AS $$
DECLARE
    v_activated timestamptz;
BEGIN
    SELECT activated_at INTO v_activated
      FROM commercial.plan_versions
     WHERE id = COALESCE(OLD.plan_version_id, NEW.plan_version_id);

    IF v_activated IS NOT NULL THEN
        RAISE EXCEPTION
            'The entitlements of an activated plan version are immutable; issue a new version instead (ADR 0021)';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

-- The version row itself is deleted only by a cascade from a draft plan, and a
-- cascade fires this trigger for each entitlement row. Draft versions have a
-- null activated_at, so the cascade passes and an activated one is refused.
CREATE TRIGGER trg_plan_entitlements_immutable_after_activation
    BEFORE UPDATE OR DELETE ON commercial.plan_entitlements
    FOR EACH ROW EXECUTE FUNCTION commercial.reject_activated_plan_entitlement_change();

-- ------------------------------------------------------------- subscriptions

CREATE TABLE commercial.subscriptions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    plan_version_id uuid NOT NULL,
    status varchar(28) NOT NULL,
    start_at timestamptz NOT NULL,
    trial_end_at timestamptz,
    current_period_start timestamptz NOT NULL,
    current_period_end timestamptz NOT NULL,
    cancel_at timestamptz,
    suspended_at timestamptz,
    suspension_reason varchar(500),
    ended_at timestamptz,
    external_billing_reference varchar(200),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_subscription_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_subscription_plan_version FOREIGN KEY (plan_version_id)
        REFERENCES commercial.plan_versions (id),
    CONSTRAINT ck_subscription_status CHECK (
        status IN ('DRAFT', 'TRIALING', 'ACTIVE', 'PAST_DUE', 'SUSPENDED',
                   'CANCELLATION_SCHEDULED', 'EXPIRED', 'TERMINATED')
    ),
    CONSTRAINT ck_subscription_period CHECK (current_period_end > current_period_start),
    CONSTRAINT ck_subscription_suspension_pair CHECK (
        (suspended_at IS NULL) = (suspension_reason IS NULL)
    ),
    CONSTRAINT ck_subscription_version CHECK (version >= 0)
);

-- One live subscription per tenant. Terminal subscriptions stay in the table
-- because a churned tenant's history is exactly what a renewal conversation
-- needs, and a partial index is what lets both facts be true at once.
CREATE UNIQUE INDEX uq_subscription_live_per_tenant
    ON commercial.subscriptions (tenant_id)
    WHERE status NOT IN ('TERMINATED', 'EXPIRED');

CREATE INDEX ix_subscription_tenant ON commercial.subscriptions (tenant_id, created_at DESC);

COMMENT ON TABLE commercial.subscriptions IS
    'ADR 0021 what one tenant is on. Distinct from tenant.tenants.status: a commercial problem suspends a subscription and never archives a tenant.';

COMMENT ON COLUMN commercial.subscriptions.status IS
    'SUSPENDED maps entitlements to an approved degraded behaviour — read-only operations, no new ordering, exports still available — and never deletes data.';

COMMENT ON COLUMN commercial.subscriptions.external_billing_reference IS
    'The identifier this subscription carries in whatever system eventually issues invoices. Recorded so a future adapter can reconcile; never read on a request path.';

COMMENT ON COLUMN commercial.subscriptions.ended_at IS
    'When a terminal status was reached. Separate from cancel_at, which is a future intention that a tenant can still withdraw.';

-- ------------------------------------------------------- entitlement overrides

CREATE TABLE commercial.entitlement_overrides (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    entitlement_key varchar(128) NOT NULL,
    value_type varchar(16) NOT NULL,
    boolean_value boolean,
    integer_value bigint,
    decimal_value numeric(38, 10),
    string_value text,
    enforcement_mode varchar(16),
    reason varchar(1000) NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz NOT NULL,
    requested_by varchar(255) NOT NULL,
    approved_by varchar(255) NOT NULL,
    revoked_at timestamptz,
    revoked_by varchar(255),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_entitlement_override_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT ck_entitlement_override_key CHECK (
        entitlement_key ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'
    ),
    CONSTRAINT ck_entitlement_override_value_type CHECK (
        value_type IN ('BOOLEAN', 'INTEGER', 'DECIMAL', 'STRING')
    ),
    CONSTRAINT ck_entitlement_override_payload CHECK (
        (value_type = 'BOOLEAN' AND boolean_value IS NOT NULL
            AND integer_value IS NULL AND decimal_value IS NULL AND string_value IS NULL)
        OR (value_type = 'INTEGER' AND integer_value IS NOT NULL
            AND boolean_value IS NULL AND decimal_value IS NULL AND string_value IS NULL)
        OR (value_type = 'DECIMAL' AND decimal_value IS NOT NULL
            AND boolean_value IS NULL AND integer_value IS NULL AND string_value IS NULL)
        OR (value_type = 'STRING' AND string_value IS NOT NULL
            AND boolean_value IS NULL AND integer_value IS NULL AND decimal_value IS NULL)
    ),
    CONSTRAINT ck_entitlement_override_mode CHECK (
        enforcement_mode IS NULL
        OR enforcement_mode IN ('METER_ONLY', 'SOFT', 'HARD', 'DISABLED')
    ),
    CONSTRAINT ck_entitlement_override_window CHECK (valid_until > valid_from),
    CONSTRAINT ck_entitlement_override_revocation_pair CHECK (
        (revoked_at IS NULL) = (revoked_by IS NULL)
    ),
    CONSTRAINT ck_entitlement_override_four_eyes CHECK (approved_by <> requested_by),
    CONSTRAINT ck_entitlement_override_version CHECK (version >= 0)
);

-- One unrevoked override per tenant and key. Replacing one is a revoke and a
-- new row, so the record of what was granted, by whom, and until when survives.
--
-- An exclusion constraint over the validity range would allow a future-dated
-- override to be staged beside a running one, which is nicer and needs
-- btree_gist. This index needs nothing and refuses the same overlap; the day
-- staging is asked for, the extension and a range column are a small migration.
CREATE UNIQUE INDEX uq_entitlement_override_live
    ON commercial.entitlement_overrides (tenant_id, entitlement_key)
    WHERE revoked_at IS NULL;

COMMENT ON TABLE commercial.entitlement_overrides IS
    'ADR 0021 exceptional, time-bounded, four-eyes-approved departures from a tenant''s plan. Visible in support tooling beside the plan value they displace.';

COMMENT ON COLUMN commercial.entitlement_overrides.valid_until IS
    'Not nullable, deliberately. An open-ended override is a plan change made without changing the plan, and it outlives everyone who remembers why it was granted.';

COMMENT ON COLUMN commercial.entitlement_overrides.enforcement_mode IS
    'Optional. Null keeps the plan version''s mode and changes only the value; setting it is how a limit is relaxed for one tenant without relaxing what happens at its boundary.';

-- ------------------------------------------------------------ usage metering

CREATE TABLE commercial.usage_events (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    entitlement_key varchar(128) NOT NULL,
    quantity bigint NOT NULL,
    unit varchar(32) NOT NULL,
    period_key varchar(16) NOT NULL,
    source_type varchar(64) NOT NULL,
    source_event_id varchar(200) NOT NULL,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    dimensions jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT fk_usage_event_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    -- The idempotency key. ADR 0021 writes it as (tenant, key, source_event_id);
    -- source_type is added because a source event id is minted in another
    -- system's namespace and two systems can legitimately produce the same
    -- string. Without it, one collision silently drops a real movement.
    CONSTRAINT uq_usage_event_source UNIQUE (tenant_id, entitlement_key, source_type, source_event_id),
    CONSTRAINT ck_usage_event_key CHECK (
        entitlement_key ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'
    ),
    -- Signed. A location removed is a movement of -1, not the deletion of the
    -- row that added it, which is what keeps a standing count auditable.
    CONSTRAINT ck_usage_event_quantity CHECK (quantity <> 0),
    CONSTRAINT ck_usage_event_period_key CHECK (period_key ~ '^(LIFETIME|\d{4}-\d{2}(-\d{2})?)$'),
    CONSTRAINT ck_usage_event_dimensions CHECK (jsonb_typeof(dimensions) = 'object')
);

CREATE INDEX ix_usage_event_period
    ON commercial.usage_events (tenant_id, entitlement_key, period_key, occurred_at);

COMMENT ON TABLE commercial.usage_events IS
    'ADR 0021 append-only usage movements. The evidence an invoice is defended with; never updated, never deleted, and rebuildable into any aggregate.';

COMMENT ON COLUMN commercial.usage_events.quantity IS
    'A signed movement, never a balance. Consumption is the sum of movements over a period, so a bug in a consumer is corrected by an adjustment rather than by editing history.';

COMMENT ON COLUMN commercial.usage_events.period_key IS
    'The period the movement counts against, derived from occurred_at in the tenant timezone at record time. LIFETIME for standing limits such as a location count.';

COMMENT ON COLUMN commercial.usage_events.occurred_at IS
    'When the metered thing happened, not when it was recorded. A late event counts against the period it happened in, which is why the two timestamps are both kept.';

COMMENT ON COLUMN commercial.usage_events.dimensions IS
    'An allowlisted, bounded set of non-personal breakdown keys (ADR 0029). No customer identifier, no address, no phone number ever enters this column.';

-- Append-only, stated where no application path can route around it. The GRANT
-- block below withholds UPDATE and DELETE as well; this trigger is what also
-- binds a migration, a psql session, and a well-meant support fix.
CREATE OR REPLACE FUNCTION commercial.reject_usage_ledger_rewrite() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
        'The usage ledger is append-only; correct it with a usage adjustment (ADR 0021)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_usage_events_append_only
    BEFORE UPDATE OR DELETE ON commercial.usage_events
    FOR EACH ROW EXECUTE FUNCTION commercial.reject_usage_ledger_rewrite();

CREATE TABLE commercial.usage_adjustments (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    entitlement_key varchar(128) NOT NULL,
    period_key varchar(16) NOT NULL,
    quantity_delta bigint NOT NULL,
    reason varchar(1000) NOT NULL,
    source_reference varchar(200),
    approved_by varchar(255) NOT NULL,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_usage_adjustment_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT ck_usage_adjustment_key CHECK (
        entitlement_key ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'
    ),
    CONSTRAINT ck_usage_adjustment_delta CHECK (quantity_delta <> 0),
    CONSTRAINT ck_usage_adjustment_period_key CHECK (
        period_key ~ '^(LIFETIME|\d{4}-\d{2}(-\d{2})?)$'
    ),
    CONSTRAINT ck_usage_adjustment_four_eyes CHECK (approved_by <> created_by)
);

CREATE INDEX ix_usage_adjustment_period
    ON commercial.usage_adjustments (tenant_id, entitlement_key, period_key);

COMMENT ON TABLE commercial.usage_adjustments IS
    'ADR 0021 corrections to metered usage, as new signed facts. A goodwill credit and a consumer bug are both recorded here rather than by editing the ledger.';

CREATE TRIGGER trg_usage_adjustments_append_only
    BEFORE UPDATE OR DELETE ON commercial.usage_adjustments
    FOR EACH ROW EXECUTE FUNCTION commercial.reject_usage_ledger_rewrite();

CREATE TABLE commercial.usage_aggregates (
    tenant_id uuid NOT NULL,
    entitlement_key varchar(128) NOT NULL,
    period_key varchar(16) NOT NULL,
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    event_quantity bigint NOT NULL DEFAULT 0,
    adjustment_quantity bigint NOT NULL DEFAULT 0,
    consumed_quantity bigint NOT NULL DEFAULT 0,
    event_count integer NOT NULL DEFAULT 0,
    last_event_at timestamptz,
    calculation_version integer NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_usage_aggregate PRIMARY KEY (tenant_id, entitlement_key, period_key),
    CONSTRAINT fk_usage_aggregate_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT ck_usage_aggregate_period CHECK (period_end > period_start),
    CONSTRAINT ck_usage_aggregate_event_count CHECK (event_count >= 0),
    -- The stored sum must equal its own parts, so a partially applied write is
    -- refused rather than silently becoming the number on an invoice.
    CONSTRAINT ck_usage_aggregate_sum CHECK (
        consumed_quantity = event_quantity + adjustment_quantity
    ),
    CONSTRAINT ck_usage_aggregate_last_event_pair CHECK (
        (last_event_at IS NULL) = (event_count = 0)
    )
);

COMMENT ON TABLE commercial.usage_aggregates IS
    'ADR 0021 derived per-period totals. A cache: truncating this table loses nothing, because every row is recomputable from usage_events plus usage_adjustments.';

COMMENT ON COLUMN commercial.usage_aggregates.calculation_version IS
    'Bumped when the aggregation rule itself changes, so a row computed under an older rule is identifiable rather than merely different.';

COMMENT ON COLUMN commercial.usage_aggregates.event_quantity IS
    'Kept apart from adjustment_quantity rather than folded into the total, so a reconciliation can say how much of a figure was measured and how much was decided by a person.';

-- --------------------------------------------------------------------- grants

GRANT USAGE ON SCHEMA commercial TO qoida_application;

GRANT SELECT, INSERT, UPDATE ON commercial.plans TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON commercial.plan_versions TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON commercial.plan_entitlements TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON commercial.subscriptions TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON commercial.entitlement_overrides TO qoida_application;
GRANT SELECT, INSERT ON commercial.usage_events TO qoida_application;
GRANT SELECT, INSERT ON commercial.usage_adjustments TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON commercial.usage_aggregates TO qoida_application;

-- The grants are uneven and the unevenness is the point.
--
-- usage_events and usage_adjustments receive SELECT and INSERT and nothing else,
-- because the ledger is the evidence and an UPDATE grant is all it takes for a
-- disputed figure to become an unrecorded one. The trigger above says the same
-- thing to callers the grant does not reach.
--
-- No table here receives DELETE except plan_entitlements and usage_aggregates.
-- A draft plan version's entitlements are edited until it is activated, at which
-- point the trigger refuses; usage_aggregates is a cache and deleting a row is
-- how a rebuild starts. Nothing else in this schema is ever deleted: a retired
-- plan, a terminated subscription and a revoked override are all answers to
-- questions somebody will ask later.
