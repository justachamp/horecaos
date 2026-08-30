-- ADR 0015: customer.customer_accounts.identity_policy_version must name a real
-- policy version, and "which policy row is current" must be defined once.
--
-- V0017 gave the column its purpose in a comment: "Which policy version created
-- this account, so a later policy change is a governed migration with a known
-- starting point rather than a reinterpretation." The writer never honoured it.
-- JdbcCustomerStore bound `policy.ordinal() + 1` -- the ordinal of the
-- CustomerIdentityPolicy *enum*, TENANT_SHARED=0 and BRAND_ISOLATED=1 -- so the
-- column recorded which mode was in force, not which version of the tenant's
-- policy said so. Two different facts that happened to share a small integer.
--
-- It was right by accident while the V0060 bug existed. Every tenant resolved
-- TENANT_SHARED whatever they had configured, so every account stamped 0 + 1 = 1,
-- which matched the version 1 row every tenant sits on. Fixing resolution
-- activated the defect: an account created in a tenant that really is
-- BRAND_ISOLATED now stamps 2, a version that does not exist, and a tenant taken
-- to version 3 by a governed change still stamps 1 or 2. Either way the column
-- can no longer tell an account created before a migration from one created
-- after -- the single question it exists to answer.
--
-- The writer is fixed in Java: the version is carried from the policy row that
-- was read to the account row that is written. This migration deals with the
-- three things the writer alone cannot.
--
-- No new table, so no new GRANT for a table. The application role already holds
-- SELECT on tenant.customer_identity_policies and INSERT/UPDATE on
-- customer.customer_accounts (V0017, V0035); the one new object that needs a
-- privilege is the function in step 4, and it is granted there.

-- 1. Let the column say "no governed policy existed".
--
-- ConfiguredCustomerPolicyLookup deliberately defaults a tenant that has never
-- configured an identity mode to TENANT_SHARED -- the safer of the two, because
-- a shared account can be split by a governed migration whereas wrongly isolated
-- accounts leave a customer unable to prove which accounts are theirs. Such a
-- tenant has no policy row and therefore no version, and the honest record of
-- that is NULL. Writing 1 is the same category of lie the ordinal was: it names
-- a version that was never decided, and a later migration reading it would
-- believe the tenant had governed itself into TENANT_SHARED when nobody chose
-- anything.
--
-- The DEFAULT goes with the NOT NULL. Left behind it would keep quietly
-- supplying 1 to any INSERT that omits the column, which is exactly the silent
-- wrong answer being removed.
ALTER TABLE customer.customer_accounts
    ALTER COLUMN identity_policy_version DROP NOT NULL,
    ALTER COLUMN identity_policy_version DROP DEFAULT;

-- 2. Refuse rather than guess.
--
-- Step 3 rewrites each account's version to the policy that was actually in
-- effect when the account was created. An account whose tenant has policy rows
-- but none covering its created_at cannot be mapped: the recorded history begins
-- after the customer does. That is a real inconsistency in the policy history --
-- almost certainly a row inserted with a retroactive effective_from -- and
-- picking the earliest version for it would be a reinterpretation, which is the
-- failure this column exists to prevent. Same stance as V0060 took on the same
-- table: stop, name the tenants, let an operator decide.
DO $$
DECLARE
    unmappable integer;
    sample text;
BEGIN
    WITH orphaned AS (
        SELECT a.id, a.tenant_id
        FROM customer.customer_accounts a
        WHERE EXISTS (
                SELECT 1 FROM tenant.customer_identity_policies p
                WHERE p.tenant_id = a.tenant_id)
          AND NOT EXISTS (
                SELECT 1 FROM tenant.customer_identity_policies p
                WHERE p.tenant_id = a.tenant_id
                  AND p.effective_from <= a.created_at
                  AND (p.superseded_at IS NULL OR p.superseded_at > a.created_at))
    )
    -- The count is over every unmappable account; only the listing is capped, so
    -- an operator is told the real size of the problem and not the size of the
    -- sample.
    SELECT
        (SELECT count(*) FROM orphaned),
        (SELECT string_agg(DISTINCT tenant_id::text, ', ')
         FROM (SELECT tenant_id FROM orphaned ORDER BY tenant_id LIMIT 10) capped)
    INTO unmappable, sample;

    IF unmappable > 0 THEN
        RAISE EXCEPTION
            'ADR 0015: % customer account(s) were created before any identity policy their '
            'tenant records was in effect (tenants: %). Their policy version cannot be '
            'determined without guessing. Correct the effective_from of the earliest policy '
            'row for these tenants, then redeploy.',
            unmappable, sample;
    END IF;
END $$;

-- 3. Replace the enum ordinal with the real version.
--
-- Every existing row is rewritten, not only the ones that are visibly wrong: a
-- stored 1 is indistinguishable from a correct 1, and the point is that the
-- column now derives from the policy history rather than from an enum. Accounts
-- whose tenant has no policy at all become NULL, per step 1.
--
-- Restartable and idempotent: it computes from created_at and the policy history,
-- neither of which this statement changes, so re-running it produces the same
-- rows. Bounded by the number of accounts, and the subquery rides
-- uq_customer_identity_policy_version (tenant_id, version) plus the tenant_id
-- lookup on the policy table.
UPDATE customer.customer_accounts a
SET identity_policy_version = (
        SELECT p.version
        FROM tenant.customer_identity_policies p
        WHERE p.tenant_id = a.tenant_id
          AND p.effective_from <= a.created_at
          AND (p.superseded_at IS NULL OR p.superseded_at > a.created_at)
        ORDER BY p.effective_from DESC, p.version DESC
        LIMIT 1);

-- 4. One definition of "which policy row is current".
--
-- The predicate existed in two modules -- ConfiguredCustomerPolicyLookup in
-- customers and JdbcTenantControlPlaneStore.findCurrentCustomerIdentityMode in
-- tenancy -- and both had the same defect: they matched on superseded_at IS NULL
-- alone and ignored effective_from entirely, so a policy row dated for a future
-- cutover would take effect the instant it was inserted. Two copies of one rule
-- is the shape of the bug V0060 fixed; correcting it in one copy and not the
-- other would have been that bug again, in a smaller font.
--
-- So the rule lives once, in the schema that owns the table, where neither module
-- can drift from it -- the same reason V0060 put the mirror in a trigger instead
-- of a repository method.
--
-- The instant is a parameter and never now(). A caller's clock is the authority
-- on what time it is -- that is what makes the fixed clock in a test the test's
-- clock rather than a decoration -- and a function reading the wall clock would
-- silently overrule it.
--
-- superseded_at > p_at, not >=, because supersede() closes the old row and opens
-- the next at the same instant: at exactly the changeover the new row is current
-- and the old one is not. The ORDER BY is a tiebreak for a malformed overlapping
-- history, which should surface as the wrong mode being reported and
-- investigated, not as a failed sign-in for every customer of that tenant.
CREATE OR REPLACE FUNCTION tenant.current_customer_identity_policy(
        p_tenant_id uuid,
        p_at timestamptz)
    RETURNS TABLE (policy_version integer, identity_mode text)
    LANGUAGE sql
    STABLE
AS $$
    SELECT p.version, p.identity_mode::text
    FROM tenant.customer_identity_policies p
    WHERE p.tenant_id = p_tenant_id
      AND p.effective_from <= p_at
      AND (p.superseded_at IS NULL OR p.superseded_at > p_at)
    ORDER BY p.effective_from DESC, p.version DESC
    LIMIT 1;
$$;

-- PostgreSQL grants EXECUTE on a new function to PUBLIC by default, but that
-- default is a setting an installation is entitled to revoke. The privilege this
-- application needs is stated rather than inherited.
GRANT EXECUTE ON FUNCTION tenant.current_customer_identity_policy(uuid, timestamptz)
    TO qoida_application;

COMMENT ON FUNCTION tenant.current_customer_identity_policy(uuid, timestamptz) IS
    'ADR 0015. The tenant.customer_identity_policies row in effect for a tenant at an instant: effective_from has passed and superseded_at has not. The single definition of "current" -- customers and tenancy both read through it so the rule cannot exist in two versions. Returns no row for a tenant that has configured nothing; the caller applies the TENANT_SHARED default.';

COMMENT ON COLUMN customer.customer_accounts.identity_policy_version IS
    'ADR 0015. The version of tenant.customer_identity_policies that was in effect when this account was created, so a later policy change is a governed migration from a known starting point rather than a reinterpretation. NULL means the tenant had configured no policy and the TENANT_SHARED default applied. Never derived from the identity mode: the mode says what the rule was, the version says which decision it came from.';
