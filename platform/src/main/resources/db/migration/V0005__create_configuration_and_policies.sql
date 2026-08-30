-- ADR 0030: one scoped configuration and policy mechanism.
--
-- The precedence chain platform -> tenant -> brand -> location is re-described
-- in at least eight ADRs. These tables give it one implementation, one null
-- semantic, and one snapshot mechanism, so a decision made under a policy stays
-- explainable after that policy changes.

CREATE TABLE tenant.configuration_values (
    id uuid PRIMARY KEY,
    key_code varchar(128) NOT NULL,
    scope_type varchar(16) NOT NULL,
    tenant_id uuid,
    brand_id uuid,
    location_id uuid,
    value_type varchar(16) NOT NULL,
    boolean_value boolean,
    integer_value bigint,
    decimal_value numeric(38, 10),
    string_value text,
    is_explicit_null boolean NOT NULL DEFAULT false,
    set_by varchar(255) NOT NULL,
    reason varchar(1000),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_configuration_value_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_configuration_value_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_configuration_value_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT ck_configuration_value_scope_type CHECK (
        scope_type IN ('PLATFORM', 'TENANT', 'BRAND', 'LOCATION')
    ),
    -- Ancestry: a scope carries exactly the identifiers its level requires, so a
    -- location value cannot reference a location outside its tenant and brand.
    CONSTRAINT ck_configuration_value_scope_shape CHECK (
        (scope_type = 'PLATFORM' AND tenant_id IS NULL AND brand_id IS NULL AND location_id IS NULL)
        OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL AND brand_id IS NULL AND location_id IS NULL)
        OR (scope_type = 'BRAND' AND tenant_id IS NOT NULL AND brand_id IS NOT NULL AND location_id IS NULL)
        OR (scope_type = 'LOCATION' AND tenant_id IS NOT NULL AND brand_id IS NOT NULL AND location_id IS NOT NULL)
    ),
    CONSTRAINT ck_configuration_value_type CHECK (
        value_type IN ('BOOLEAN', 'INTEGER', 'DECIMAL', 'STRING')
    ),
    -- An explicit null carries no value; a set row carries exactly one typed value.
    CONSTRAINT ck_configuration_value_payload CHECK (
        (is_explicit_null
            AND boolean_value IS NULL AND integer_value IS NULL
            AND decimal_value IS NULL AND string_value IS NULL)
        OR (NOT is_explicit_null AND (
            (value_type = 'BOOLEAN' AND boolean_value IS NOT NULL
                AND integer_value IS NULL AND decimal_value IS NULL AND string_value IS NULL)
            OR (value_type = 'INTEGER' AND integer_value IS NOT NULL
                AND boolean_value IS NULL AND decimal_value IS NULL AND string_value IS NULL)
            OR (value_type = 'DECIMAL' AND decimal_value IS NOT NULL
                AND boolean_value IS NULL AND integer_value IS NULL AND string_value IS NULL)
            OR (value_type = 'STRING' AND string_value IS NOT NULL
                AND boolean_value IS NULL AND integer_value IS NULL AND decimal_value IS NULL)
        ))
    ),
    CONSTRAINT ck_configuration_value_version CHECK (version >= 0)
);

-- One row per key per scope. Partial indexes because NULL scope identifiers
-- would otherwise defeat a plain composite unique constraint.
CREATE UNIQUE INDEX uq_configuration_value_platform
    ON tenant.configuration_values (key_code)
    WHERE scope_type = 'PLATFORM';

CREATE UNIQUE INDEX uq_configuration_value_tenant
    ON tenant.configuration_values (key_code, tenant_id)
    WHERE scope_type = 'TENANT';

CREATE UNIQUE INDEX uq_configuration_value_brand
    ON tenant.configuration_values (key_code, tenant_id, brand_id)
    WHERE scope_type = 'BRAND';

CREATE UNIQUE INDEX uq_configuration_value_location
    ON tenant.configuration_values (key_code, tenant_id, brand_id, location_id)
    WHERE scope_type = 'LOCATION';

CREATE INDEX ix_configuration_value_lookup
    ON tenant.configuration_values (key_code, tenant_id);

COMMENT ON TABLE tenant.configuration_values IS
    'ADR 0030 scoped configuration values. Absence means "not set here"; is_explicit_null means "deliberately unset here".';

CREATE TABLE tenant.policies (
    id uuid PRIMARY KEY,
    key_code varchar(128) NOT NULL,
    scope_type varchar(16) NOT NULL,
    tenant_id uuid,
    brand_id uuid,
    location_id uuid,
    version integer NOT NULL,
    status varchar(16) NOT NULL,
    document jsonb NOT NULL,
    document_hash char(64) NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    created_by varchar(255) NOT NULL,
    approved_by varchar(255),
    created_at timestamptz NOT NULL DEFAULT now(),
    retired_at timestamptz,
    CONSTRAINT fk_policy_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_policy_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_policy_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT uq_policy_scope_version UNIQUE (key_code, scope_type, tenant_id, brand_id, location_id, version),
    CONSTRAINT ck_policy_scope_type CHECK (
        scope_type IN ('PLATFORM', 'TENANT', 'BRAND', 'LOCATION')
    ),
    CONSTRAINT ck_policy_scope_shape CHECK (
        (scope_type = 'PLATFORM' AND tenant_id IS NULL AND brand_id IS NULL AND location_id IS NULL)
        OR (scope_type = 'TENANT' AND tenant_id IS NOT NULL AND brand_id IS NULL AND location_id IS NULL)
        OR (scope_type = 'BRAND' AND tenant_id IS NOT NULL AND brand_id IS NOT NULL AND location_id IS NULL)
        OR (scope_type = 'LOCATION' AND tenant_id IS NOT NULL AND brand_id IS NOT NULL AND location_id IS NOT NULL)
    ),
    CONSTRAINT ck_policy_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_policy_version CHECK (version > 0),
    CONSTRAINT ck_policy_document CHECK (jsonb_typeof(document) = 'object'),
    CONSTRAINT ck_policy_hash CHECK (document_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_policy_validity CHECK (valid_until IS NULL OR valid_until >= valid_from)
);

CREATE INDEX ix_policy_lookup
    ON tenant.policies (key_code, scope_type, tenant_id, brand_id, location_id, version DESC);

COMMENT ON TABLE tenant.policies IS
    'ADR 0030 versioned policy documents. Immutable once referenced by a business fact; editing creates a new version.';

-- The active version per key and scope, maintained transactionally with
-- activation. This reproduces the current-version pattern already used by
-- ordering.order_acceptance_policies rather than inventing a second one.
CREATE TABLE tenant.policy_current (
    key_code varchar(128) NOT NULL,
    scope_type varchar(16) NOT NULL,
    tenant_id uuid,
    brand_id uuid,
    location_id uuid,
    policy_id uuid NOT NULL,
    policy_version integer NOT NULL,
    activated_at timestamptz NOT NULL DEFAULT now(),
    activated_by varchar(255) NOT NULL,
    CONSTRAINT fk_policy_current_policy FOREIGN KEY (policy_id)
        REFERENCES tenant.policies (id),
    CONSTRAINT ck_policy_current_scope_type CHECK (
        scope_type IN ('PLATFORM', 'TENANT', 'BRAND', 'LOCATION')
    )
);

CREATE UNIQUE INDEX uq_policy_current_platform
    ON tenant.policy_current (key_code)
    WHERE scope_type = 'PLATFORM';

CREATE UNIQUE INDEX uq_policy_current_tenant
    ON tenant.policy_current (key_code, tenant_id)
    WHERE scope_type = 'TENANT';

CREATE UNIQUE INDEX uq_policy_current_brand
    ON tenant.policy_current (key_code, tenant_id, brand_id)
    WHERE scope_type = 'BRAND';

CREATE UNIQUE INDEX uq_policy_current_location
    ON tenant.policy_current (key_code, tenant_id, brand_id, location_id)
    WHERE scope_type = 'LOCATION';

COMMENT ON TABLE tenant.policy_current IS
    'ADR 0030 active policy version per key and scope. At most one active version per scope.';
