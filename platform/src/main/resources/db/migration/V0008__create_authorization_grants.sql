-- ADR 0025: capability-based authorization owned by Qoida.
--
-- ADR 0003 stopped at the tenant boundary and left fine-grained grants to
-- "Qoida projections" without saying what they are. Everything after it assumed
-- the missing model, and today organization membership alone authorises reading
-- every location's data in a tenant. These tables close that gap.

CREATE TABLE iam.roles (
    id uuid PRIMARY KEY,
    tenant_id uuid,
    code varchar(64) NOT NULL,
    name varchar(255) NOT NULL,
    scope_type varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    is_platform_defined boolean NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_role_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT ck_role_scope_type CHECK (scope_type IN ('PLATFORM', 'TENANT', 'BRAND', 'LOCATION')),
    CONSTRAINT ck_role_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    -- A platform-defined role is owned by nobody; a tenant-defined role must
    -- name its tenant, so one tenant can never see another's custom role.
    CONSTRAINT ck_role_ownership CHECK (
        (is_platform_defined AND tenant_id IS NULL)
        OR (NOT is_platform_defined AND tenant_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_role_platform_code ON iam.roles (code) WHERE tenant_id IS NULL;
CREATE UNIQUE INDEX uq_role_tenant_code ON iam.roles (tenant_id, code) WHERE tenant_id IS NOT NULL;

CREATE TABLE iam.role_capabilities (
    role_id uuid NOT NULL,
    capability_code varchar(128) NOT NULL,
    PRIMARY KEY (role_id, capability_code),
    CONSTRAINT fk_role_capability_role FOREIGN KEY (role_id)
        REFERENCES iam.roles (id) ON DELETE CASCADE
);

CREATE TABLE iam.grants (
    id uuid PRIMARY KEY,
    tenant_id uuid,
    principal_subject varchar(255) NOT NULL,
    role_id uuid NOT NULL,
    scope_type varchar(16) NOT NULL,
    scope_id uuid,
    status varchar(16) NOT NULL,
    granted_by varchar(255) NOT NULL,
    reason varchar(1000) NOT NULL,
    valid_from timestamptz NOT NULL DEFAULT now(),
    valid_until timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_grant_role FOREIGN KEY (role_id) REFERENCES iam.roles (id),
    CONSTRAINT fk_grant_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT ck_grant_scope_type CHECK (scope_type IN ('PLATFORM', 'TENANT', 'BRAND', 'LOCATION')),
    CONSTRAINT ck_grant_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_grant_scope_id CHECK (
        (scope_type = 'PLATFORM' AND scope_id IS NULL AND tenant_id IS NULL)
        OR (scope_type <> 'PLATFORM' AND scope_id IS NOT NULL AND tenant_id IS NOT NULL)
    ),
    CONSTRAINT ck_grant_validity CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_grant_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_grant_active
    ON iam.grants (principal_subject, role_id, scope_type, coalesce(scope_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE status = 'ACTIVE';

-- The hot path: resolve everything a principal holds in one tenant.
CREATE INDEX ix_grant_principal_tenant
    ON iam.grants (principal_subject, tenant_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_grant_expiry ON iam.grants (valid_until) WHERE status = 'ACTIVE' AND valid_until IS NOT NULL;

COMMENT ON TABLE iam.grants IS
    'ADR 0025 capability grants. Scope covers downward only: a tenant grant reaches every brand and location, a location grant reaches one location.';

-- Written from code at startup so the catalogue can be joined for reporting
-- without the database becoming the authority for what a capability means.
CREATE TABLE iam.capability_registry_snapshot (
    capability_code varchar(128) PRIMARY KEY,
    resource_type varchar(64) NOT NULL,
    action varchar(64) NOT NULL,
    observed_at timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE iam.capability_registry_snapshot IS
    'ADR 0025 projection of the code-owned capability registry. Never the authority.';
