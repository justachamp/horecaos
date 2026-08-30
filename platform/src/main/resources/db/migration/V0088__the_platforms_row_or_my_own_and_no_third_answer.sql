-- The four references a composite key was said to be unable to express.
--
-- `tools/checks/known_tenant_blind_references.tsv` has carried five
-- MIXED_OWNERSHIP_TARGET entries since V0077, all with the same argument: the
-- referenced table's `tenant_id` is nullable on purpose, because a platform-owned
-- row sits underneath a tenant-owned one — ADR 0030 puts a platform default under
-- a tenant override, V0025 says outright that "Tashkent is not one tenant's fact",
-- and ADR 0027 admits a platform-scope approval that belongs to no tenant. A
-- foreign key on `(tenant_id, id)` cannot say "mine or the platform's": under
-- MATCH SIMPLE a NULL anywhere in the key stops the check entirely, so the
-- platform case would go unchecked while the cross-tenant case stayed possible.
-- Every entry therefore concluded that only a resolution rule in the referencing
-- service could close it.
--
-- That conclusion is half right. A key on the *raw* columns cannot express the
-- disjunction. A key on two derived ones can, and this migration is that key,
-- applied four times. The fifth entry — `iam.grants.fk_grant_role` — is left
-- alone; iam's grant path is the authorization path and belongs to a change of
-- its own.
--
-- ----------------------------------------------------------------- the shape
--
-- Two derived columns, one on each side.
--
--   On the TARGET:      owner_tenant_id uuid GENERATED ALWAYS AS
--                           (coalesce(tenant_id, PLATFORM)) STORED
--
--     The nullable ownership, made total. A platform row's owner is a real value
--     rather than an absence, so it can appear in a key and be checked like any
--     other. `PLATFORM` below is the nil uuid, which V0008 and V0051 already use
--     for exactly this — a scope that is deliberately not a row.
--
--   On the REFERENCING: <ref>_is_platform boolean, declared by the writer
--                       <ref>_owner_id uuid GENERATED ALWAYS AS
--                           (CASE WHEN <ref>_id IS NULL       THEN NULL
--                                 WHEN <ref>_is_platform      THEN PLATFORM
--                                 ELSE tenant_id END) STORED
--
--     The referencing row states which of the two permitted owners it is naming,
--     and the schema does the encoding. The generated expression can produce only
--     three values: NULL when there is no reference at all, the platform sentinel,
--     or this row's own tenant. There is no way to write a third, so a foreign key
--     on `(<ref>_owner_id, <ref>_id)` admits exactly the platform's row and the
--     caller's own — which is the rule the allowlist said a database could not
--     hold.
--
-- The boolean is a fact, not an encoding, and it is deliberately the writer's to
-- state rather than something derived behind its back: a service that resolves
-- the id in the wrong tenant now has to *say so* in a column, and the constraint
-- catches it. A derived-from-the-target version would agree with whatever the
-- target happened to be and check nothing.
--
-- Three properties this has and a service-side resolution rule does not:
--
--   1. It holds for every write path, including the ones nobody has written yet
--      — a repair script, a backfill, a second service. Every one of these five
--      entries said "the service already resolves correctly", and every one was
--      right; V0069's entry said the same thing about CourierEngagementService.
--      The reference matters precisely where the resolved path is bypassed.
--   2. It holds in the other direction. `UPDATE audit.approval_policies SET
--      tenant_id = ...` changes the generated owner, and the foreign key refuses
--      it while a request still names that policy. A trigger on the referencing
--      table cannot see that update at all, so the guarantee it delivers lapses
--      silently the moment the target moves.
--   3. It survives being read. `owner_tenant_id` in a catalog dump says what the
--      rule is; a trigger body says it somewhere a reviewer has to go and find.
--
-- Where a service check is still needed, it is needed for the error rather than
-- the guarantee: a constraint violation aborts the transaction and reads as a
-- 500, and an operator naming another tenant's approval request deserves a
-- refusal that says so. The service resolution added alongside this migration is
-- that, and this file is what makes it true when the service is not on the path.
--
-- ------------------------------------------------------------ what it is not
--
-- This closes the TENANT boundary and nothing else. It does not check that a
-- cutover decision's approval request authorised *that* cutover (ADR 0027's spend
-- model, V0071), that a `policy_current` pointer names a policy of its own
-- key_code and scope, or that an approval request's policy governs its action
-- code. Those are real and separate; conflating them here would have made one
-- unreviewable change out of two reviewable ones.
--
-- ------------------------------------------------------------------- grants
--
-- No new table, so no new GRANT. Every one of the six tables touched holds a
-- table-level privilege for `horecaos_application` (V0005, V0007, V0024, V0025), and
-- a table-level privilege in PostgreSQL covers columns added later — the same
-- reasoning V0071 wrote down for `consumed_at`. A generated column is never
-- written directly, so it needs no INSERT or UPDATE privilege of its own, which
-- is why the column-level UPDATE grant V0059 left on `audit.approval_policies`
-- (valid_until, and nothing else) does not have to be widened. Referential
-- integrity checks run with the privileges of the constraint's owner, not the
-- inserting role, so no REFERENCES grant is needed either.
--
-- Every ALTER that adds a STORED generated column rewrites its table. All six are
-- configuration- or decision-rate tables — policies, regions, approval records,
-- zone versions — and none is on the order path.

-- ---------------------------------------------------------------------------
-- 0. The sentinel has to be a value no tenant can ever have
-- ---------------------------------------------------------------------------

-- The whole construction rests on `coalesce(tenant_id, PLATFORM)` being
-- injective: if a tenant could be registered under the nil uuid, its rows and the
-- platform's would collapse onto one owner and every other tenant could reference
-- them. Every table below takes its `tenant_id` from `tenant.tenants` through a
-- foreign key, so one CHECK here covers all four references.
--
-- Refuse rather than repair. A tenant registered under the nil uuid is not a data
-- error this migration can fix — renaming a tenant id means rewriting every row
-- in the platform that carries it — and deploying the constraint over it would be
-- claiming a boundary that is not there.
DO $$
DECLARE
    offending bigint;
BEGIN
    SELECT count(*) INTO offending
      FROM tenant.tenants
     WHERE id = '00000000-0000-0000-0000-000000000000'::uuid;
    IF offending > 0 THEN
        RAISE EXCEPTION
            'V0088: a tenant is registered under the nil uuid, which V0088 reserves as the '
            'platform owner. Re-key that tenant before deploying: SELECT id, slug FROM '
            'tenant.tenants WHERE id = ''00000000-0000-0000-0000-000000000000'';';
    END IF;
END
$$;

ALTER TABLE tenant.tenants
    ADD CONSTRAINT ck_tenant_id_is_not_the_platform_sentinel
        CHECK (id <> '00000000-0000-0000-0000-000000000000'::uuid);

COMMENT ON CONSTRAINT ck_tenant_id_is_not_the_platform_sentinel ON tenant.tenants IS
    'V0088 reserves the nil uuid as the owner of platform-owned rows in tables whose tenant_id is nullable by design. A tenant holding it would share an owner with the platform.';

-- ---------------------------------------------------------------------------
-- 1. migration.cutover_decisions -> audit.approval_requests
-- ---------------------------------------------------------------------------
--
-- First because it is the worst. A cutover decision is the record that a named
-- human accepted responsibility for moving who writes a tenant's data, and
-- `approval_request_id` is the ADR 0027 maker-checker request that decision
-- discharges. `MigrationScopeService.cutOver` takes it straight from the request
-- body and writes it, checking only that it exists somewhere on the platform. So
-- an operator could cite ANOTHER TENANT'S approval request as the authorisation
-- for moving their own tenant's writer, and the evidence table — the one a
-- reviewer reads first, and the one V0024 made append-only so it could be
-- trusted — would record it as signed for.
--
-- `audit.approval_requests.tenant_id` is nullable because a PLATFORM-scope
-- approval belongs to no tenant, which is a real case: a platform-wide cutover is
-- approved by the platform, not by the tenant being moved.

ALTER TABLE audit.approval_requests
    ADD COLUMN owner_tenant_id uuid
        GENERATED ALWAYS AS (
            coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid)
        ) STORED;

COMMENT ON COLUMN audit.approval_requests.owner_tenant_id IS
    'V0088. The request''s owner made total: its tenant, or the nil uuid for a PLATFORM-scope request that belongs to no tenant. Exists so a reference can name an owner rather than an absence.';

ALTER TABLE audit.approval_requests
    ADD CONSTRAINT uq_approval_request_owner_identity UNIQUE (owner_tenant_id, id);

-- Refuse the deployment rather than repair the row. A cutover decision naming
-- another tenant's approval request is a claim that somebody authorised a
-- transfer of ownership; deleting it, or blanking the pointer, destroys the only
-- evidence that the claim was ever made. It is adjudicated by a human with the
-- rows in front of them.
DO $$
DECLARE
    offending bigint;
BEGIN
    SELECT count(*) INTO offending
      FROM migration.cutover_decisions decision
      JOIN audit.approval_requests request ON request.id = decision.approval_request_id
     WHERE request.tenant_id IS NOT NULL
       AND request.tenant_id <> decision.tenant_id;
    IF offending > 0 THEN
        RAISE EXCEPTION
            'V0088: % cutover decision(s) cite an approval request belonging to another '
            'tenant. Nothing here will delete them. List them with: SELECT d.id, d.tenant_id, '
            'd.approval_request_id, r.tenant_id FROM migration.cutover_decisions d JOIN '
            'audit.approval_requests r ON r.id = d.approval_request_id WHERE r.tenant_id IS NOT '
            'NULL AND r.tenant_id <> d.tenant_id;', offending;
    END IF;
END
$$;

ALTER TABLE migration.cutover_decisions
    ADD COLUMN approval_request_is_platform boolean;

-- Backfill from what the rows already point at. The check above has already
-- established that every one of them is either the platform's or its own
-- tenant's, so this records which — it does not decide anything.
UPDATE migration.cutover_decisions AS decision
   SET approval_request_is_platform = (request.tenant_id IS NULL)
  FROM audit.approval_requests AS request
 WHERE request.id = decision.approval_request_id;

ALTER TABLE migration.cutover_decisions
    ADD COLUMN approval_request_owner_id uuid
        GENERATED ALWAYS AS (
            CASE
                WHEN approval_request_id IS NULL THEN NULL
                WHEN approval_request_is_platform THEN '00000000-0000-0000-0000-000000000000'::uuid
                ELSE tenant_id
            END
        ) STORED;

COMMENT ON COLUMN migration.cutover_decisions.approval_request_is_platform IS
    'V0088. Whether the cited approval request is a PLATFORM-scope one. Null exactly when no request is cited; fk_cutover_approval_request makes the claim true.';

COMMENT ON COLUMN migration.cutover_decisions.approval_request_owner_id IS
    'V0088. Derived: the platform sentinel or this decision''s own tenant, and nothing else is expressible. The tenant half of fk_cutover_approval_request.';

-- A decision either cites a request and says whose it is, or cites none. Without
-- this, leaving the boolean null would make the generated column null and MATCH
-- SIMPLE would skip the foreign key — which is the exact hole this file closes,
-- reopened one column to the left.
ALTER TABLE migration.cutover_decisions
    ADD CONSTRAINT ck_cutover_approval_ownership_declared CHECK (
        (approval_request_id IS NULL) = (approval_request_is_platform IS NULL));

ALTER TABLE migration.cutover_decisions
    DROP CONSTRAINT fk_cutover_approval_request;

ALTER TABLE migration.cutover_decisions
    ADD CONSTRAINT fk_cutover_approval_request
        FOREIGN KEY (approval_request_owner_id, approval_request_id)
        REFERENCES audit.approval_requests (owner_tenant_id, id);

-- ---------------------------------------------------------------------------
-- 2. audit.approval_requests -> audit.approval_policies
-- ---------------------------------------------------------------------------
--
-- The policy decides who is allowed to approve — `required_approver_capability`
-- is on the policy row, and `policy_version` is snapshotted onto the request so a
-- later edit cannot change what was approved. A request naming another tenant's
-- policy therefore imports another tenant's answer to "who may sign this".
--
-- `JdbcApprovalService.resolvePolicy` is tenant-correct today: it walks the ADR
-- 0030 scope chain and matches `tenant_id IS NOT DISTINCT FROM :tenantId` at each
-- level, so the only rows it can find are the caller's own and — at the PLATFORM
-- level, where the chain ends — the platform's. Those two are exactly the two the
-- key below admits. Nothing about that resolution is written down in the schema,
-- which is the gap.
--
-- V0082 added `brand_id`, `location_id` and `legacy_scope_wide` to
-- `approval_policies` and replaced `uq_approval_policy_version` with five partial
-- unique indexes. None of them serves a two-column reference, so the unique added
-- here is a new one rather than a reuse; a foreign key must reference a unique
-- constraint on exactly its own columns, and a partial index cannot be referenced
-- at all.

ALTER TABLE audit.approval_policies
    ADD COLUMN owner_tenant_id uuid
        GENERATED ALWAYS AS (
            coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid)
        ) STORED;

COMMENT ON COLUMN audit.approval_policies.owner_tenant_id IS
    'V0088. The policy''s owner made total: its tenant, or the nil uuid for a PLATFORM-scope policy under ADR 0027. Exists so a reference can name an owner rather than an absence.';

ALTER TABLE audit.approval_policies
    ADD CONSTRAINT uq_approval_policy_owner_identity UNIQUE (owner_tenant_id, id);

DO $$
DECLARE
    offending bigint;
BEGIN
    SELECT count(*) INTO offending
      FROM audit.approval_requests request
      JOIN audit.approval_policies policy ON policy.id = request.policy_id
     WHERE policy.tenant_id IS NOT NULL
       AND policy.tenant_id IS DISTINCT FROM request.tenant_id;
    IF offending > 0 THEN
        RAISE EXCEPTION
            'V0088: % approval request(s) name a policy belonging to another tenant, which is '
            'another tenant''s answer to who may approve them. Nothing here will delete them. '
            'List them with: SELECT r.id, r.tenant_id, r.policy_id, p.tenant_id FROM '
            'audit.approval_requests r JOIN audit.approval_policies p ON p.id = r.policy_id '
            'WHERE p.tenant_id IS NOT NULL AND p.tenant_id IS DISTINCT FROM r.tenant_id;',
            offending;
    END IF;
END
$$;

ALTER TABLE audit.approval_requests
    ADD COLUMN policy_is_platform boolean;

UPDATE audit.approval_requests AS request
   SET policy_is_platform = (policy.tenant_id IS NULL)
  FROM audit.approval_policies AS policy
 WHERE policy.id = request.policy_id;

-- `policy_id` is NOT NULL on this table, so every row has a policy and every row
-- must say whose it is. The UPDATE above covers every row for that reason; if it
-- did not, SET NOT NULL is what would say so.
ALTER TABLE audit.approval_requests
    ALTER COLUMN policy_is_platform SET NOT NULL;

ALTER TABLE audit.approval_requests
    ADD COLUMN policy_owner_id uuid
        GENERATED ALWAYS AS (
            CASE
                WHEN policy_is_platform THEN '00000000-0000-0000-0000-000000000000'::uuid
                ELSE tenant_id
            END
        ) STORED;

COMMENT ON COLUMN audit.approval_requests.policy_is_platform IS
    'V0088. Whether the snapshotted policy is the PLATFORM-scope one ADR 0030 resolution falls back to. fk_approval_request_policy makes the claim true.';

COMMENT ON COLUMN audit.approval_requests.policy_owner_id IS
    'V0088. Derived: the platform sentinel or this request''s own tenant, and nothing else is expressible. The tenant half of fk_approval_request_policy.';

-- This table's own `tenant_id` is nullable — a PLATFORM-scope request belongs to
-- no tenant. Such a request may only use a platform policy, and without this
-- check `policy_is_platform = false` on one of them would generate a NULL owner
-- and take the foreign key out of the check entirely.
ALTER TABLE audit.approval_requests
    ADD CONSTRAINT ck_approval_request_policy_ownership CHECK (
        policy_is_platform OR tenant_id IS NOT NULL);

ALTER TABLE audit.approval_requests
    DROP CONSTRAINT fk_approval_request_policy;

ALTER TABLE audit.approval_requests
    ADD CONSTRAINT fk_approval_request_policy
        FOREIGN KEY (policy_owner_id, policy_id)
        REFERENCES audit.approval_policies (owner_tenant_id, id);

-- ---------------------------------------------------------------------------
-- 3. fulfillment.service_zone_versions -> fulfillment.regions
-- ---------------------------------------------------------------------------
--
-- V0025's column comment is the whole argument for the nullable tenant: "Null
-- means a platform region every tenant may reference. Tashkent is not one
-- tenant's fact." A tenant may also define its own region, so the target is mixed
-- rather than tenant-free, and `ServiceZoneService.draft` passes `regionId`
-- from the request body to the insert untouched. A zone version can therefore
-- name another tenant's private region — and the region is not decorative: its
-- bounding box is what `activate` checks the polygon against, so one tenant's
-- geography would be gating another tenant's zone.

ALTER TABLE fulfillment.regions
    ADD COLUMN owner_tenant_id uuid
        GENERATED ALWAYS AS (
            coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid)
        ) STORED;

COMMENT ON COLUMN fulfillment.regions.owner_tenant_id IS
    'V0088. The region''s owner made total: its tenant, or the nil uuid for a platform region every tenant may reference. Exists so a reference can name an owner rather than an absence.';

ALTER TABLE fulfillment.regions
    ADD CONSTRAINT uq_region_owner_identity UNIQUE (owner_tenant_id, id);

DO $$
DECLARE
    offending bigint;
BEGIN
    SELECT count(*) INTO offending
      FROM fulfillment.service_zone_versions version
      JOIN fulfillment.regions region ON region.id = version.region_id
     WHERE region.tenant_id IS NOT NULL
       AND region.tenant_id <> version.tenant_id;
    IF offending > 0 THEN
        RAISE EXCEPTION
            'V0088: % service zone version(s) name a region belonging to another tenant. '
            'Nothing here will clear the pointer. List them with: SELECT v.id, v.tenant_id, '
            'v.region_id, r.tenant_id FROM fulfillment.service_zone_versions v JOIN '
            'fulfillment.regions r ON r.id = v.region_id WHERE r.tenant_id IS NOT NULL AND '
            'r.tenant_id <> v.tenant_id;', offending;
    END IF;
END
$$;

ALTER TABLE fulfillment.service_zone_versions
    ADD COLUMN region_is_platform boolean;

UPDATE fulfillment.service_zone_versions AS version
   SET region_is_platform = (region.tenant_id IS NULL)
  FROM fulfillment.regions AS region
 WHERE region.id = version.region_id;

ALTER TABLE fulfillment.service_zone_versions
    ADD COLUMN region_owner_id uuid
        GENERATED ALWAYS AS (
            CASE
                WHEN region_id IS NULL THEN NULL
                WHEN region_is_platform THEN '00000000-0000-0000-0000-000000000000'::uuid
                ELSE tenant_id
            END
        ) STORED;

COMMENT ON COLUMN fulfillment.service_zone_versions.region_is_platform IS
    'V0088. Whether the named region is a platform region (V0025: "Tashkent is not one tenant''s fact"). Null exactly when no region is named; fk_zone_version_region makes the claim true.';

COMMENT ON COLUMN fulfillment.service_zone_versions.region_owner_id IS
    'V0088. Derived: the platform sentinel or this version''s own tenant, and nothing else is expressible. The tenant half of fk_zone_version_region.';

ALTER TABLE fulfillment.service_zone_versions
    ADD CONSTRAINT ck_zone_version_region_ownership_declared CHECK (
        (region_id IS NULL) = (region_is_platform IS NULL));

ALTER TABLE fulfillment.service_zone_versions
    DROP CONSTRAINT fk_zone_version_region;

ALTER TABLE fulfillment.service_zone_versions
    ADD CONSTRAINT fk_zone_version_region
        FOREIGN KEY (region_owner_id, region_id)
        REFERENCES fulfillment.regions (owner_tenant_id, id);

-- ---------------------------------------------------------------------------
-- 4. tenant.policy_current -> tenant.policies
-- ---------------------------------------------------------------------------
--
-- The narrowest of the four, and the only one that needs no declared boolean,
-- because `policy_current` already carries the scope it is the pointer for.
--
-- `JdbcPolicyResolver.resolve` selects every `policy_current` row in the caller's
-- chain and then picks the most specific *level*: the PLATFORM row is what a
-- tenant falls back to, and the TENANT row is the tenant's own override. So a
-- TENANT-scope pointer for tenant A does not mean "A's current policy, wherever
-- it comes from" — it means "the policy A activated at tenant scope", and it is
-- the resolver, not the pointer, that reaches past it to the platform. Ownership
-- therefore has to agree exactly, and `coalesce(tenant_id, PLATFORM)` on both
-- sides says so with no third value to declare.
--
-- The allowlist called this one unreachable, and on today's code it is: nothing
-- in Java writes this table at all — V0012 and the local fixtures are the only
-- writers, and the activation path ADR 0030 describes has not been built. That is
-- the argument for doing it in the schema and nowhere else. The service that will
-- eventually write this table does not exist yet, so a rule that lived in that
-- service would be a rule nobody can read today and nobody will remember to write
-- tomorrow; the constraint is here before its first caller is.

ALTER TABLE tenant.policies
    ADD COLUMN owner_tenant_id uuid
        GENERATED ALWAYS AS (
            coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid)
        ) STORED;

COMMENT ON COLUMN tenant.policies.owner_tenant_id IS
    'V0088. The policy''s owner made total: its tenant, or the nil uuid for the ADR 0030 platform default that a tenant override sits on top of.';

ALTER TABLE tenant.policies
    ADD CONSTRAINT uq_policy_owner_identity UNIQUE (owner_tenant_id, id);

DO $$
DECLARE
    offending bigint;
BEGIN
    SELECT count(*) INTO offending
      FROM tenant.policy_current current_pointer
      JOIN tenant.policies policy ON policy.id = current_pointer.policy_id
     WHERE policy.tenant_id IS DISTINCT FROM current_pointer.tenant_id;
    IF offending > 0 THEN
        RAISE EXCEPTION
            'V0088: % activation pointer(s) name a policy owned by a different scope than the '
            'pointer itself. Nothing here will repoint them. List them with: SELECT c.key_code, '
            'c.scope_type, c.tenant_id, c.policy_id, p.tenant_id FROM tenant.policy_current c '
            'JOIN tenant.policies p ON p.id = c.policy_id WHERE p.tenant_id IS DISTINCT FROM '
            'c.tenant_id;', offending;
    END IF;
END
$$;

ALTER TABLE tenant.policy_current
    ADD COLUMN owner_tenant_id uuid
        GENERATED ALWAYS AS (
            coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid)
        ) STORED;

COMMENT ON COLUMN tenant.policy_current.owner_tenant_id IS
    'V0088. The pointer''s own scope made total, so fk_policy_current_policy can require the policy it names to be owned by exactly that scope rather than by anybody on the platform.';

ALTER TABLE tenant.policy_current
    DROP CONSTRAINT fk_policy_current_policy;

ALTER TABLE tenant.policy_current
    ADD CONSTRAINT fk_policy_current_policy
        FOREIGN KEY (owner_tenant_id, policy_id)
        REFERENCES tenant.policies (owner_tenant_id, id);

-- ---------------------------------------------------------------------------
-- 5. Why all four stay on the allowlist
-- ---------------------------------------------------------------------------
--
-- `tools/checks/tenant_scoped_references.py` and
-- `TenantScopedReferenceCatalogTests` both ask one question of a foreign key: do
-- its columns, or the columns it references, include one spelled `tenant_id`?
-- None of the four keys above does — they name `owner_tenant_id`,
-- `policy_owner_id`, `approval_request_owner_id`, `region_owner_id` — so both
-- checks still report all four, and removing their allowlist lines would fail the
-- build with "references ... without tenant_id".
--
-- The lines therefore stay, with their reasons rewritten to say where the
-- guarantee now lives. That is the honest outcome and not a workaround: the
-- checks are a text-and-catalog heuristic for "did somebody remember the tenant",
-- and a derived owner column is a shape they were not written to recognise.
-- Teaching them to recognise it means teaching them which derived columns are
-- ownership columns, which is a judgement, and a judgement is what the file's
-- reason field is for.

COMMENT ON CONSTRAINT fk_cutover_approval_request ON migration.cutover_decisions IS
    'V0088. Tenant-checked through derived ownership: the cited approval request must be the platform''s or this decision''s own tenant''s, and the generated key column can express no third answer.';

COMMENT ON CONSTRAINT fk_approval_request_policy ON audit.approval_requests IS
    'V0088. Tenant-checked through derived ownership: the snapshotted policy must be the platform''s or this request''s own tenant''s, which is exactly what ADR 0030 resolution can return.';

COMMENT ON CONSTRAINT fk_zone_version_region ON fulfillment.service_zone_versions IS
    'V0088. Tenant-checked through derived ownership: the named region must be a platform region or this tenant''s own.';

COMMENT ON CONSTRAINT fk_policy_current_policy ON tenant.policy_current IS
    'V0088. Tenant-checked through derived ownership, and exactly rather than by disjunction: an activation pointer names the policy activated at its own scope, because the resolver — not the pointer — is what reaches past it to the platform default.';
