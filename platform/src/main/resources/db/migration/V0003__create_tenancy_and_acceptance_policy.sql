CREATE TABLE tenant.tenants (
    id uuid PRIMARY KEY,
    slug varchar(63) NOT NULL,
    legal_name varchar(200) NOT NULL,
    display_name varchar(200) NOT NULL,
    default_currency char(3) NOT NULL,
    default_timezone varchar(63) NOT NULL,
    keycloak_organization_id varchar(64),
    status varchar(24) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenants_slug UNIQUE (slug),
    CONSTRAINT uq_tenants_keycloak_organization UNIQUE (keycloak_organization_id),
    CONSTRAINT uq_tenants_scope UNIQUE (id, slug),
    CONSTRAINT ck_tenants_slug CHECK (slug ~ '^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT ck_tenants_currency CHECK (default_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_tenants_status CHECK (status IN ('PROVISIONING', 'ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    CONSTRAINT ck_tenants_version CHECK (version >= 0)
);

CREATE TABLE tenant.brands (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    code varchar(32) NOT NULL,
    slug varchar(63) NOT NULL,
    display_name varchar(200) NOT NULL,
    status varchar(24) NOT NULL,
    legacy_company_id uuid,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_brands_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT uq_brands_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_brands_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_brands_tenant_slug UNIQUE (tenant_id, slug),
    CONSTRAINT uq_brands_legacy_company UNIQUE (legacy_company_id),
    CONSTRAINT ck_brands_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_brands_slug CHECK (slug ~ '^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT ck_brands_status CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    CONSTRAINT ck_brands_version CHECK (version >= 0)
);

CREATE INDEX ix_brands_tenant_id ON tenant.brands (tenant_id);

CREATE TABLE tenant.locations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    code varchar(32) NOT NULL,
    slug varchar(63) NOT NULL,
    display_name varchar(200) NOT NULL,
    timezone varchar(63) NOT NULL,
    status varchar(24) NOT NULL,
    legacy_vendor_id uuid,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_locations_brand_scope FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT uq_locations_tenant_brand_id UNIQUE (tenant_id, brand_id, id),
    CONSTRAINT uq_locations_brand_code UNIQUE (tenant_id, brand_id, code),
    CONSTRAINT uq_locations_brand_slug UNIQUE (tenant_id, brand_id, slug),
    CONSTRAINT uq_locations_legacy_vendor UNIQUE (legacy_vendor_id),
    CONSTRAINT ck_locations_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_locations_slug CHECK (slug ~ '^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT ck_locations_status CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    CONSTRAINT ck_locations_version CHECK (version >= 0)
);

CREATE INDEX ix_locations_tenant_brand ON tenant.locations (tenant_id, brand_id);

CREATE TABLE tenant.customer_identity_policies (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    version integer NOT NULL,
    identity_mode varchar(24) NOT NULL,
    effective_from timestamptz NOT NULL,
    superseded_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_customer_identity_policy_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT uq_customer_identity_policy_version UNIQUE (tenant_id, version),
    CONSTRAINT ck_customer_identity_policy_version CHECK (version > 0),
    CONSTRAINT ck_customer_identity_policy_mode CHECK (
        identity_mode IN ('TENANT_SHARED', 'BRAND_ISOLATED')
    ),
    CONSTRAINT ck_customer_identity_policy_range CHECK (
        superseded_at IS NULL OR superseded_at >= effective_from
    )
);

CREATE UNIQUE INDEX uq_customer_identity_policy_current
    ON tenant.customer_identity_policies (tenant_id)
    WHERE superseded_at IS NULL;

CREATE TABLE ordering.order_acceptance_policies (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid,
    location_id uuid,
    version integer NOT NULL,
    acceptance_mode varchar(32) NOT NULL,
    approval_channel varchar(32) NOT NULL,
    approval_timeout_seconds integer NOT NULL,
    timeout_action varchar(24) NOT NULL,
    rejection_reason_required boolean NOT NULL DEFAULT true,
    notify_customer_while_pending boolean NOT NULL DEFAULT true,
    effective_from timestamptz NOT NULL,
    superseded_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_order_acceptance_policy_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_order_acceptance_policy_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_order_acceptance_policy_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT ck_order_acceptance_policy_version CHECK (version > 0),
    CONSTRAINT ck_order_acceptance_policy_scope CHECK (
        location_id IS NULL OR brand_id IS NOT NULL
    ),
    CONSTRAINT ck_order_acceptance_policy_mode CHECK (
        acceptance_mode IN ('AUTO_CONFIRM', 'RESTAURANT_APPROVAL')
    ),
    CONSTRAINT ck_order_acceptance_policy_channel CHECK (
        approval_channel IN ('NONE', 'QOIDA_OPERATIONS', 'POS', 'EITHER')
    ),
    CONSTRAINT ck_order_acceptance_policy_timeout_action CHECK (
        timeout_action IN ('AUTO_REJECT', 'AUTO_CONFIRM')
    ),
    CONSTRAINT ck_order_acceptance_policy_timeout CHECK (
        (acceptance_mode = 'AUTO_CONFIRM'
            AND approval_channel = 'NONE'
            AND approval_timeout_seconds = 0)
        OR
        (acceptance_mode = 'RESTAURANT_APPROVAL'
            AND approval_channel <> 'NONE'
            AND approval_timeout_seconds BETWEEN 30 AND 1800)
    ),
    CONSTRAINT ck_order_acceptance_policy_range CHECK (
        superseded_at IS NULL OR superseded_at >= effective_from
    )
);

CREATE UNIQUE INDEX uq_order_acceptance_policy_tenant_version
    ON ordering.order_acceptance_policies (tenant_id, version)
    WHERE brand_id IS NULL AND location_id IS NULL;

CREATE UNIQUE INDEX uq_order_acceptance_policy_brand_version
    ON ordering.order_acceptance_policies (tenant_id, brand_id, version)
    WHERE brand_id IS NOT NULL AND location_id IS NULL;

CREATE UNIQUE INDEX uq_order_acceptance_policy_location_version
    ON ordering.order_acceptance_policies (tenant_id, brand_id, location_id, version)
    WHERE location_id IS NOT NULL;

CREATE UNIQUE INDEX uq_order_acceptance_policy_current_tenant
    ON ordering.order_acceptance_policies (tenant_id)
    WHERE brand_id IS NULL AND location_id IS NULL AND superseded_at IS NULL;

CREATE UNIQUE INDEX uq_order_acceptance_policy_current_brand
    ON ordering.order_acceptance_policies (tenant_id, brand_id)
    WHERE brand_id IS NOT NULL AND location_id IS NULL AND superseded_at IS NULL;

CREATE UNIQUE INDEX uq_order_acceptance_policy_current_location
    ON ordering.order_acceptance_policies (tenant_id, brand_id, location_id)
    WHERE location_id IS NOT NULL AND superseded_at IS NULL;

COMMENT ON TABLE tenant.tenants IS 'SaaS legal/commercial customer and isolation boundary';
COMMENT ON TABLE tenant.brands IS 'Customer-facing brand owned by exactly one tenant';
COMMENT ON TABLE tenant.locations IS 'Physical or virtual fulfillment point owned by exactly one brand';
COMMENT ON TABLE tenant.customer_identity_policies IS 'Versioned cross-brand customer identity policy';
COMMENT ON TABLE ordering.order_acceptance_policies IS 'Versioned tenant, brand, or location order-acceptance configuration';
