-- ADR 0029, batch 21 (w10-privacy): a legal hold on a named data subject.
--
-- The ADR lists "legal hold" among the privacy operations and CustomerErasureService's class doc named it, with an
-- open dispute and a pending delivery, as the checks an execution does not make. A hold is a decision that a
-- customer's data must be kept because the tenant is, or may be, obliged to produce it: a claim, an investigation, a
-- regulator's request. While it stands an erasure request cannot be executed and the retention jobs that expire a
-- customer's data leave her alone.
--
-- The hold names an account, a closed reason and the two people who agreed to it. It carries no free text: the
-- justification a person typed belongs to the ADR 0027 approval request that authorised the hold (customer.legal-hold
-- .place), which is the record a checker reads and an auditor asks for. A free-text column here would be one more
-- place a name could end up.

CREATE TABLE customer.legal_holds (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,

    status varchar(8) NOT NULL DEFAULT 'ACTIVE',
    reason_code varchar(24) NOT NULL,

    placed_by_actor_type varchar(16) NOT NULL,
    placed_by_actor_id varchar(255) NOT NULL,
    placed_at timestamptz NOT NULL DEFAULT now(),
    place_approval_id uuid,

    lifted_by_actor_type varchar(16),
    lifted_by_actor_id varchar(255),
    lifted_at timestamptz,
    lift_approval_id uuid,

    version integer NOT NULL DEFAULT 1,

    CONSTRAINT ck_legal_hold_status CHECK (status IN ('ACTIVE', 'LIFTED')),
    CONSTRAINT ck_legal_hold_reason CHECK (
        reason_code IN ('LITIGATION', 'REGULATORY_REQUEST', 'INVESTIGATION', 'OTHER')),
    CONSTRAINT ck_legal_hold_actor_type CHECK (
        placed_by_actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'MIGRATION')),
    CONSTRAINT ck_legal_hold_lifted_actor_type CHECK (
        lifted_by_actor_type IS NULL OR lifted_by_actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'MIGRATION')),
    -- A hold is LIFTED exactly when it records who lifted it and when: the equivalence, not one direction, is what
    -- catches a service that flips the status without writing the person.
    CONSTRAINT ck_legal_hold_lifted_fields CHECK (
        (status = 'LIFTED' AND lifted_at IS NOT NULL
            AND lifted_by_actor_type IS NOT NULL AND lifted_by_actor_id IS NOT NULL)
        OR (status = 'ACTIVE' AND lifted_at IS NULL
            AND lifted_by_actor_type IS NULL AND lifted_by_actor_id IS NULL)),
    CONSTRAINT ck_legal_hold_version CHECK (version >= 1),
    CONSTRAINT fk_legal_hold_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT uq_legal_hold_identity UNIQUE (id, tenant_id)
);

-- At most one hold stands on an account; placing a second while one is active is the same hold.
CREATE UNIQUE INDEX ux_legal_hold_active
    ON customer.legal_holds (tenant_id, customer_account_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_legal_holds_account
    ON customer.legal_holds (tenant_id, customer_account_id, placed_at DESC);

COMMENT ON TABLE customer.legal_holds IS
    'ADR 0029. A hold on one customer account: while ACTIVE, an erasure of the account is refused and the retention jobs that expire her data skip it. Account id, closed reason code and the two people; the justification lives on the ADR 0027 approval request.';

GRANT SELECT, INSERT, UPDATE ON customer.legal_holds TO horecaos_application;

-- Placing and lifting a hold are each a second person's decision. Fail-closed, like the other actions that remove a
-- right or release a record: seeded at platform scope so the action is governed from the first day, and a
-- deployment that deleted the policy should stop holds being placed or lifted, not let one person decide alone.
-- The approver holds the capability that executes an erasure, because that is the right the hold restricts.
INSERT INTO audit.approval_policies (
    id, tenant_id, action_code, scope_type, threshold_json, required_approver_capability,
    valid_from, version, approved_by)
VALUES
    ('0192d1a0-0000-7000-8000-000000068201', NULL, 'customer.legal-hold.place', 'PLATFORM',
     '{"description": "Every legal hold placed on a customer account"}'::jsonb,
     'customer.erasure.execute', '2026-10-14T00:00:00Z', 1, 'migration V0682'),
    ('0192d1a0-0000-7000-8000-000000068202', NULL, 'customer.legal-hold.lift', 'PLATFORM',
     '{"description": "Every legal hold lifted from a customer account"}'::jsonb,
     'customer.erasure.execute', '2026-10-14T00:00:00Z', 1, 'migration V0682');
