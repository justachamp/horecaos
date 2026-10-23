-- ADR 0186 (row 9.1b): a tenant may define jobs of its own, composed from the capability
-- catalogue.
--
-- iam.roles has carried a tenant_id, ck_role_ownership and uq_role_tenant_code since V0008,
-- and V0086/V0089 made it safe for a grant to name a role a tenant defined (fk_grant_role is
-- composite on the derived owner). What the table never had is anywhere to say who defined a
-- job, who last changed it, what it was copied from, or why it was closed. Those are the six
-- columns below, all nullable: a code-owned job (is_platform_defined, written from code by
-- RoleRegistrySynchronizer) has none of them, and a row some fixture wrote by hand before this
-- migration has none either.
--
-- What is deliberately NOT constrained here, and why.
--
--   * The shape of a tenant job's code. TenantRoleService generates 'job-' followed by ten
--     lowercase hex digits and GrantManagementService.resolveRole consults the code-owned
--     registry before the table, so a tenant row can never shadow tenant-owner whatever it is
--     called. A CHECK on the format would be a second statement of the first fact, and a dozen
--     existing suites hand-author tenant roles with readable codes to model a narrower-than-
--     bundle grant; the constraint would refuse their rows without making anything safer.
--   * created_by on a tenant row. The same fixtures, for the same reason.
--
-- What is constrained: the retirement columns agree with each other, and a code-owned job has
-- none of the tenant columns, so a bug in the synchronizer cannot start writing an author onto
-- a platform role.

ALTER TABLE iam.roles
    ADD COLUMN created_by varchar(255),
    ADD COLUMN updated_by varchar(255),
    ADD COLUMN copied_from varchar(64),
    ADD COLUMN retired_at timestamptz,
    ADD COLUMN retired_by varchar(255),
    ADD COLUMN retired_reason varchar(1000);

-- All three retirement facts together, and only on a row that is actually retired. The
-- converse (a RETIRED row must carry them) is not asserted: status RETIRED existed before
-- this migration and nothing in the tree ever wrote it, but a hand-written fixture may.
ALTER TABLE iam.roles
    ADD CONSTRAINT ck_role_retirement_agrees CHECK (
        (retired_at IS NULL) = (retired_by IS NULL)
        AND (retired_at IS NULL) = (retired_reason IS NULL)
        AND (retired_at IS NULL OR status = 'RETIRED')
    );

ALTER TABLE iam.roles
    ADD CONSTRAINT ck_role_platform_has_no_tenant_columns CHECK (
        NOT is_platform_defined OR (
            created_by IS NULL AND updated_by IS NULL AND copied_from IS NULL
            AND retired_at IS NULL AND retired_by IS NULL AND retired_reason IS NULL)
    );

COMMENT ON COLUMN iam.roles.created_by IS
    'ADR 0186. The principal subject (opaque, never a name) that defined this tenant job. Null on a code-owned job.';
COMMENT ON COLUMN iam.roles.updated_by IS
    'ADR 0186. The principal subject that last changed this tenant job. Null on a code-owned job.';
COMMENT ON COLUMN iam.roles.copied_from IS
    'ADR 0186. The code of the job this one was started from (a "template" is a copy), kept for the audit trail only. Not a reference: the source may be retired or code-owned.';
COMMENT ON COLUMN iam.roles.retired_at IS
    'ADR 0186. When the tenant closed this job. A job is retired, never deleted: the application role has no DELETE on this table, and a retire is refused while any active grant names the job.';
COMMENT ON COLUMN iam.roles.version IS
    'The expected version of an If-Match write (ADR 0031). A tenant job starts at 1 and every edit or retire adds one; a code-owned job is never edited through the API and stays at 0.';

-- No new table, so no new GRANT: V0035 already gives horecaos_application SELECT, INSERT and
-- UPDATE on iam.roles and SELECT, INSERT and DELETE on iam.role_capabilities, which is exactly
-- what defining, editing and retiring a job needs.
