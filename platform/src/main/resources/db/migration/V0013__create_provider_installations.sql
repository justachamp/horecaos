-- ADR 0026: one provider integration model.
--
-- ADR 0011 introduced these tables under a POS title, and ADRs 0013, 0014, and
-- 0020 all reference provider_binding_id without it being defined anywhere. An
-- undefined shared entity referenced by three decisions is how three subtly
-- different implementations get built, each with its own answer to credential
-- rotation, scope resolution, and audit.

-- Platform-owned reference data. Tenants choose an environment; they never
-- supply a URL, which closes the server-side request forgery path at the model
-- rather than in a validator someone can forget.
CREATE TABLE integration.provider_environments (
    code varchar(64) PRIMARY KEY,
    provider_category varchar(32) NOT NULL,
    provider_type varchar(64) NOT NULL,
    base_url varchar(512) NOT NULL,
    is_production boolean NOT NULL,
    egress_allowlist varchar(1024) NOT NULL,
    notes varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_provider_environment_category CHECK (
        provider_category IN ('POS', 'PAYMENT', 'DELIVERY', 'NOTIFICATION', 'GEOCODING', 'OTHER')
    ),
    CONSTRAINT ck_provider_environment_url CHECK (base_url ~ '^https?://')
);

COMMENT ON TABLE integration.provider_environments IS
    'ADR 0026 approved provider endpoints. Platform-owned; never tenant-writable.';

CREATE TABLE integration.installations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    provider_category varchar(32) NOT NULL,
    provider_type varchar(64) NOT NULL,
    environment_code varchar(64) NOT NULL,
    display_name varchar(255) NOT NULL,
    status varchar(24) NOT NULL,
    -- A reference, never a value. ADR 0028 owns the secret itself, so rotation
    -- changes what is behind this string and never this string.
    secret_reference varchar(512),
    external_account_reference varchar(255),
    non_sensitive_config jsonb NOT NULL DEFAULT '{}'::jsonb,
    capability_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    adapter_version varchar(64),
    last_connection_check_at timestamptz,
    last_connection_status varchar(24),
    last_connection_evidence varchar(1000),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_installation_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT fk_installation_environment FOREIGN KEY (environment_code)
        REFERENCES integration.provider_environments (code),
    CONSTRAINT uq_installation_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_installation_category CHECK (
        provider_category IN ('POS', 'PAYMENT', 'DELIVERY', 'NOTIFICATION', 'GEOCODING', 'OTHER')
    ),
    CONSTRAINT ck_installation_status CHECK (
        status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'RETIRED')
    ),
    CONSTRAINT ck_installation_connection_status CHECK (
        last_connection_status IS NULL
        OR last_connection_status IN ('SUCCEEDED', 'FAILED', 'UNVERIFIED')
    ),
    CONSTRAINT ck_installation_config CHECK (jsonb_typeof(non_sensitive_config) = 'object'),
    CONSTRAINT ck_installation_capabilities CHECK (jsonb_typeof(capability_snapshot) = 'object'),
    CONSTRAINT ck_installation_version CHECK (version >= 0)
);

-- One external account per tenant and environment, where the account is
-- identifiable without being sensitive. Two installations against one account
-- would rotate independently and drift.
CREATE UNIQUE INDEX uq_installation_external_account
    ON integration.installations (tenant_id, provider_type, environment_code, external_account_reference)
    WHERE external_account_reference IS NOT NULL;

CREATE INDEX ix_installation_tenant_category
    ON integration.installations (tenant_id, provider_category, status);

COMMENT ON TABLE integration.installations IS
    'ADR 0026 tenant-owned provider accounts. A merchant account is an installation of category PAYMENT.';

CREATE TABLE integration.bindings (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    brand_id uuid,
    location_id uuid,
    status varchar(24) NOT NULL,
    priority integer NOT NULL DEFAULT 100,
    effective_from timestamptz NOT NULL DEFAULT now(),
    effective_until timestamptz,
    configuration_override jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_binding_installation FOREIGN KEY (tenant_id, installation_id)
        REFERENCES integration.installations (tenant_id, id),
    CONSTRAINT fk_binding_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_binding_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT uq_binding_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_binding_status CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED')),
    -- A binding must name a scope, and a location implies its brand. Composite
    -- ancestry keys then make a cross-tenant binding impossible rather than
    -- merely discouraged.
    CONSTRAINT ck_binding_scope CHECK (brand_id IS NOT NULL OR location_id IS NOT NULL),
    CONSTRAINT ck_binding_location_implies_brand CHECK (
        location_id IS NULL OR brand_id IS NOT NULL
    ),
    CONSTRAINT ck_binding_override CHECK (jsonb_typeof(configuration_override) = 'object'),
    CONSTRAINT ck_binding_validity CHECK (effective_until IS NULL OR effective_until > effective_from),
    CONSTRAINT ck_binding_version CHECK (version >= 0)
);

CREATE INDEX ix_binding_scope
    ON integration.bindings (tenant_id, location_id, brand_id, status);

COMMENT ON TABLE integration.bindings IS
    'ADR 0026 binding of an installation to a brand or location. provider_binding_id throughout the ADRs means this row.';

CREATE TABLE integration.binding_capabilities (
    binding_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    capability_code varchar(64) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    is_primary boolean NOT NULL DEFAULT false,
    capability_version varchar(32),
    verified_at timestamptz,
    -- The binding's narrowest scope, copied here so "one primary per scope and
    -- capability" can be a unique index rather than application logic. Which
    -- provider handles a capability must not depend on row order, and the scope
    -- lives on the binding, so enforcing it at this level requires the copy.
    -- integration.bindings remains the authority; a trigger keeps this in step.
    scope_key uuid,
    PRIMARY KEY (binding_id, capability_code),
    CONSTRAINT fk_binding_capability_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id) ON DELETE CASCADE
);

COMMENT ON TABLE integration.binding_capabilities IS
    'ADR 0026 capabilities a binding actually provides, verified rather than assumed.';

CREATE TABLE integration.provider_entity_mappings (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    binding_id uuid NOT NULL,
    entity_type varchar(64) NOT NULL,
    qoida_entity_id uuid NOT NULL,
    external_entity_id varchar(255) NOT NULL,
    external_parent_id varchar(255),
    status varchar(24) NOT NULL,
    mapping_source varchar(32) NOT NULL,
    last_seen_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_mapping_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id),
    -- Both directions unique: an ambiguous mapping is a conflict to resolve,
    -- never a last-write-wins race.
    CONSTRAINT uq_mapping_external UNIQUE (binding_id, entity_type, external_entity_id),
    CONSTRAINT uq_mapping_qoida UNIQUE (binding_id, entity_type, qoida_entity_id),
    CONSTRAINT ck_mapping_status CHECK (status IN ('PROPOSED', 'ACTIVE', 'CONFLICTED', 'RETIRED')),
    CONSTRAINT ck_mapping_source CHECK (mapping_source IN ('DISCOVERED', 'OPERATOR', 'IMPORTED'))
);

CREATE INDEX ix_mapping_lookup
    ON integration.provider_entity_mappings (tenant_id, entity_type, qoida_entity_id, status);

COMMENT ON TABLE integration.provider_entity_mappings IS
    'ADR 0026 single store of external identifier mappings. Catalog reads these through a port and keeps no copy.';

-- Only one primary binding per scope and capability.
CREATE UNIQUE INDEX uq_binding_capability_primary
    ON integration.binding_capabilities (tenant_id, scope_key, capability_code)
    WHERE is_primary AND enabled;

-- Keeps the denormalised scope honest. Without this, a capability row could
-- claim a scope its binding does not have, and the uniqueness above would be
-- enforcing a fiction.
CREATE OR REPLACE FUNCTION integration.binding_capability_scope()
RETURNS trigger AS $$
DECLARE
    binding_scope uuid;
BEGIN
    SELECT coalesce(b.location_id, b.brand_id)
      INTO binding_scope
      FROM integration.bindings b
     WHERE b.id = NEW.binding_id AND b.tenant_id = NEW.tenant_id;

    IF binding_scope IS NULL THEN
        RAISE EXCEPTION 'Binding % has no scope', NEW.binding_id;
    END IF;

    NEW.scope_key := binding_scope;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_binding_capability_scope
    BEFORE INSERT OR UPDATE ON integration.binding_capabilities
    FOR EACH ROW EXECUTE FUNCTION integration.binding_capability_scope();
