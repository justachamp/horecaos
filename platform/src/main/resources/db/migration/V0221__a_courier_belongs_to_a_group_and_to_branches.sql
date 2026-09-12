-- IA 3.3: a courier's group and the branches they ride for, as relations.
--
-- Today a courier is attached to a branch only by having opened a shift there,
-- which means the attachment exists exactly while somebody is working and
-- cannot be planned, listed, or handed to a new manager. "Which riders belong
-- to Chilonzor" has no answer before six in the morning, and "who is on the
-- night group" has none at all.
--
-- Two relations, not one column each:
--
--   courier_groups / courier_group_members  A group is a roster-wide label a
--       manager authors -- "night", "bicycles", "the Yunusobod six" -- and a
--       courier may sit in more than one of them. A single group_id column on
--       the courier would force the first tenant who wants two labels to
--       invent a naming convention inside one string.
--
--   courier_branch_bindings  Which branches this courier rides for, with one
--       of them marked primary. Many-to-many because a rider covering two
--       branches on alternate days is the ordinary case in a two-branch
--       tenant, and because the alternative -- a branch column on the courier
--       -- makes the second branch invisible rather than impossible.
--
-- Neither relation authorises anything. Dispatch still reads the engagement
-- and the shift; this is the manager's organisation of the fleet, and a
-- binding that quietly gated an offer would be a second, undocumented
-- dispatch rule.

CREATE TABLE fulfillment.courier_groups (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    code varchar(32) NOT NULL,
    display_name varchar(120) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_courier_group_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT uq_courier_group_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_courier_group_identity UNIQUE (id, tenant_id),
    CONSTRAINT ck_courier_group_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

COMMENT ON TABLE fulfillment.courier_groups IS
    'IA 3.3. A manager-authored label over the roster. Archived rather than deleted: a group named on a past shift plan is history, and history is not edited.';

-- The membership, keyed so that the database refuses a courier and a group
-- from two different tenants rather than trusting the service to notice.
CREATE TABLE fulfillment.courier_group_members (
    tenant_id uuid NOT NULL,
    group_id uuid NOT NULL,
    courier_id uuid NOT NULL,
    added_at timestamptz NOT NULL DEFAULT now(),
    added_by varchar(128) NOT NULL,

    CONSTRAINT pk_courier_group_member PRIMARY KEY (tenant_id, group_id, courier_id),
    CONSTRAINT fk_group_member_group FOREIGN KEY (group_id, tenant_id)
        REFERENCES fulfillment.courier_groups (id, tenant_id),
    CONSTRAINT fk_group_member_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id)
);

CREATE INDEX ix_courier_group_members_courier
    ON fulfillment.courier_group_members (tenant_id, courier_id);

CREATE TABLE fulfillment.courier_branch_bindings (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    courier_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    is_primary boolean NOT NULL DEFAULT false,
    bound_at timestamptz NOT NULL DEFAULT now(),
    bound_by varchar(128) NOT NULL,

    CONSTRAINT fk_courier_binding_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id),
    CONSTRAINT fk_courier_binding_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT uq_courier_binding UNIQUE (tenant_id, courier_id, location_id)
);

COMMENT ON TABLE fulfillment.courier_branch_bindings IS
    'IA 3.3. Which branches a courier rides for. Not an authorization: dispatch reads the engagement and the shift, and a binding that gated an offer would be a second dispatch rule nobody wrote down.';

-- At most one primary branch per courier. Expressed as a partial unique index
-- rather than a CHECK, because "at most one row of a set" is a statement about
-- the set and a CHECK can only see one row of it.
CREATE UNIQUE INDEX ux_courier_binding_one_primary
    ON fulfillment.courier_branch_bindings (tenant_id, courier_id)
    WHERE is_primary;

CREATE INDEX ix_courier_bindings_location
    ON fulfillment.courier_branch_bindings (tenant_id, location_id);

GRANT SELECT, INSERT, UPDATE ON fulfillment.courier_groups TO horecaos_application;
-- Membership and bindings are attachments a manager makes and unmakes, and an
-- unmade attachment is not history the way a group's name is: DELETE is the
-- honest verb, and a status column pretending otherwise would leave every read
-- filtering on it forever.
GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.courier_group_members TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON fulfillment.courier_branch_bindings TO horecaos_application;
