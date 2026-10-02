-- ADR 0139: a staff member's emergency contacts (gap map row 9.2b).
--
-- A third party's name and phone, stored for the employer's benefit, about a
-- person who never dealt with the platform. That is why this is its own table
-- with its own capability: it is read only through staff.emergency-contact.read,
-- which writes an ADR 0027 fact on every read, and there is no bulk read.
--
-- At most three per member (sort_order 1..3, unique per member). The lawful
-- basis and the notice the staff member owes the person named are legal
-- questions the ADR leaves with legal; if they cannot be answered, this table
-- is the first thing to remove.
--
-- Name and phone are ADR 0029 PERSONAL, envelope-encrypted and bound to this
-- row. The relationship code is in clear: it is the one fact about the contact
-- a screen needs without decrypting anything.

CREATE TABLE iam.staff_emergency_contacts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    staff_member_id uuid NOT NULL,
    relationship_code varchar(24) NOT NULL,
    protected_name text NOT NULL,
    protected_phone text NOT NULL,
    sort_order smallint NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_emergency_contact_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    -- uq_staff_member_identity (V0453): exactly (id, tenant_id).
    CONSTRAINT fk_emergency_contact_member FOREIGN KEY (staff_member_id, tenant_id)
        REFERENCES iam.staff_members (id, tenant_id),
    CONSTRAINT uq_emergency_contact_slot UNIQUE (tenant_id, staff_member_id, sort_order),
    CONSTRAINT ck_emergency_contact_relationship CHECK (
        relationship_code IN ('SPOUSE', 'PARENT', 'CHILD', 'SIBLING', 'FRIEND', 'OTHER')),
    CONSTRAINT ck_emergency_contact_slot CHECK (sort_order BETWEEN 1 AND 3),
    CONSTRAINT ck_emergency_contact_version CHECK (version >= 1)
);

COMMENT ON TABLE iam.staff_emergency_contacts IS
    'ADR 0139 row 9.2b. Up to three emergency contacts per staff member: a third party''s data, read only through staff.emergency-contact.read with an audit fact per read, deleted with the retention sweeper''s anonymisation of the member.';
COMMENT ON COLUMN iam.staff_emergency_contacts.protected_name IS
    'ADR 0029 PERSONAL, a third party''s name, envelope-encrypted and bound to this row by AAD.';
COMMENT ON COLUMN iam.staff_emergency_contacts.protected_phone IS
    'ADR 0029 PERSONAL, a third party''s phone, envelope-encrypted and bound to this row by AAD.';

-- DELETE: the contact set is replaced as a whole, and the retention sweeper
-- deletes an ended member's contacts.
GRANT SELECT, INSERT, UPDATE, DELETE ON iam.staff_emergency_contacts TO horecaos_application;
