-- ADR 0079: the kitchen display device principal and its enrolment.
--
-- ADR 0025 built one shape of principal -- a Keycloak subject that traces to a
-- person -- and ADR 0049 added three non-staff shapes that still trace to one
-- accountable person or counterparty. A kitchen screen is none of those: it is
-- provisioned as its own confidential Keycloak service-account client, and its
-- service-account subject is what iam.grants.principal_subject stores for it,
-- exactly the column every staff grant already uses. Nothing about
-- authorization changes -- AuthorizationService, JdbcAuthorizationService,
-- ResourceScopeVerifier and @RequiresCapability run the identical check they
-- already run for a person. This migration only gives that subject somewhere
-- to come from and somewhere to be revoked.

-- iam.grants.id alone is already a unique primary key, so this adds no new
-- uniqueness -- it exists only so a later foreign key can be tenant-scoped
-- rather than referencing (id) alone, which repo_hygiene.py's cross-tenant
-- check refuses: a tenant-scoped row must not be able to point at another
-- tenant's row of the same id. The V0127 precedent already alters iam.grants
-- in a migration after the one that created it.
ALTER TABLE iam.grants ADD CONSTRAINT uq_grant_tenant_id UNIQUE (tenant_id, id);

CREATE TABLE iam.device_principals (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    -- Closed, like kitchen.stations.role: free text means one deployment spells
    -- it "kds" and another "KDS-1", and a future reader cannot tell them apart.
    -- Only one value exists today; ADR 0079 defers VDU and EXPO to ADR 0041's
    -- own rollout step 4 rather than inventing their shape now.
    device_class varchar(32) NOT NULL,
    display_name varchar(120) NOT NULL,
    -- Keycloak's own internal client id (a UUID Keycloak mints, not this
    -- platform), needed to call the Admin API again to disable or inspect the
    -- client. Separate from client_id, the OAuth identifier the device itself
    -- presents at the token endpoint.
    keycloak_client_internal_id varchar(64) NOT NULL,
    keycloak_client_id varchar(128) NOT NULL,
    -- The service-account user's Keycloak subject. This is the exact value
    -- iam.grants.principal_subject carries for this device -- there is no
    -- second identity column anywhere for a device to be authorized under.
    principal_subject varchar(255) NOT NULL,
    -- The iam.grants row this device's PlatformRole.KITCHEN_DEVICE grant
    -- lives in, so revoking a device revokes the exact grant it was given
    -- rather than searching for one by subject.
    grant_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    enrolled_by varchar(255) NOT NULL,
    enrolled_at timestamptz NOT NULL DEFAULT now(),
    -- The V0127 pairing: a revoked row always carries all three, an active one
    -- carries none.
    revoked_by varchar(255),
    revoked_at timestamptz,
    revoked_reason varchar(500),
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_device_principal_class CHECK (device_class IN ('KITCHEN_KDS')),
    CONSTRAINT ck_device_principal_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_device_principal_revocation CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL AND revoked_by IS NULL AND revoked_reason IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_by IS NOT NULL AND revoked_reason IS NOT NULL)
    ),
    CONSTRAINT ck_device_principal_version CHECK (version >= 1),
    -- ADR 0079: a device is LOCATION-scoped, always. This is the ancestry proof
    -- ResourceScopeVerifier's application-level check is backed by at the
    -- database boundary -- the identical shape kitchen.stations already uses,
    -- so a device can never be inserted naming a brand or tenant it does not
    -- actually belong to.
    CONSTRAINT fk_device_principal_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    -- Tenant-scoped, against the uq_grant_tenant_id constraint just added:
    -- one tenant's device row can never reference another tenant's grant.
    CONSTRAINT fk_device_principal_grant FOREIGN KEY (tenant_id, grant_id) REFERENCES iam.grants (tenant_id, id),
    CONSTRAINT uq_device_principal_client_id UNIQUE (keycloak_client_id),
    CONSTRAINT uq_device_principal_subject UNIQUE (principal_subject),
    -- Exists so iam.device_enrolment_requests can reference a device
    -- tenant-scoped rather than by (id) alone, the same reasoning as
    -- uq_grant_tenant_id above.
    CONSTRAINT uq_device_principal_tenant_id UNIQUE (tenant_id, id)
);

CREATE INDEX ix_device_principal_location ON iam.device_principals (tenant_id, location_id) WHERE status = 'ACTIVE';

COMMENT ON TABLE iam.device_principals IS
    'ADR 0079. A non-human principal enrolled to exactly one location. principal_subject is the exact value iam.grants.principal_subject stores for this device; authorization is the ordinary ADR 0025 grant path, never a second code path.';

GRANT SELECT, INSERT, UPDATE ON iam.device_principals TO horecaos_application;

-- ---------------------------------------------------------------------------
-- Enrolment requests
-- ---------------------------------------------------------------------------
--
-- A pairing-code handshake modelled on RFC 8628's UX and adapted for the
-- reason ADR 0079's Alternatives table gives: the raw device grant
-- authenticates the token as the approving person, which is the exact
-- shared-credential failure this record exists to end. No secret value is
-- stored here at any point -- device_code is hashed like ADR 0040's
-- expected_value_hash, and the Keycloak client secret this table's row
-- eventually authorizes handing out is never written to a database at all.

CREATE TABLE iam.device_enrolment_requests (
    id uuid PRIMARY KEY,
    -- Peppered hash of the long opaque value the device alone holds until it
    -- claims its credential. Never the raw value.
    device_code_hash varchar(128) NOT NULL,
    -- Short and human-typeable, read off the device's own screen by whoever is
    -- standing in front of it. Spent (leaves PENDING) the moment it is
    -- approved, so an overheard code is worthless once enrolment completes.
    user_code varchar(16) NOT NULL,
    requested_class varchar(32) NOT NULL,
    requested_label varchar(120),
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    -- Unknown until approval; an unenrolled device names no tenant.
    tenant_id uuid,
    brand_id uuid,
    location_id uuid,
    device_principal_id uuid,
    approved_by varchar(255),
    approved_at timestamptz,
    -- When the credential was actually handed out. Distinct from approved_at:
    -- a device that never polls again is approved but never claimed, and that
    -- gap is itself worth being able to see.
    claimed_at timestamptz,
    denied_reason varchar(500),
    expires_at timestamptz NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_device_enrolment_class CHECK (requested_class IN ('KITCHEN_KDS')),
    CONSTRAINT ck_device_enrolment_status
        CHECK (status IN ('PENDING', 'APPROVED', 'CLAIMED', 'DENIED', 'EXPIRED')),
    CONSTRAINT ck_device_enrolment_approval CHECK (
        (status IN ('PENDING', 'DENIED', 'EXPIRED')
            AND tenant_id IS NULL AND brand_id IS NULL AND location_id IS NULL
            AND device_principal_id IS NULL AND approved_by IS NULL AND approved_at IS NULL)
        OR (status IN ('APPROVED', 'CLAIMED')
            AND tenant_id IS NOT NULL AND brand_id IS NOT NULL AND location_id IS NOT NULL
            AND device_principal_id IS NOT NULL AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
    ),
    CONSTRAINT ck_device_enrolment_claim CHECK (status = 'CLAIMED' OR claimed_at IS NULL),
    CONSTRAINT ck_device_enrolment_version CHECK (version >= 1),
    -- Tenant-scoped against uq_device_principal_tenant_id: MATCH SIMPLE means
    -- this is unenforced while both columns are NULL (every status but
    -- APPROVED/CLAIMED, per ck_device_enrolment_approval above), and checked
    -- the moment both are populated together.
    CONSTRAINT fk_device_enrolment_principal FOREIGN KEY (tenant_id, device_principal_id)
        REFERENCES iam.device_principals (tenant_id, id),
    CONSTRAINT fk_device_enrolment_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id)
);

CREATE UNIQUE INDEX uq_device_enrolment_code_hash ON iam.device_enrolment_requests (device_code_hash);
-- Only one row may be PENDING under a given user code at a time; once
-- approved or expired, the short code may be reused by a later enrolment
-- without a collision, because the row that spent it is no longer PENDING.
CREATE UNIQUE INDEX uq_device_enrolment_user_code_pending
    ON iam.device_enrolment_requests (user_code) WHERE status = 'PENDING';
CREATE INDEX ix_device_enrolment_pending_expiry
    ON iam.device_enrolment_requests (expires_at) WHERE status = 'PENDING';

COMMENT ON TABLE iam.device_enrolment_requests IS
    'ADR 0079. The pairing-code enrolment handshake. No secret value is ever stored: device_code is hashed, and the Keycloak client secret this table authorizes handing out once is never written here.';

GRANT SELECT, INSERT, UPDATE ON iam.device_enrolment_requests TO horecaos_application;
