-- ADR 0139: a branch's contact persons (gap map row 9.2b).
--
-- `tenant.locations.contact_phone` (V0023) is the published line customers and
-- couriers see, stored in clear on purpose, and stays exactly as it is and
-- stays the only number they see. This is a different thing: who, by name and
-- role, a colleague or the head office calls about the branch -- the manager,
-- the landlord, the security desk, the maintenance contractor.
--
-- A contact is either a colleague (a staff member id, no copy of anyone's
-- phone, so the number cannot drift from the person's own record) or an outside
-- person (a protected name and phone). Exactly one of the two shapes; the CHECK
-- states it as an equality so the mixed case is not reachable through the
-- three-valued hole.
--
-- Two tables with real foreign keys rather than one polymorphic contact table
-- (ADR 0139's alternatives table): a polymorphic id cannot carry a foreign key,
-- so a contact could name another tenant's location and only application code
-- would notice. Both references below are tenant-scoped composite keys.
--
-- An outside person's name and phone are a third party's data (ADR 0029
-- PERSONAL), envelope-encrypted and bound to this row. The lawful basis and the
-- notice for holding them is a legal question the ADR leaves open; if it cannot
-- be answered, the outside-person shape is the first thing to remove.

CREATE TABLE tenant.location_contact_persons (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    location_id uuid NOT NULL,
    relationship_code varchar(24) NOT NULL,
    staff_member_id uuid,
    protected_name text,
    protected_phone text,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_location_contact_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    -- uq_locations_tenant_id (V0020): exactly (tenant_id, id).
    CONSTRAINT fk_location_contact_location FOREIGN KEY (tenant_id, location_id)
        REFERENCES tenant.locations (tenant_id, id),
    -- uq_staff_member_identity (V0453): exactly (id, tenant_id). A contact that
    -- names a colleague of another tenant is refused by the database.
    CONSTRAINT fk_location_contact_staff_member FOREIGN KEY (staff_member_id, tenant_id)
        REFERENCES iam.staff_members (id, tenant_id),
    CONSTRAINT ck_location_contact_relationship CHECK (
        relationship_code IN ('MANAGER', 'OWNER', 'LANDLORD', 'SECURITY', 'MAINTENANCE', 'OTHER')),
    CONSTRAINT ck_location_contact_shape CHECK (
        (staff_member_id IS NOT NULL AND protected_name IS NULL AND protected_phone IS NULL)
        OR (staff_member_id IS NULL AND protected_name IS NOT NULL AND protected_phone IS NOT NULL)),
    CONSTRAINT ck_location_contact_version CHECK (version >= 1)
);

CREATE INDEX ix_location_contact_location
    ON tenant.location_contact_persons (tenant_id, location_id, relationship_code, created_at);

-- A colleague appears once per role per branch; listing the same manager twice
-- as manager is a mistake the screen should refuse rather than render.
CREATE UNIQUE INDEX ux_location_contact_colleague
    ON tenant.location_contact_persons (tenant_id, location_id, relationship_code, staff_member_id)
    WHERE staff_member_id IS NOT NULL;

COMMENT ON TABLE tenant.location_contact_persons IS
    'ADR 0139 row 9.2b. A named contact person for a branch: a colleague by staff member id, or an outside person by protected name and phone. The published branch line stays tenant.locations.contact_phone.';
COMMENT ON COLUMN tenant.location_contact_persons.protected_name IS
    'ADR 0029 PERSONAL, a third party''s name, envelope-encrypted and bound to this row by AAD. Null for a colleague, whose name is read from iam.staff_members.';
COMMENT ON COLUMN tenant.location_contact_persons.protected_phone IS
    'ADR 0029 PERSONAL, a third party''s phone, envelope-encrypted and bound to this row by AAD. Null for a colleague: no copy of anyone''s phone is made.';

-- DELETE: a contact is genuinely removable; nothing else refers to one.
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.location_contact_persons TO horecaos_application;
