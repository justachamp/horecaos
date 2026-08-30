-- ADR 0050: approval policies must identify the brand or location they govern.
--
-- V0007 stored only scope_type. A BRAND or LOCATION policy therefore matched
-- every resource of that type in its tenant; the author could name no brand or
-- location, and resolution could not distinguish two branches. Add the same
-- ancestry columns ADR 0030 uses for tenant-scoped policies.

ALTER TABLE audit.approval_policies
    ADD COLUMN brand_id uuid,
    ADD COLUMN location_id uuid,
    ADD COLUMN legacy_scope_wide boolean NOT NULL DEFAULT false;

-- Existing rows cannot be assigned to a resource truthfully: the old schema did
-- not retain one. Preserve their old all-brands/all-locations behaviour, label
-- it visibly as legacy, and make every newly authored row exact. A targeted row
-- wins over a legacy fallback at the same scope level in JdbcApprovalService.
UPDATE audit.approval_policies
   SET legacy_scope_wide = true
 WHERE scope_type IN ('BRAND', 'LOCATION');

ALTER TABLE audit.approval_policies
    ADD CONSTRAINT fk_approval_policy_brand
        FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    ADD CONSTRAINT fk_approval_policy_location
        FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    ADD CONSTRAINT ck_approval_policy_scope_shape CHECK (
        (scope_type = 'PLATFORM'
            AND tenant_id IS NULL AND brand_id IS NULL AND location_id IS NULL
            AND NOT legacy_scope_wide)
        OR (scope_type = 'TENANT'
            AND tenant_id IS NOT NULL AND brand_id IS NULL AND location_id IS NULL
            AND NOT legacy_scope_wide)
        OR (scope_type = 'BRAND'
            AND tenant_id IS NOT NULL AND brand_id IS NOT NULL AND location_id IS NULL
            AND NOT legacy_scope_wide)
        OR (scope_type = 'LOCATION'
            AND tenant_id IS NOT NULL AND brand_id IS NOT NULL AND location_id IS NOT NULL
            AND NOT legacy_scope_wide)
        OR (legacy_scope_wide AND scope_type IN ('BRAND', 'LOCATION')
            AND tenant_id IS NOT NULL AND brand_id IS NULL AND location_id IS NULL)
    );

ALTER TABLE audit.approval_policies
    DROP CONSTRAINT uq_approval_policy_version;

CREATE UNIQUE INDEX uq_approval_policy_platform_version
    ON audit.approval_policies (action_code, version)
    WHERE scope_type = 'PLATFORM';

CREATE UNIQUE INDEX uq_approval_policy_tenant_version
    ON audit.approval_policies (action_code, tenant_id, version)
    WHERE scope_type = 'TENANT';

CREATE UNIQUE INDEX uq_approval_policy_brand_version
    ON audit.approval_policies (action_code, tenant_id, brand_id, version)
    WHERE scope_type = 'BRAND' AND NOT legacy_scope_wide;

CREATE UNIQUE INDEX uq_approval_policy_location_version
    ON audit.approval_policies (action_code, tenant_id, brand_id, location_id, version)
    WHERE scope_type = 'LOCATION' AND NOT legacy_scope_wide;

CREATE UNIQUE INDEX uq_approval_policy_legacy_scope_wide_version
    ON audit.approval_policies (action_code, tenant_id, scope_type, version)
    WHERE legacy_scope_wide;

CREATE INDEX ix_approval_policy_resolution
    ON audit.approval_policies (
        action_code, scope_type, tenant_id, brand_id, location_id, valid_from DESC, version DESC);

COMMENT ON COLUMN audit.approval_policies.legacy_scope_wide IS
    'True only for pre-V0082 brand/location rows that had no resource identifier. Replace with exact scoped versions; new authoring never writes true.';
