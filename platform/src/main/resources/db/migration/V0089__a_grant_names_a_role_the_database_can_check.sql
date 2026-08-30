-- The authorization reference, done the way V0088 did the other four.
--
-- V0086 tried to close this with a pair of triggers: one refusing a grant that
-- names a role belonging to another tenant, one refusing a re-parent that would
-- leave such a grant behind. Both are correct in what they check and neither
-- holds, and the reason is the whole lesson.
--
-- `iam.roles.tenant_id` appears only in partial unique indexes, so it is not a
-- lock key column. `fk_grant_role`'s FOR KEY SHARE therefore does not serialize
-- against a change to it, and an adversarial pass reproduced the breach twice on
-- PostgreSQL 18: session one inserts a grant in tenant B naming a platform role
-- and holds its transaction open; session two re-parents that role to tenant A;
-- both commit and neither trigger fires, because each looked at a world the
-- other had not committed yet. A trigger is a check at a moment. Referential
-- integrity is a lock.
--
-- So this uses V0088's shape instead, which the same pass attacked three ways —
-- lying about the declared boolean, re-parenting the target concurrently, and
-- delete-and-reinsert under a new owner — and could not break. The owner becomes
-- part of the referenced key, and PostgreSQL's own RI does the locking.
--
-- What this makes true is what ck_role_ownership's comment has claimed since
-- V0007 and could not enforce: one tenant can never see another's custom role.

-- ---------------------------------------------------------------------------
-- 1. Nothing to repair, and prove it rather than assume it
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    offending bigint;
BEGIN
    SELECT count(*) INTO offending
      FROM iam.grants AS grant_row
      JOIN iam.roles AS role_row ON role_row.id = grant_row.role_id
     WHERE role_row.tenant_id IS NOT NULL
       AND role_row.tenant_id IS DISTINCT FROM grant_row.tenant_id;
    IF offending > 0 THEN
        RAISE EXCEPTION
            'V0089: % grant(s) name a role defined by another tenant. A grant is the '
            'authorization primitive, so nothing here will delete one. List them with: '
            'SELECT g.id, g.tenant_id, g.role_id, r.tenant_id FROM iam.grants g JOIN '
            'iam.roles r ON r.id = g.role_id WHERE r.tenant_id IS NOT NULL AND '
            'r.tenant_id IS DISTINCT FROM g.tenant_id;',
            offending;
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 2. The role's owner, made total
-- ---------------------------------------------------------------------------
--
-- The nil uuid is the platform, and tenant.tenants has refused to hold it since
-- V0088's ck_tenant_id_is_not_the_platform_sentinel — so no tenant can ever
-- share an owner with the platform.

ALTER TABLE iam.roles
    ADD COLUMN owner_tenant_id uuid
        GENERATED ALWAYS AS (
            coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid)
        ) STORED;

COMMENT ON COLUMN iam.roles.owner_tenant_id IS
    'V0089. The role''s owner made total: its tenant, or the nil uuid for a platform-defined role under ADR 0025. Exists so a grant can name an owner rather than an absence, and so that owner is a lock key column — which is exactly what V0086''s triggers were not.';

ALTER TABLE iam.roles
    ADD CONSTRAINT uq_role_owner_identity UNIQUE (owner_tenant_id, id);

-- ---------------------------------------------------------------------------
-- 3. The grant declares which kind of role it names, and the key makes it true
-- ---------------------------------------------------------------------------

ALTER TABLE iam.grants
    ADD COLUMN role_is_platform boolean;

UPDATE iam.grants AS grant_row
   SET role_is_platform = (role_row.tenant_id IS NULL)
  FROM iam.roles AS role_row
 WHERE role_row.id = grant_row.role_id;

-- `role_id` is NOT NULL, so every grant names a role and every grant must say
-- whose. The UPDATE above therefore covers every row, and SET NOT NULL is what
-- would say so if it did not.
ALTER TABLE iam.grants
    ALTER COLUMN role_is_platform SET NOT NULL;

ALTER TABLE iam.grants
    ADD COLUMN role_owner_id uuid
        GENERATED ALWAYS AS (
            CASE
                WHEN role_is_platform THEN '00000000-0000-0000-0000-000000000000'::uuid
                ELSE tenant_id
            END
        ) STORED;

COMMENT ON COLUMN iam.grants.role_is_platform IS
    'V0089. Whether the role this grant names is platform-defined. fk_grant_role makes the claim true rather than trusting it.';

COMMENT ON COLUMN iam.grants.role_owner_id IS
    'V0089. Derived: the platform sentinel or this grant''s own tenant, and no third answer is expressible. The tenant half of fk_grant_role.';

-- `iam.grants.tenant_id` is nullable — a PLATFORM-scope grant belongs to no
-- tenant. Such a grant may only name a platform role, and without this a
-- `role_is_platform = false` on one of them would generate a NULL owner and take
-- the foreign key out of the check entirely, which is the hole the whole
-- migration exists to close.
ALTER TABLE iam.grants
    ADD CONSTRAINT ck_grant_role_ownership CHECK (
        role_is_platform OR tenant_id IS NOT NULL);

ALTER TABLE iam.grants
    DROP CONSTRAINT fk_grant_role;

ALTER TABLE iam.grants
    ADD CONSTRAINT fk_grant_role
        FOREIGN KEY (role_owner_id, role_id)
        REFERENCES iam.roles (owner_tenant_id, id);

COMMENT ON CONSTRAINT fk_grant_role ON iam.grants IS
    'V0089. Composite on the derived owner, so a grant names the platform''s role or one of its own and nothing else. Replaces V0086''s trigger pair, which checked the same rule and lost the race because iam.roles.tenant_id was not a lock key column.';

-- ---------------------------------------------------------------------------
-- 4. Retire the triggers the key replaces
-- ---------------------------------------------------------------------------
--
-- Strictly weaker than the constraint above and now redundant, so they go rather
-- than staying as a second answer to the same question that fires on every write
-- and reassures a reader who has not read this far.

DROP TRIGGER IF EXISTS trg_grant_names_a_visible_role ON iam.grants;
DROP TRIGGER IF EXISTS trg_role_reparenting_leaves_no_grant_behind ON iam.roles;
DROP FUNCTION IF EXISTS iam.assert_grant_names_a_visible_role();
DROP FUNCTION IF EXISTS iam.assert_role_reparenting_leaves_no_grant_behind();
