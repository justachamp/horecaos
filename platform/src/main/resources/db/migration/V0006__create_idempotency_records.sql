-- ADR 0031: one idempotency mechanism for every effectful mutation.
--
-- Seven ADRs require an Idempotency-Key on mutations. Without a shared
-- implementation each would build its own, and a retried checkout, refund, or
-- courier booking would depend on which module happened to get it right.

CREATE SCHEMA IF NOT EXISTS platform;

COMMENT ON SCHEMA platform IS
    'Cross-cutting API infrastructure owned by no single business module (ADR 0031)';

CREATE TABLE platform.idempotency_records (
    id uuid PRIMARY KEY,
    scope_key varchar(128) NOT NULL,
    idempotency_key varchar(255) NOT NULL,
    tenant_id uuid,
    principal_subject varchar(255) NOT NULL,
    request_hash char(64) NOT NULL,
    status varchar(16) NOT NULL,
    response_status integer,
    response_body text,
    lease_expires_at timestamptz NOT NULL,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    expires_at timestamptz NOT NULL,
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_idempotency_response_status CHECK (
        response_status IS NULL OR response_status BETWEEN 100 AND 599
    ),
    -- A completed record must carry the response it is replaying; an in-progress
    -- one must not, so a crash mid-request cannot replay a half-formed result.
    CONSTRAINT ck_idempotency_lifecycle CHECK (
        (status = 'IN_PROGRESS' AND completed_at IS NULL AND response_status IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND response_status IS NOT NULL)
    )
);

-- scope_key names the operation, so one client key reused against two different
-- operations does not collide.
CREATE UNIQUE INDEX uq_idempotency_scope_key_tenant
    ON platform.idempotency_records (scope_key, idempotency_key, tenant_id)
    WHERE tenant_id IS NOT NULL;

CREATE UNIQUE INDEX uq_idempotency_scope_key_platform
    ON platform.idempotency_records (scope_key, idempotency_key)
    WHERE tenant_id IS NULL;

CREATE INDEX ix_idempotency_expiry ON platform.idempotency_records (expires_at);

COMMENT ON TABLE platform.idempotency_records IS
    'ADR 0031 idempotency records. Retention is at least 24 hours, longer for monetary operations.';
