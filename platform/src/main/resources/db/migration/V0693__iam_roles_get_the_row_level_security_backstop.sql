-- ADR 0056 / ADR 0186: row-level security on the one table whose tenant_id is nullable.
--
-- V0161 built the policy template and said, in the comment on it, what it could not do:
-- "A table whose tenant_id is nullable because it is shared between platform-defined and
-- tenant-defined rows (iam.roles, iam.grants) needs a different predicate -- tenant_id = :guc
-- OR tenant_id IS NULL -- and must not be pointed at this function unmodified." Until a
-- tenant could define a job there was nothing in iam.roles to isolate. Now there is.
--
-- The shape, and why it is four policies rather than the one the template creates.
--
--   iam.roles
--     SELECT  tenant_id IS NULL OR tenant_id = <bound tenant>
--             A tenant sees the platform's jobs (it has to: they are what it grants) and its
--             own, and no other tenant's. An unbound session sees the platform's jobs only,
--             which is the fail-closed direction.
--     INSERT  tenant_id = <bound tenant>        (WITH CHECK)
--     UPDATE  tenant_id = <bound tenant>        (USING and WITH CHECK)
--             Writing is narrower than reading. The one-policy template would let a tenant
--             session UPDATE a platform job, because tenant_id IS NULL would pass its USING.
--             Platform jobs are written by RoleRegistrySynchronizer under the exempt role
--             (bindPlatform), which is BYPASSRLS and needs no policy.
--     DELETE  no policy, so refused. The application role has no DELETE on the table either
--             (V0035); a job is retired, not deleted.
--
--   iam.role_capabilities has no tenant_id, so it follows its role:
--     SELECT  its role is visible
--     INSERT  its role is the bound tenant's
--     DELETE  its role is the bound tenant's
--             (UPDATE is not granted on it.) The EXISTS subquery reads iam.roles under that
--             table's own SELECT policy, so "visible" means exactly what it means above.
--
-- Not covered here: iam.grants, whose tenant_id is nullable in the same way. It is read on the
-- hot path by far more callers than the roles are, and its inventory of readers is its own
-- piece of work; ADR 0186 says so.
--
-- Nothing in this migration changes a privilege, a column or a row, so rolling it back is
-- DISABLE ROW LEVEL SECURITY on the two tables.

ALTER TABLE iam.roles ENABLE ROW LEVEL SECURITY;

CREATE POLICY roles_visible ON iam.roles
    FOR SELECT
    USING (
        tenant_id IS NULL
        OR tenant_id = NULLIF(current_setting('horecaos.tenant_id', true), '')::uuid
    );

CREATE POLICY roles_insert_own ON iam.roles
    FOR INSERT
    WITH CHECK (tenant_id = NULLIF(current_setting('horecaos.tenant_id', true), '')::uuid);

CREATE POLICY roles_update_own ON iam.roles
    FOR UPDATE
    USING (tenant_id = NULLIF(current_setting('horecaos.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('horecaos.tenant_id', true), '')::uuid);

ALTER TABLE iam.role_capabilities ENABLE ROW LEVEL SECURITY;

CREATE POLICY role_capabilities_visible ON iam.role_capabilities
    FOR SELECT
    USING (EXISTS (SELECT 1 FROM iam.roles r WHERE r.id = role_capabilities.role_id));

CREATE POLICY role_capabilities_insert_own ON iam.role_capabilities
    FOR INSERT
    WITH CHECK (EXISTS (
        SELECT 1 FROM iam.roles r
         WHERE r.id = role_capabilities.role_id
           AND r.tenant_id = NULLIF(current_setting('horecaos.tenant_id', true), '')::uuid));

CREATE POLICY role_capabilities_delete_own ON iam.role_capabilities
    FOR DELETE
    USING (EXISTS (
        SELECT 1 FROM iam.roles r
         WHERE r.id = role_capabilities.role_id
           AND r.tenant_id = NULLIF(current_setting('horecaos.tenant_id', true), '')::uuid));

COMMENT ON POLICY roles_visible ON iam.roles IS
    'ADR 0056 / ADR 0186. A session reads the platform''s jobs and the bound tenant''s own; an unbound session reads the platform''s only.';
COMMENT ON POLICY roles_insert_own ON iam.roles IS
    'ADR 0186. A tenant session can create only its own jobs. A platform job is written under horecaos_platform_bypass.';
COMMENT ON POLICY roles_update_own ON iam.roles IS
    'ADR 0186. A tenant session can change only its own jobs, and cannot move one to another tenant.';
