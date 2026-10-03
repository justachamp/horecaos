-- ADR 0139: the counter behind iam.staff_members.display_reference.
--
-- A per-tenant row that the allocating statement locks, so two concurrent
-- invitations queue on it and never collide on a reference. Allocation is
--
--     INSERT ... ON CONFLICT (tenant_id) DO UPDATE
--        SET last_reference = last_reference + 1 RETURNING last_reference
--
-- which takes the row lock for the rest of the allocating transaction. A
-- reference is therefore never reused: it is not derived from count(*) or
-- max(), either of which would hand a vacated number to the next person, and an
-- audit fact that says "S-0142" must keep meaning one person.
--
-- A rolled-back allocation does roll the counter back with it, which is correct:
-- the number was never given to anyone.

CREATE TABLE iam.staff_member_counters (
    tenant_id uuid PRIMARY KEY,
    last_reference integer NOT NULL DEFAULT 0,

    CONSTRAINT fk_staff_member_counter_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT ck_staff_member_counter CHECK (last_reference >= 0)
);

COMMENT ON TABLE iam.staff_member_counters IS
    'ADR 0139. The last display_reference number given to a staff member of this tenant. Locked by the allocating statement so concurrent invitations queue rather than collide.';

GRANT SELECT, INSERT, UPDATE ON iam.staff_member_counters TO horecaos_application;
