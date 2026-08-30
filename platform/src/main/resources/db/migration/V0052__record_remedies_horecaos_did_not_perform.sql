-- ADR 0013 as amended by the owner's decision of 2026-08-25: a refund is a
-- bookkeeping and remedy record, not a payment operation. Staff refund in the
-- provider's own cabinet; HorecaOS records it so the order, the settlement and the
-- analytics stay whole.
--
-- The cost of that decision is that this table asserts something HorecaOS did not
-- perform and cannot verify. The columns below are what keep the assertion from
-- reading as a fact: the money is split at the moment of recording into the part
-- the platform settled itself and the part it is taking on trust, the
-- attestation carries who says they did it and where, and verification_state is
-- an explicit value rather than a null that would read as "unknown".

CREATE TABLE payments.order_remedies (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    order_id uuid NOT NULL,

    remedy_type varchar(32) NOT NULL,
    reason_code varchar(48) NOT NULL,
    reason text NOT NULL,

    currency char(3) NOT NULL,

    -- Whole som (ADR 0018). amount_minor is what the customer got back;
    -- the two columns beneath it say who actually moved it.
    amount_minor bigint NOT NULL DEFAULT 0,
    attested_money_minor bigint NOT NULL DEFAULT 0,
    platform_settled_minor bigint NOT NULL DEFAULT 0,

    settlement_basis varchar(24) NOT NULL,

    execution_channel varchar(24),
    provider_reference varchar(128),
    executed_by varchar(128),
    executed_at timestamptz,

    verification_state varchar(16) NOT NULL DEFAULT 'UNVERIFIED',
    verification_source varchar(128),
    verified_at timestamptz,

    -- The delivery fee the reimbursement was bounded by, or NULL when the
    -- platform could not establish one. NULL is not zero: zero would mean
    -- free delivery and would refuse every reimbursement.
    delivery_fee_basis_minor bigint,

    recorded_by varchar(128) NOT NULL,
    recorded_at timestamptz NOT NULL,
    approval_request_id uuid,

    idempotency_key varchar(255) NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_remedy_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_remedy_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_remedy_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),

    CONSTRAINT uq_remedy_identity UNIQUE (tenant_id, id),
    CONSTRAINT uq_remedy_idempotency UNIQUE (tenant_id, idempotency_key),

    CONSTRAINT ck_remedy_type CHECK (remedy_type IN (
        'ORDER_REFUND', 'DELIVERY_FEE_REIMBURSEMENT', 'FUTURE_DISCOUNT')),
    CONSTRAINT ck_remedy_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_remedy_amounts_not_negative CHECK (
        amount_minor >= 0 AND attested_money_minor >= 0
        AND platform_settled_minor >= 0),

    -- The gap, as an invariant. What the customer got back is exactly what
    -- HorecaOS moved plus what somebody says they moved elsewhere.
    CONSTRAINT ck_remedy_amount_is_split CHECK (
        amount_minor = attested_money_minor + platform_settled_minor),

    CONSTRAINT ck_remedy_basis CHECK (settlement_basis IN (
        'OPERATOR_ATTESTED', 'PLATFORM_SETTLED', 'MIXED', 'NOT_MONEY')),
    CONSTRAINT ck_remedy_basis_matches_split CHECK (
        CASE settlement_basis
            WHEN 'OPERATOR_ATTESTED' THEN
                attested_money_minor > 0 AND platform_settled_minor = 0
            WHEN 'PLATFORM_SETTLED' THEN
                platform_settled_minor > 0 AND attested_money_minor = 0
            WHEN 'MIXED' THEN
                attested_money_minor > 0 AND platform_settled_minor > 0
            ELSE
                attested_money_minor = 0 AND platform_settled_minor = 0
        END),

    -- A future discount cannot be summed into a refund figure, because there
    -- is nothing on the row to sum. Its exposure lives on the entitlement.
    CONSTRAINT ck_remedy_future_discount_is_not_money CHECK (
        remedy_type <> 'FUTURE_DISCOUNT'
        OR (amount_minor = 0 AND settlement_basis = 'NOT_MONEY')),
    CONSTRAINT ck_remedy_money_remedy_moves_money CHECK (
        remedy_type = 'FUTURE_DISCOUNT' OR amount_minor > 0),

    -- Money the platform did not move is recorded only with who moved it,
    -- when, and through which channel. Otherwise the ledger carries an
    -- assertion with nobody attached to it.
    CONSTRAINT ck_remedy_attestation_is_evidenced CHECK (
        attested_money_minor = 0
        OR (execution_channel IS NOT NULL AND executed_by IS NOT NULL
            AND executed_at IS NOT NULL)),
    CONSTRAINT ck_remedy_channel CHECK (execution_channel IS NULL
        OR execution_channel IN (
            'PROVIDER_CONSOLE', 'CASH_DRAWER', 'BANK_TRANSFER')),
    -- Only a cabinet issues an identifier, and without it nothing can ever
    -- match a settlement line.
    CONSTRAINT ck_remedy_console_reference CHECK (
        execution_channel IS DISTINCT FROM 'PROVIDER_CONSOLE'
        OR provider_reference IS NOT NULL),

    CONSTRAINT ck_remedy_verification_state CHECK (verification_state IN (
        'UNVERIFIED', 'CONFIRMED', 'DISPUTED')),
    CONSTRAINT ck_remedy_verification_is_sourced CHECK (
        verification_state = 'UNVERIFIED'
        OR (verification_source IS NOT NULL AND verified_at IS NOT NULL)),
    -- Only an assertion can be corroborated. A points reversal was performed
    -- here and verifying it would be verifying our own ledger against itself.
    CONSTRAINT ck_remedy_only_attestations_are_verified CHECK (
        verification_state = 'UNVERIFIED' OR attested_money_minor > 0),

    CONSTRAINT ck_remedy_fee_basis_is_a_delivery_concern CHECK (
        delivery_fee_basis_minor IS NULL
        OR (remedy_type = 'DELIVERY_FEE_REIMBURSEMENT'
            AND delivery_fee_basis_minor > 0)),
    CONSTRAINT ck_remedy_version CHECK (version >= 1)
);

COMMENT ON TABLE payments.order_remedies IS
    'ADR 0013 as amended 2026-08-25: refunds and service-recovery remedies as bookkeeping. Nothing here calls a payment provider; the money moved, if it moved, in the provider''s own cabinet.';

COMMENT ON COLUMN payments.order_remedies.attested_money_minor IS
    'Money a person asserts left a merchant account HorecaOS cannot see. Unverifiable by construction, and reported apart from platform_settled_minor everywhere.';

COMMENT ON COLUMN payments.order_remedies.verification_state IS
    'UNVERIFIED is the resting state, not a transitional one: ADR 0013 settlement import does not exist, so nothing can yet corroborate an attestation.';

CREATE INDEX ix_remedy_order ON payments.order_remedies (tenant_id, order_id);

-- The reconciliation worklist. Partial, because the whole point is the small
-- set of rows that assert money moved and have nothing backing them.
CREATE INDEX ix_remedy_unverified_attestation
    ON payments.order_remedies (tenant_id, recorded_at)
 WHERE attested_money_minor > 0 AND verification_state = 'UNVERIFIED';

-- ------------------------------------------------------ future discounts

CREATE TABLE payments.remedy_entitlements (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    remedy_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,

    applies_to varchar(16) NOT NULL,
    benefit_kind varchar(16) NOT NULL,
    percent_basis_points integer,
    amount_minor bigint,
    maximum_minor bigint,
    currency char(3) NOT NULL,

    uses_granted integer NOT NULL,
    uses_consumed integer NOT NULL DEFAULT 0,

    starts_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_entitlement_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_entitlement_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_entitlement_remedy FOREIGN KEY (tenant_id, remedy_id)
        REFERENCES payments.order_remedies (tenant_id, id),
    CONSTRAINT fk_entitlement_customer FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),

    CONSTRAINT uq_entitlement_identity UNIQUE (tenant_id, id),
    -- One grant per remedy. Two entitlements behind one decision would be
    -- twice the liability an approver weighed.
    CONSTRAINT uq_entitlement_remedy UNIQUE (tenant_id, remedy_id),

    CONSTRAINT ck_entitlement_scope CHECK (applies_to IN (
        'SUBTOTAL', 'DELIVERY_FEE', 'BOTH')),
    CONSTRAINT ck_entitlement_benefit CHECK (benefit_kind IN (
        'PERCENT', 'FIXED_AMOUNT')),
    CONSTRAINT ck_entitlement_currency CHECK (currency ~ '^[A-Z]{3}$'),

    -- A percentage without a cap is an unbounded liability granted by one
    -- console click: 20% off a delivery fee is 2 000 som and 20% off a
    -- catering subtotal is 400 000.
    CONSTRAINT ck_entitlement_percent_is_capped CHECK (
        benefit_kind <> 'PERCENT'
        OR (percent_basis_points BETWEEN 1 AND 10000
            AND maximum_minor IS NOT NULL AND maximum_minor > 0
            AND amount_minor IS NULL)),
    CONSTRAINT ck_entitlement_fixed_amount CHECK (
        benefit_kind <> 'FIXED_AMOUNT'
        OR (amount_minor IS NOT NULL AND amount_minor > 0
            AND percent_basis_points IS NULL)),

    -- The redemption cap, in the database. Two concurrent orders cannot both
    -- pass a check made in application code; they can both fail this one.
    CONSTRAINT ck_entitlement_uses_within_grant CHECK (
        uses_granted BETWEEN 1 AND 10
        AND uses_consumed >= 0 AND uses_consumed <= uses_granted),

    CONSTRAINT ck_entitlement_window CHECK (expires_at > starts_at),
    CONSTRAINT ck_entitlement_status CHECK (status IN (
        'ACTIVE', 'EXHAUSTED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT ck_entitlement_version CHECK (version >= 1)
);

COMMENT ON TABLE payments.remedy_entitlements IS
    'ADR 0013 as amended 2026-08-25: a future-discount remedy, worth N uses against the subtotal, the delivery fee, or both. Granted by payments; priced by ADR 0018.';

CREATE INDEX ix_entitlement_spendable
    ON payments.remedy_entitlements (tenant_id, brand_id, customer_account_id,
                                     expires_at)
 WHERE status = 'ACTIVE';

CREATE TABLE payments.entitlement_redemptions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    entitlement_id uuid NOT NULL,
    order_id uuid NOT NULL,

    subtotal_discount_minor bigint NOT NULL DEFAULT 0,
    delivery_discount_minor bigint NOT NULL DEFAULT 0,
    currency char(3) NOT NULL,

    redeemed_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_entitlement_redemption_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_entitlement_redemption_entitlement
        FOREIGN KEY (tenant_id, entitlement_id)
        REFERENCES payments.remedy_entitlements (tenant_id, id),
    CONSTRAINT fk_entitlement_redemption_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),

    -- One use is one order. Without this a retried placement is a second
    -- use, and an N-use grant is worth however many times the customer
    -- refreshes.
    CONSTRAINT uq_entitlement_redemption_order
        UNIQUE (tenant_id, entitlement_id, order_id),

    CONSTRAINT ck_entitlement_redemption_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_entitlement_redemption_positive CHECK (
        subtotal_discount_minor >= 0 AND delivery_discount_minor >= 0
        AND subtotal_discount_minor + delivery_discount_minor > 0)
);

COMMENT ON TABLE payments.entitlement_redemptions IS
    'Append-only: one row per use of a future-discount entitlement, keyed by the order that used it.';

GRANT SELECT, INSERT, UPDATE ON payments.order_remedies TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON payments.remedy_entitlements TO horecaos_application;
-- Append-only, said as a grant rather than as a convention.
GRANT SELECT, INSERT ON payments.entitlement_redemptions TO horecaos_application;
