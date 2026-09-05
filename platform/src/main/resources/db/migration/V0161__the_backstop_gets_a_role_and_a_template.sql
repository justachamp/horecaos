-- ADR 0056: PostgreSQL row-level security as a backstop beneath the
-- application-enforced tenant isolation this platform already runs on.
--
-- This migration introduces the two pieces every later "turn RLS on for one
-- more schema" migration will reuse, and changes the behaviour of nothing
-- that exists today: no policy is created here, so no query anywhere runs
-- any differently after this migration than before it. That separation is
-- deliberate. Creating the escape hatch before there is anything to escape
-- from means it can be proven in isolation, and the schema that first turns
-- a policy on (V0162) is reviewed for that alone.
--
-- ---------------------------------------------------------------------------
-- The exempt role
-- ---------------------------------------------------------------------------
--
-- A legitimate cross-tenant reader or writer -- the outbox relay, a day-close
-- rollup, a reporting projection, a control-plane operation, and today the
-- one inventory has: JdbcInventoryStore.expireReservations, which sweeps
-- every tenant's stale holds in a single UPDATE -- has to keep working once
-- a table it touches enforces "this session's tenant, and only this
-- session's tenant". PostgreSQL's answer is BYPASSRLS, and BYPASSRLS is
-- all-or-nothing: there is no grant that says "bypass row-level security on
-- this table but not that one." So the narrowing this platform needs cannot
-- live in what the role is granted on. It lives in who may become the role,
-- and for how long:
--
--   * horecaos_app holds membership in horecaos_platform_bypass WITHOUT
--     INHERIT. An ordinary connection is therefore completely unaffected by
--     this role existing at all -- BYPASSRLS applies only once a transaction
--     explicitly issues SET LOCAL ROLE horecaos_platform_bypass
--     (TenantRlsSession.bindPlatform), and SET LOCAL is scoped to that one
--     transaction by PostgreSQL itself. A pooled connection that runs a
--     bypassing transaction and is then handed to the next, unrelated
--     request reverts to being an ordinary horecaos_app session at COMMIT or
--     ROLLBACK, before that next request's first statement — the same
--     transaction-scoping property the tenant GUC itself relies on, and for
--     the identical reason: a setting that outlived its transaction on a
--     pooled connection would be a leak, not a backstop.
--
--   * horecaos_platform_bypass holds membership in horecaos_application
--     WITH INHERIT (the default), so that once a session assumes it, it can
--     actually reach the tables horecaos_app can reach -- BYPASSRLS lifts a
--     restriction, it does not grant a privilege that was never held.
--
-- horecaos_migrator is deliberately not part of any of this. It owns every
-- table an ADR 0056 policy will ever be created on, and PostgreSQL exempts a
-- table's OWNER from that table's own row-level security automatically --
-- no FORCE ROW LEVEL SECURITY, no BYPASSRLS, no grant of any kind. V0162
-- explains why FORCE is deliberately never used: it would undo exactly this,
-- and the migrator and every maintenance path that runs as it (a backfill, a
-- SECURITY DEFINER partition job) needs to stay exactly as unscoped as it is
-- today.
--
-- horecaos_app itself is named nowhere below, on purpose and for the same
-- reason V0007 does not name it either: that login role is created only by
-- infra/production/postgres-init/10-application-role.sh, which runs before
-- Flyway on a real deployment and never runs at all against a Testcontainers
-- database — a migration that referenced it by name would migrate cleanly
-- in production and fail every test in this repository. The membership that
-- needs horecaos_app to exist — GRANT horecaos_platform_bypass TO
-- horecaos_app WITH INHERIT FALSE — lives in that script, next to the
-- identical GRANT horecaos_application TO horecaos_app it already makes.
-- horecaos_platform_bypass is created there too, guarded exactly like
-- horecaos_application and horecaos_reporting_read already are, so that
-- whichever of the script or this migration runs first, the other is a
-- no-op rather than a "role already exists" failure.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'horecaos_platform_bypass') THEN
        CREATE ROLE horecaos_platform_bypass NOLOGIN BYPASSRLS;
    END IF;
END
$$;

GRANT horecaos_application TO horecaos_platform_bypass;

COMMENT ON ROLE horecaos_platform_bypass IS
    'ADR 0056. The policy-exempt role for a legitimate cross-tenant reader or '
    'writer -- the outbox relay, day-close, a reporting rollup, a '
    'control-plane operation. horecaos_app holds membership WITHOUT INHERIT, '
    'so bypassing row-level security requires an explicit '
    'SET LOCAL ROLE horecaos_platform_bypass inside the one transaction that '
    'needs it (see uz.horecaos.platform.configuration.rls), and never '
    'happens by default or by accident of ordinary connection setup.';

-- ---------------------------------------------------------------------------
-- The policy template
-- ---------------------------------------------------------------------------
--
-- Every tenant-owned table this platform ever turns row-level security on
-- for gets exactly the same shape: enabled, and one policy whose rows are
-- exactly those whose tenant_id matches the session's bound tenant. Stating
-- that once, as a function every enabling migration calls, is what keeps the
-- predicate's wording from drifting between tables the way this platform's
-- own history says it eventually would -- V0035's nine forgotten GRANTs and
-- V0080's search_path gap are both the same lesson: a rule copy-pasted once
-- per table is a rule that is eventually copy-pasted wrong.
--
-- Not SECURITY DEFINER, and not granted to horecaos_application. It runs
-- DDL, and DDL runs only inside a migration, as horecaos_migrator, who
-- already owns every table it will ever be pointed at. Exposing it to the
-- application at runtime would be the exact mistake V0075 and V0070 already
-- cost this platform once: a schema change reachable from a connection that
-- is supposed to hold none.
CREATE FUNCTION platform.enable_tenant_row_level_security(target regclass) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
    EXECUTE format($policy$
        CREATE POLICY tenant_isolation ON %s
            USING (tenant_id = NULLIF(current_setting('horecaos.tenant_id', true), '')::uuid)
        $policy$, target);
END;
$$;

COMMENT ON FUNCTION platform.enable_tenant_row_level_security(regclass) IS
    'ADR 0056 policy template. Enables row-level security on target and adds '
    'the one policy every tenant-owned table gets: visible and writable rows '
    'are exactly those whose tenant_id equals horecaos.tenant_id, the '
    'per-transaction setting uz.horecaos.platform.configuration.rls binds. '
    'current_setting(..., true) answers NULL rather than raising for a '
    'session nothing has bound yet, and NULLIF(...,'''') guards the same '
    'case if something ever binds an empty string, so the fail-closed '
    'direction is: no tenant bound means no row visible, never every row. '
    'A table whose tenant_id is nullable because it is shared between '
    'platform-defined and tenant-defined rows (iam.roles, iam.grants) needs '
    'a different predicate — tenant_id = :guc OR tenant_id IS NULL — and '
    'must not be pointed at this function unmodified.';
