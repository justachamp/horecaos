-- ADR 0027: one append-only evidence store and one approval model.
--
-- The audit schema was created empty in V0001 while nearly every later ADR
-- required audit facts as a precondition. Maker-checker appears in five ADRs
-- with no shared definition of what an approval is. Both are filled in here,
-- before the capabilities that depend on them are built.

CREATE TABLE audit.audit_events (
    id uuid NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    tenant_id uuid,
    audit_class varchar(16) NOT NULL,
    action_code varchar(128) NOT NULL,
    actor_type varchar(16) NOT NULL,
    actor_subject varchar(255),
    actor_display varchar(255),
    on_behalf_of_subject varchar(255),
    scope_type varchar(16) NOT NULL,
    scope_id uuid,
    target_type varchar(64),
    target_id uuid,
    target_version bigint,
    outcome varchar(16) NOT NULL,
    reason varchar(1000),
    change_document jsonb,
    evidence_reference varchar(512),
    capability_used varchar(128),
    approval_request_id uuid,
    correlation_id varchar(128) NOT NULL,
    causation_id varchar(128),
    request_id varchar(128),
    source_ip_hash char(64),
    user_agent_hash char(64),
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY (id, recorded_at),
    CONSTRAINT ck_audit_class CHECK (audit_class IN ('SECURITY', 'BUSINESS')),
    -- actor_type distinguishes a person from a backfill, so a migration is never
    -- mistaken for a user action during an investigation.
    CONSTRAINT ck_audit_actor_type CHECK (
        actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'MIGRATION')
    ),
    CONSTRAINT ck_audit_actor_subject CHECK (
        (actor_type = 'USER' AND actor_subject IS NOT NULL) OR actor_type <> 'USER'
    ),
    CONSTRAINT ck_audit_scope_type CHECK (
        scope_type IN ('PLATFORM', 'TENANT', 'BRAND', 'LOCATION')
    ),
    CONSTRAINT ck_audit_scope_id CHECK (
        (scope_type = 'PLATFORM' AND scope_id IS NULL)
        OR (scope_type <> 'PLATFORM' AND scope_id IS NOT NULL)
    ),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCEEDED', 'REJECTED', 'FAILED')),
    CONSTRAINT ck_audit_change_document CHECK (
        change_document IS NULL OR jsonb_typeof(change_document) = 'object'
    ),
    CONSTRAINT ck_audit_hashes CHECK (
        (source_ip_hash IS NULL OR source_ip_hash ~ '^[0-9a-f]{64}$')
        AND (user_agent_hash IS NULL OR user_agent_hash ~ '^[0-9a-f]{64}$')
    )
) PARTITION BY RANGE (recorded_at);

-- Yearly partitions to start. Splitting a year into months later is a routine
-- operation; retrofitting partitioning onto a large unpartitioned table is not.
CREATE TABLE audit.audit_events_2026 PARTITION OF audit.audit_events
    FOR VALUES FROM ('2026-01-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');
CREATE TABLE audit.audit_events_2027 PARTITION OF audit.audit_events
    FOR VALUES FROM ('2027-01-01 00:00:00+00') TO ('2028-01-01 00:00:00+00');
CREATE TABLE audit.audit_events_2028 PARTITION OF audit.audit_events
    FOR VALUES FROM ('2028-01-01 00:00:00+00') TO ('2029-01-01 00:00:00+00');

-- A default partition so a clock skew or a late backfill can never fail an
-- audited business action. Operations moves rows out of it when adding partitions.
CREATE TABLE audit.audit_events_default PARTITION OF audit.audit_events DEFAULT;

CREATE INDEX ix_audit_tenant_time ON audit.audit_events (tenant_id, recorded_at DESC);
CREATE INDEX ix_audit_actor_time ON audit.audit_events (actor_subject, recorded_at DESC);
CREATE INDEX ix_audit_target ON audit.audit_events (target_type, target_id, recorded_at DESC);
CREATE INDEX ix_audit_action ON audit.audit_events (action_code, recorded_at DESC);
CREATE INDEX ix_audit_correlation ON audit.audit_events (correlation_id);

COMMENT ON TABLE audit.audit_events IS
    'ADR 0027 append-only audit evidence. Written in the same transaction as the change it describes. No application path may update or delete a row.';

CREATE TABLE audit.approval_policies (
    id uuid PRIMARY KEY,
    tenant_id uuid,
    action_code varchar(128) NOT NULL,
    scope_type varchar(16) NOT NULL,
    threshold_json jsonb NOT NULL,
    required_approver_capability varchar(128) NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    version integer NOT NULL,
    approved_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_approval_policy_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT uq_approval_policy_version UNIQUE (action_code, scope_type, tenant_id, version),
    CONSTRAINT ck_approval_policy_scope_type CHECK (
        scope_type IN ('PLATFORM', 'TENANT', 'BRAND', 'LOCATION')
    ),
    CONSTRAINT ck_approval_policy_version CHECK (version > 0),
    CONSTRAINT ck_approval_policy_threshold CHECK (jsonb_typeof(threshold_json) = 'object'),
    CONSTRAINT ck_approval_policy_validity CHECK (valid_until IS NULL OR valid_until >= valid_from)
);

CREATE TABLE audit.approval_requests (
    id uuid PRIMARY KEY,
    tenant_id uuid,
    action_code varchar(128) NOT NULL,
    parameters_hash char(64) NOT NULL,
    scope_type varchar(16) NOT NULL,
    scope_id uuid,
    policy_id uuid NOT NULL,
    policy_version integer NOT NULL,
    threshold_description varchar(500) NOT NULL,
    status varchar(16) NOT NULL,
    requested_by varchar(255) NOT NULL,
    requested_at timestamptz NOT NULL DEFAULT now(),
    reason varchar(1000) NOT NULL,
    decided_by varchar(255),
    decided_at timestamptz,
    decision_reason varchar(1000),
    expires_at timestamptz NOT NULL,
    evidence_reference varchar(512),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_approval_request_policy FOREIGN KEY (policy_id)
        REFERENCES audit.approval_policies (id),
    CONSTRAINT fk_approval_request_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT ck_approval_request_status CHECK (
        status IN ('PENDING', 'APPROVED', 'DECLINED', 'EXPIRED')
    ),
    CONSTRAINT ck_approval_request_hash CHECK (parameters_hash ~ '^[0-9a-f]{64}$'),
    -- Four eyes, enforced by the database rather than by a service that could be
    -- bypassed. A requester approving their own action is the failure this exists
    -- to prevent.
    CONSTRAINT ck_approval_request_four_eyes CHECK (
        decided_by IS NULL OR decided_by <> requested_by
    ),
    CONSTRAINT ck_approval_request_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status = 'EXPIRED' AND decided_by IS NULL)
        OR (status IN ('APPROVED', 'DECLINED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
    ),
    CONSTRAINT ck_approval_request_scope_id CHECK (
        (scope_type = 'PLATFORM' AND scope_id IS NULL)
        OR (scope_type <> 'PLATFORM' AND scope_id IS NOT NULL)
    ),
    CONSTRAINT ck_approval_request_version CHECK (version >= 0)
);

CREATE INDEX ix_approval_request_pending
    ON audit.approval_requests (tenant_id, status, requested_at)
    WHERE status = 'PENDING';

CREATE INDEX ix_approval_request_action
    ON audit.approval_requests (action_code, parameters_hash);

COMMENT ON TABLE audit.approval_requests IS
    'ADR 0027 maker-checker requests. The policy version is snapshotted so a later policy change cannot alter what was approved.';

-- The application role may add evidence and read it, never rewrite it. Granting
-- this role to the environment login user keeps credentials out of migrations.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'qoida_application') THEN
        CREATE ROLE qoida_application NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA audit TO qoida_application;
GRANT INSERT, SELECT ON audit.audit_events TO qoida_application;
GRANT INSERT, SELECT ON audit.audit_events_2026 TO qoida_application;
GRANT INSERT, SELECT ON audit.audit_events_2027 TO qoida_application;
GRANT INSERT, SELECT ON audit.audit_events_2028 TO qoida_application;
GRANT INSERT, SELECT ON audit.audit_events_default TO qoida_application;
GRANT INSERT, SELECT, UPDATE ON audit.approval_requests TO qoida_application;
GRANT SELECT ON audit.approval_policies TO qoida_application;

REVOKE UPDATE, DELETE, TRUNCATE ON audit.audit_events FROM qoida_application;
