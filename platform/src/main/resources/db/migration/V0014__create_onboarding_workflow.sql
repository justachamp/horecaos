-- ADR 0008: resumable tenant onboarding.
--
-- Kafka carries facts to other modules but is not the workflow store and not the
-- timer. A durable SQL workflow is what lets a failed step resume without
-- repeating completed external side effects, and lets support answer "why is
-- this tenant not live" from one run rather than by reading topics.

CREATE TABLE tenant.onboarding_templates (
    id uuid PRIMARY KEY,
    code varchar(64) NOT NULL,
    version integer NOT NULL,
    status varchar(16) NOT NULL,
    description varchar(1000),
    required_steps jsonb NOT NULL,
    default_configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    retired_at timestamptz,
    CONSTRAINT uq_onboarding_template_version UNIQUE (code, version),
    CONSTRAINT ck_onboarding_template_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_onboarding_template_version CHECK (version > 0),
    CONSTRAINT ck_onboarding_template_steps CHECK (jsonb_typeof(required_steps) = 'array'),
    CONSTRAINT ck_onboarding_template_config CHECK (jsonb_typeof(default_configuration) = 'object')
);

COMMENT ON TABLE tenant.onboarding_templates IS
    'ADR 0008 versioned onboarding templates. Immutable once used by a run, so changing defaults is a new version.';

CREATE TABLE tenant.onboarding_runs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    template_id uuid NOT NULL,
    template_version integer NOT NULL,
    status varchar(24) NOT NULL,
    current_phase varchar(24) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    started_by varchar(255) NOT NULL,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    failed_at timestamptz,
    activation_approval_id uuid,
    last_error_code varchar(64),
    last_error varchar(2000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_onboarding_run_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT fk_onboarding_run_template FOREIGN KEY (template_id)
        REFERENCES tenant.onboarding_templates (id),
    CONSTRAINT ck_onboarding_run_status CHECK (
        status IN ('DRAFT', 'PROVISIONING', 'CONFIGURING', 'VALIDATING',
                   'READY', 'ACTIVATING', 'ACTIVE', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_onboarding_run_version CHECK (version >= 0)
);

-- One active run per tenant. Two concurrent runs would race on the same
-- external side effects, which is the failure this whole workflow exists to
-- make impossible.
CREATE UNIQUE INDEX uq_onboarding_run_active
    ON tenant.onboarding_runs (tenant_id)
    WHERE status NOT IN ('ACTIVE', 'CANCELLED');

CREATE INDEX ix_onboarding_run_status ON tenant.onboarding_runs (status, started_at);

CREATE TABLE tenant.onboarding_steps (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    step_key varchar(64) NOT NULL,
    step_version integer NOT NULL DEFAULT 1,
    phase varchar(24) NOT NULL,
    sequence_number integer NOT NULL,
    status varchar(24) NOT NULL,
    required boolean NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    claim_token uuid,
    claimed_at timestamptz,
    input_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    result_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- The immutable identifier the external system assigned. Re-running a step
    -- reconciles against this rather than creating a second organization.
    external_reference varchar(512),
    checkpoint varchar(512),
    last_error_code varchar(64),
    last_error varchar(2000),
    started_at timestamptz,
    completed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_onboarding_step_run FOREIGN KEY (run_id)
        REFERENCES tenant.onboarding_runs (id) ON DELETE CASCADE,
    CONSTRAINT uq_onboarding_step UNIQUE (run_id, step_key),
    CONSTRAINT ck_onboarding_step_status CHECK (
        status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'SKIPPED', 'BLOCKED')
    ),
    CONSTRAINT ck_onboarding_step_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_onboarding_step_input CHECK (jsonb_typeof(input_snapshot) = 'object'),
    CONSTRAINT ck_onboarding_step_result CHECK (jsonb_typeof(result_snapshot) = 'object'),
    CONSTRAINT ck_onboarding_step_lifecycle CHECK (
        (status = 'RUNNING' AND claim_token IS NOT NULL AND claimed_at IS NOT NULL)
        OR (status <> 'RUNNING' AND claim_token IS NULL)
    )
);

CREATE INDEX ix_onboarding_step_due
    ON tenant.onboarding_steps (run_id, sequence_number)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX ix_onboarding_step_claims
    ON tenant.onboarding_steps (claimed_at)
    WHERE status = 'RUNNING';

COMMENT ON COLUMN tenant.onboarding_steps.status IS
    'BLOCKED means the capability this step checks does not exist yet. It is never reported as success (ADR 0008).';

CREATE TABLE tenant.readiness_checks (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    check_key varchar(64) NOT NULL,
    required boolean NOT NULL,
    status varchar(16) NOT NULL,
    evaluator_version varchar(32) NOT NULL,
    evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    detail varchar(1000),
    evaluated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_readiness_check_run FOREIGN KEY (run_id)
        REFERENCES tenant.onboarding_runs (id) ON DELETE CASCADE,
    CONSTRAINT uq_readiness_check UNIQUE (run_id, check_key),
    CONSTRAINT ck_readiness_status CHECK (status IN ('PASSED', 'FAILED', 'BLOCKED', 'SKIPPED')),
    CONSTRAINT ck_readiness_evidence CHECK (jsonb_typeof(evidence) = 'object')
);

COMMENT ON TABLE tenant.readiness_checks IS
    'ADR 0008 readiness evidence. A run cannot reach READY while a required check is missing or failing.';
