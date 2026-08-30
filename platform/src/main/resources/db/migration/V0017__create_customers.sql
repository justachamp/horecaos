-- ADR 0015: customer accounts, cross-brand identity, and consent.
--
-- Two decisions drive this schema.
--
-- First, Keycloak authenticates but does not own the commercial identity. A JWT
-- says who signed in; it says nothing about their addresses, consent, or order
-- history. Those need a durable record that survives an identity-provider change.
--
-- Second, personal data is encrypted at the column and looked up by a separate
-- keyed hash (ADR 0029). Deterministic encryption would let anyone with read
-- access confirm whether a given phone number is a customer, which for a small
-- domain like Uzbek mobile numbers is equivalent to leaking the list.

CREATE SCHEMA IF NOT EXISTS customer;

-- Which partitioning a tenant uses. On the tenant rather than in a policy table
-- because it is a structural property of the tenant's identity model, not a
-- setting that varies by brand or location: a tenant cannot be half-isolated.
ALTER TABLE tenant.tenants
    ADD COLUMN customer_identity_policy varchar(24) NOT NULL DEFAULT 'TENANT_SHARED';

ALTER TABLE tenant.tenants
    ADD CONSTRAINT ck_tenant_identity_policy CHECK (
        customer_identity_policy IN ('TENANT_SHARED', 'BRAND_ISOLATED')
    );

COMMENT ON COLUMN tenant.tenants.customer_identity_policy IS
    'ADR 0015. Changing this is a governed migration with duplicate discovery and approval, never an in-place toggle: flipping it silently would merge or split real people''s accounts.';

-- The durable commercial identity.
CREATE TABLE customer.customer_accounts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- Null under TENANT_SHARED, set under BRAND_ISOLATED. Stored on the row
    -- rather than derived at read time, because the partition an account was
    -- created in must not silently change when a tenant's policy does.
    identity_partition_brand_id uuid,

    status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    display_name varchar(200),
    preferred_locale varchar(16),
    preferred_timezone varchar(64),

    -- Which policy version created this account, so a later policy change is a
    -- governed migration with a known starting point rather than a reinterpretation.
    identity_policy_version integer NOT NULL DEFAULT 1,

    -- Set when this account was merged away. The row stays: order history points
    -- at it, and deleting it would orphan immutable snapshots.
    merged_into_account_id uuid,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    anonymized_at timestamptz,

    CONSTRAINT ck_customer_status CHECK (
        status IN ('ACTIVE', 'SUSPENDED', 'MERGED', 'ANONYMIZED', 'CLOSED')
    ),
    CONSTRAINT ck_customer_merged CHECK (
        (status <> 'MERGED') OR (merged_into_account_id IS NOT NULL)
    ),
    -- A merge target inside the same tenant only. A cross-tenant redirect would
    -- be a data leak dressed up as a merge.
    CONSTRAINT fk_customer_merged_into FOREIGN KEY (merged_into_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT uq_customer_identity UNIQUE (id, tenant_id)
);

-- Links a Keycloak subject to an account.
CREATE TABLE customer.principal_links (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    identity_partition_brand_id uuid,
    customer_account_id uuid NOT NULL,

    issuer varchar(255) NOT NULL,
    subject varchar(255) NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    linked_at timestamptz NOT NULL DEFAULT now(),
    unlinked_at timestamptz,

    CONSTRAINT ck_principal_link_status CHECK (status IN ('ACTIVE', 'UNLINKED')),
    CONSTRAINT ck_principal_link_unlinked CHECK (
        (status <> 'UNLINKED') OR (unlinked_at IS NOT NULL)
    ),
    CONSTRAINT fk_principal_link_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id)
);

-- Two partial indexes rather than one, because a null brand does not compare
-- equal to anything in a unique index. Without the split, TENANT_SHARED accounts
-- would have no uniqueness at all and one subject could silently acquire several.
CREATE UNIQUE INDEX ux_principal_link_tenant_shared
    ON customer.principal_links (issuer, subject, tenant_id)
    WHERE status = 'ACTIVE' AND identity_partition_brand_id IS NULL;

CREATE UNIQUE INDEX ux_principal_link_brand_isolated
    ON customer.principal_links (issuer, subject, tenant_id, identity_partition_brand_id)
    WHERE status = 'ACTIVE' AND identity_partition_brand_id IS NOT NULL;

CREATE INDEX ix_principal_links_account
    ON customer.principal_links (customer_account_id) WHERE status = 'ACTIVE';

-- Brand-specific preferences and history for one account.
CREATE TABLE customer.brand_profiles (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    loyalty_reference varchar(128),
    marketing_profile_version integer NOT NULL DEFAULT 1,
    first_order_at timestamptz,
    last_order_at timestamptz,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_brand_profile_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT fk_brand_profile_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT uq_brand_profile UNIQUE (tenant_id, brand_id, customer_account_id)
);

-- Phone numbers and email addresses.
CREATE TABLE customer.contact_points (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,
    type varchar(16) NOT NULL,

    -- Keyed, per-tenant, deterministic. This is what equality search uses.
    -- Keyed rather than a plain digest so an attacker holding the table cannot
    -- confirm a number by hashing a guess; per-tenant so the same number in two
    -- tenants does not produce the same value.
    normalized_hash varchar(64) NOT NULL,

    -- Randomized AEAD, bound to this row. A ciphertext copied to another row
    -- fails to decrypt rather than revealing the wrong person's number.
    encrypted_value text NOT NULL,

    verification_status varchar(16) NOT NULL DEFAULT 'UNVERIFIED',
    verified_at timestamptz,
    is_primary boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_contact_type CHECK (type IN ('PHONE', 'EMAIL')),
    CONSTRAINT ck_contact_verification CHECK (
        verification_status IN ('UNVERIFIED', 'PENDING', 'VERIFIED', 'FAILED')
    ),
    CONSTRAINT ck_contact_verified_at CHECK (
        (verification_status <> 'VERIFIED') OR (verified_at IS NOT NULL)
    ),
    CONSTRAINT fk_contact_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id)
);

-- Deliberately NOT unique across the tenant. Two people genuinely share a
-- household phone, and a recycled number changes owner; a unique constraint here
-- would silently merge two customers into one. ADR 0015 makes contact a method,
-- not an identity key.
CREATE INDEX ix_contact_points_lookup
    ON customer.contact_points (tenant_id, type, normalized_hash);

CREATE UNIQUE INDEX ux_contact_point_primary
    ON customer.contact_points (customer_account_id, type)
    WHERE is_primary;

CREATE TABLE customer.addresses (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,
    label varchar(64),

    -- The whole address as one encrypted document. Splitting it into encrypted
    -- columns would still leak structure, and no query needs a street name.
    encrypted_fields text NOT NULL,
    delivery_instructions_encrypted text,

    -- Left in clear: a delivery cannot be routed without them, and a coordinate
    -- alone identifies a building rather than a person.
    latitude double precision,
    longitude double precision,

    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_address_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_address_coordinates CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
    ),
    CONSTRAINT fk_address_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id)
);

CREATE INDEX ix_addresses_account
    ON customer.addresses (customer_account_id) WHERE status = 'ACTIVE';

-- Append-only. Current consent is a query over the latest decision, never an
-- updated row: proving what someone consented to on a given date is the entire
-- point, and an UPDATE destroys exactly that evidence.
CREATE TABLE customer.consent_decisions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,
    brand_id uuid,
    purpose varchar(64) NOT NULL,
    channel varchar(32),
    decision varchar(16) NOT NULL,
    policy_version varchar(32) NOT NULL,
    source varchar(32) NOT NULL,
    evidence_reference varchar(255),
    decided_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_consent_decision CHECK (decision IN ('GRANTED', 'WITHDRAWN')),
    CONSTRAINT ck_consent_source CHECK (
        source IN ('STOREFRONT', 'SUPPORT_AGENT', 'IMPORT', 'MIGRATION', 'API')
    ),
    CONSTRAINT fk_consent_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id)
);

-- Serves the "what is the current decision" query: newest first, per scope.
CREATE INDEX ix_consent_current
    ON customer.consent_decisions (customer_account_id, purpose, brand_id, channel, decided_at DESC);

GRANT USAGE ON SCHEMA customer TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON customer.customer_accounts TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON customer.principal_links TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON customer.brand_profiles TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON customer.contact_points TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON customer.addresses TO horecaos_application;

-- Insert and read only. No UPDATE, no DELETE: a consent record that can be
-- edited is not evidence of anything.
GRANT SELECT, INSERT ON customer.consent_decisions TO horecaos_application;
