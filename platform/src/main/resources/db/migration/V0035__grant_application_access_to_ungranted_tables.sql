-- The grants nine earlier migrations never made.
--
-- In production the application connects as `horecaos_app`: a login role that owns
-- nothing and inherits only the NOLOGIN group role `horecaos_application`, so it
-- reaches exactly the objects a migration has named and nothing else. In
-- development it connects as the owner, where every grant is redundant. A
-- missing GRANT is therefore invisible until the first production start, and the
-- first production start, on 2026-08-23, found 24 tables the application could
-- not read, in three schemas it could not enter.
--
-- The convention of ending a migration with a GRANT block starts at V0007.
-- V0003, V0004, V0005, V0006, V0008, V0009, V0011, V0013 and V0014 either
-- predate it or were written alongside it and did not pick it up. Flyway is
-- forward-only, so their blocks cannot be added retrospectively; this migration
-- carries them, and every grant below names the migration it belongs to.
--
-- The privileges here are not the uniform SELECT/INSERT/UPDATE/DELETE that kept
-- production running in the meantime. That stopgap — applied by deploy.sh from
-- infra/production/grants-pending-migration.sql, which this migration replaces
-- and deletes — declined to make the judgement on purpose, because a judgement
-- about a table belongs beside the table. This is where it belongs, and it is
-- made against two things: the SQL the repositories under
-- src/main/java/uz/horecaos/platform issue, and the lifecycle each table's own
-- columns describe. The reasoning for every narrowing is recorded after the
-- grants.

-- Four tables in this set have no COMMENT ON TABLE, and in each case the thing
-- that is missing is exactly the thing that decides its privileges.

COMMENT ON TABLE iam.roles IS
    'ADR 0025 roles, projected from the code-owned registry at startup. Code remains the authority for what a role means; a role leaves service by becoming RETIRED, never by disappearing.';

COMMENT ON TABLE iam.role_capabilities IS
    'ADR 0025 capabilities a role confers. A replaced set, not an edited one: the startup synchroniser deletes a role''s rows and re-inserts them, so a capability removed from a bundle in code cannot survive here.';

COMMENT ON TABLE tenant.onboarding_runs IS
    'ADR 0008 one resumable onboarding of one tenant. The workflow store and the answer to "why is this tenant not live"; it is not a log, and a cancelled run stays as CANCELLED.';

COMMENT ON TABLE tenant.onboarding_steps IS
    'ADR 0008 the materialised steps of a run, including the ones blocked on absent capabilities. Claimed with FOR UPDATE SKIP LOCKED, so several replicas share the work rather than repeat it.';


-- --------------------------------------------------------------------- grants

-- No migration has ever granted USAGE on these three, so every table inside them
-- was unreachable twice over. tenant and reporting have it from V0020 and V0031,
-- which is why only the tables listed below were missing there.
GRANT USAGE ON SCHEMA iam TO horecaos_application;
GRANT USAGE ON SCHEMA integration TO horecaos_application;
GRANT USAGE ON SCHEMA platform TO horecaos_application;

-- The stopgap granted all four privileges on every table below, and production
-- has been running with it applied since 2026-08-23. Revoking first is what
-- makes the grants that follow the whole truth on that database, rather than a
-- floor underneath privileges that are already wider. Where the stopgap never
-- ran — a fresh restore, a test container, a developer's machine — every REVOKE
-- here is a no-op, because no migration has ever granted on these tables.
REVOKE ALL ON
    iam.roles,
    iam.role_capabilities,
    iam.grants,
    iam.capability_registry_snapshot,
    integration.outbox_events,
    integration.inbox_messages,
    integration.provider_environments,
    integration.installations,
    integration.bindings,
    integration.binding_capabilities,
    integration.provider_entity_mappings,
    tenant.tenants,
    tenant.brands,
    tenant.locations,
    tenant.customer_identity_policies,
    tenant.configuration_values,
    tenant.policies,
    tenant.policy_current,
    tenant.onboarding_templates,
    tenant.onboarding_runs,
    tenant.onboarding_steps,
    tenant.readiness_checks,
    platform.idempotency_records,
    reporting.tenant_summaries
    FROM horecaos_application;

-- V0003 create_tenancy_and_acceptance_policy
GRANT SELECT, INSERT, UPDATE ON tenant.tenants TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON tenant.brands TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON tenant.locations TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON tenant.customer_identity_policies TO horecaos_application;

-- V0004 create_transactional_outbox
GRANT SELECT, INSERT, UPDATE ON integration.outbox_events TO horecaos_application;

-- V0005 create_configuration_and_policies
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.configuration_values TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON tenant.policies TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant.policy_current TO horecaos_application;

-- V0006 create_idempotency_records
GRANT SELECT, INSERT, UPDATE, DELETE ON platform.idempotency_records TO horecaos_application;

-- V0008 create_authorization_grants
GRANT SELECT, INSERT, UPDATE ON iam.roles TO horecaos_application;
GRANT SELECT, INSERT, DELETE ON iam.role_capabilities TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON iam.grants TO horecaos_application;
GRANT SELECT, INSERT, DELETE ON iam.capability_registry_snapshot TO horecaos_application;

-- V0009 create_inbox_messages
GRANT SELECT, INSERT, UPDATE ON integration.inbox_messages TO horecaos_application;

-- V0011 create_tenant_summary_read_model
GRANT SELECT, INSERT, UPDATE ON reporting.tenant_summaries TO horecaos_application;

-- V0013 create_provider_installations
GRANT SELECT ON integration.provider_environments TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON integration.installations TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON integration.bindings TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON integration.binding_capabilities TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON integration.provider_entity_mappings TO horecaos_application;

-- V0014 create_onboarding_workflow
GRANT SELECT, INSERT, UPDATE ON tenant.onboarding_templates TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON tenant.onboarding_runs TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON tenant.onboarding_steps TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON tenant.readiness_checks TO horecaos_application;

-- The grants above are deliberately uneven, and the unevenness is the point. The
-- default is SELECT, INSERT, UPDATE, and eighteen of these twenty-four tables
-- take it. What follows is why three have DELETE on top of it, two trade UPDATE
-- for DELETE, and one has SELECT and nothing else.
--
-- DELETE, on the three tables that have it:
--
--   platform.idempotency_records is the only one where deletion is on the
--   request path. `IdempotencyService.release` frees a claim whose request
--   failed in a retriable way, and `purgeExpired` removes records past their
--   retention. Both must remove the row rather than mark it: a tombstone would
--   still occupy the unique key the next attempt needs.
--
--   tenant.configuration_values needs it because ADR 0030 makes absence and
--   explicit null different answers. No row means "not set at this level,
--   continue"; a row with is_explicit_null means "deliberately unset here". So
--   clearing an override has to remove the row — writing a null in its place
--   would change the resolution, not undo it.
--
--   tenant.policy_current holds the active version per scope, and returning a
--   scope to its parent's policy is the removal of that row. Activation itself
--   is the INSERT or the UPDATE; deactivation has nothing to write.
--
-- No DELETE anywhere else, for three different reasons. integration.outbox_events
-- and integration.inbox_messages are ledgers of what crossed the boundary: the
-- inbox row *is* the idempotency record, so deleting it would let a redelivery
-- re-run an effect that already happened, and retention on both is an operator
-- job run as the owner, not something a request may do. iam.grants, the
-- installations and bindings, the entity mappings, the policies, the runs and
-- the tenants all record that something was decided, and each has a status —
-- REVOKED, RETIRED, SUSPENDED, ARCHIVED, CANCELLED — that says so in a way a
-- deletion cannot. And ADR 0030 states outright that a policy version can never
-- be deleted while a business fact still references it.
--
-- iam.role_capabilities and iam.capability_registry_snapshot are the exception
-- that looks like an oversight: SELECT, INSERT and DELETE, with no UPDATE.
-- Neither has a column that could be updated — one is (role_id,
-- capability_code) and the other is a code with its resource type and action —
-- and `RoleRegistrySynchronizer` maintains both by replacement at startup,
-- deleting a role's capability rows before re-inserting them and emptying the
-- registry snapshot before rewriting it. Replacement is the mechanism that makes
-- a capability withdrawn in code disappear from the database; an UPDATE grant
-- would add nothing it could act on.
--
-- integration.provider_environments gets SELECT and nothing else. It is the
-- approved catalogue of provider endpoints, platform-owned and never
-- tenant-writable, and that is what closes the request-forgery path at the
-- model: a tenant chooses an environment, and no application path can add one.
-- The same shape as audit.approval_policies in V0007.
--
-- Two tables here have no application writer yet — tenant.onboarding_templates
-- is authored ahead of a run, and nothing has evaluated a readiness check since
-- ADR 0008's evaluators are unbuilt. They are granted for the write path their
-- own columns describe (a template is created and later retired; a check is
-- re-evaluated in place, which is what uq_readiness_check exists to make
-- possible) rather than left at SELECT, because a grant that trails the code by
-- one release reproduces exactly the failure this migration exists to fix.
--
-- SELECT is broader than reading looks in two places, and both are the
-- database's rule rather than a choice. reporting.tenant_summaries is written
-- only by a projection, but its upsert reads back last_event_at through
-- GREATEST, and ON CONFLICT DO UPDATE needs SELECT on the columns it reads.
-- tenant.onboarding_steps and integration.outbox_events are claimed with FOR
-- UPDATE SKIP LOCKED, which needs UPDATE on top of SELECT even before the
-- claiming statement writes anything.
