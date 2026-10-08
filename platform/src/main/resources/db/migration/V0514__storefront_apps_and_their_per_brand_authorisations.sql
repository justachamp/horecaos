-- ADR 0070: a storefront is a client of a published contract, so the platform has
-- to be able to tell one storefront from another.
--
-- Until now a storefront identified itself by tenantId and brandId in its own
-- config.json and by nothing else, which means the platform could not tell a
-- vendor's storefront from a script, could not revoke one, and could not say
-- which one an order came from. Two tables close that, and the whole of what
-- they decide is in their comments below.
--
-- ---------------------------------------------------------------------------
-- Why two tables and not an ADR 0026 installation
-- ---------------------------------------------------------------------------
--
-- ADR 0026's integration.installations is a TENANT's account at a provider: a
-- tenant row with a category from a closed list. A storefront app is the other
-- way round -- registered once by the platform, then authorised by many tenants
-- -- so its registry is platform-owned (no tenant_id, like
-- integration.provider_environments) and what a tenant owns is only its
-- authorisation of it, which is the same install/suspend/revoke lifecycle an
-- installation has, on its own rows. The secret half does use ADR 0026/0028's
-- machinery unchanged: a confidential client holds a secret REFERENCE here and
-- the value lives behind it in the secrets manager.

CREATE SCHEMA IF NOT EXISTS storefront_app;

GRANT USAGE ON SCHEMA storefront_app TO horecaos_application;

CREATE TABLE storefront_app.apps (
    id uuid PRIMARY KEY,
    -- What a tenant sees when it chooses a storefront, and what the control
    -- plane lists. Unique per vendor, case-insensitively, below.
    name varchar(120) NOT NULL,
    vendor varchar(160) NOT NULL,
    -- PUBLIC: a browser-only app. It holds no secret -- a browser keeps none --
    -- so it is attributable (an app id) and revocable, and the platform checks
    -- the request's Origin against origin_allowlist. It is NOT authenticated in a
    -- sense a browser cannot deliver, and nothing in the platform pretends it is.
    -- CONFIDENTIAL: a server-backed app. It presents a secret, resolved through
    -- secret_reference, and that earns it what ADR 0070 says it earns.
    client_type varchar(16) NOT NULL,
    -- Our own storefronts register too (ADR 0070, rollout stage two).
    first_party boolean NOT NULL DEFAULT false,
    -- Exact origins, scheme://host[:port], lowercase, no path. Required for a
    -- PUBLIC client; optional for a CONFIDENTIAL one (a server has no Origin).
    origin_allowlist text[] NOT NULL DEFAULT '{}',
    -- A reference, never a value (ADR 0028). Present exactly for a CONFIDENTIAL
    -- client, and replaced by a fresh reference on every rotation.
    secret_reference varchar(512),
    secret_rotated_at timestamptz,
    -- ACTIVE serves. SUSPENDED refuses every tenant's requests at once and can be
    -- lifted. RETIRED is final: the row stays so an order that names the app
    -- still resolves, but the app can never serve again.
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    -- The conformance suite's verdict against the contract's major version
    -- (ADR 0070). The suite itself is not part of this migration; this is where
    -- its result lives. A PASSED result expires when the contract's major
    -- version moves, which is derived at read time from conformance_contract_version
    -- rather than stored as a status that nothing would ever flip.
    conformance_status varchar(16) NOT NULL DEFAULT 'NOT_RUN',
    conformance_contract_version varchar(16),
    conformance_recorded_at timestamptz,
    conformance_note varchar(1000),
    registered_by varchar(255) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_storefront_app_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_storefront_app_vendor CHECK (length(btrim(vendor)) > 0),
    CONSTRAINT ck_storefront_app_client_type CHECK (client_type IN ('PUBLIC', 'CONFIDENTIAL')),
    CONSTRAINT ck_storefront_app_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT ck_storefront_app_conformance_status CHECK (conformance_status IN ('NOT_RUN', 'PASSED', 'FAILED')),
    CONSTRAINT ck_storefront_app_origin_count CHECK (cardinality(origin_allowlist) <= 20),
    -- The two client types have different shapes and the database holds them to
    -- it: a public client with no origin could never pass the check that is its
    -- only protection, and a public client holding a secret reference is a
    -- bundled secret being called one.
    CONSTRAINT ck_storefront_app_public_shape CHECK (
        client_type <> 'PUBLIC'
        OR (cardinality(origin_allowlist) >= 1 AND secret_reference IS NULL AND secret_rotated_at IS NULL)
    ),
    CONSTRAINT ck_storefront_app_confidential_shape CHECK (
        client_type <> 'CONFIDENTIAL' OR (secret_reference IS NOT NULL AND secret_rotated_at IS NOT NULL)
    ),
    CONSTRAINT ck_storefront_app_conformance_shape CHECK (
        (conformance_status = 'NOT_RUN') = (conformance_recorded_at IS NULL)
        AND (conformance_status = 'NOT_RUN') = (conformance_contract_version IS NULL)
    ),
    CONSTRAINT ck_storefront_app_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_storefront_app_vendor_name ON storefront_app.apps (lower(vendor), lower(name));

COMMENT ON TABLE storefront_app.apps IS
    'ADR 0070 platform-owned registry of storefront apps. No tenant_id: an app is registered once and authorised per tenant brand in storefront_app.authorisations.';

CREATE TABLE storefront_app.authorisations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    app_id uuid NOT NULL,
    -- ACTIVE: the brand lets this app serve it. REVOKED: it does not, effective on
    -- the very next request -- the identity check reads this row every time and
    -- nothing caches it (ADR 0033: no correctness decision reads cache state).
    status varchar(16) NOT NULL,
    granted_by varchar(255) NOT NULL,
    granted_at timestamptz NOT NULL,
    revoked_by varchar(255),
    revoked_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_storefront_authorisation_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_storefront_authorisation_app FOREIGN KEY (app_id)
        REFERENCES storefront_app.apps (id),
    -- One row per brand and app. Authorising again after a revocation reuses it
    -- (the history is the audit trail, ADR 0027), so "has this brand ever
    -- authorised this app" and "is it authorised now" are one lookup.
    CONSTRAINT uq_storefront_authorisation UNIQUE (tenant_id, brand_id, app_id),
    CONSTRAINT ck_storefront_authorisation_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_storefront_authorisation_shape CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL AND revoked_by IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_by IS NOT NULL)
    ),
    CONSTRAINT ck_storefront_authorisation_version CHECK (version >= 0)
);

-- The request-time question for a path that names a tenant but no brand: does
-- this tenant have the app authorised anywhere.
CREATE INDEX ix_storefront_authorisation_tenant_app
    ON storefront_app.authorisations (tenant_id, app_id) WHERE status = 'ACTIVE';

CREATE INDEX ix_storefront_authorisation_app ON storefront_app.authorisations (app_id, status);

COMMENT ON TABLE storefront_app.authorisations IS
    'ADR 0070 a brand''s authorisation of one storefront app. A tenant act, capability-gated and audited; revoking takes effect on the next request.';

-- No DELETE: an authorisation is revoked, not removed, and an app is retired,
-- not deleted -- the record that either once existed outlives its authority.
GRANT SELECT, INSERT, UPDATE ON storefront_app.apps TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON storefront_app.authorisations TO horecaos_application;
