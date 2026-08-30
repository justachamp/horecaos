-- ADR 0015: one answer to "does this tenant isolate customers by brand".
--
-- The mode was stored in two places and nothing kept them in step.
--
--   tenant.customer_identity_policies (V0003)  -- versioned, written by the
--       control plane when an operator creates a tenant. This is the operator's
--       actual choice, with a version history.
--   tenant.tenants.customer_identity_policy (V0017)  -- denormalised, NOT NULL
--       DEFAULT 'TENANT_SHARED', and *never written by any code in the tree*.
--
-- Identity resolution read the second one. So a tenant that configured
-- BRAND_ISOLATED through the control plane was partitioned TENANT_SHARED: one
-- person's profile, addresses and order history visible across brands that were
-- meant to be separate businesses. The control plane returned success and the
-- row it wrote was real -- just not the row anything read.
--
-- The versioned table is now the single source of truth, and
-- ConfiguredCustomerPolicyLookup reads it. This migration makes the denormalised
-- column stop being a second, silently different answer:
--
--   1. It refuses the deployment if honouring the operator's real choice would
--      re-partition customers who already exist.
--   2. It backfills the column from the versioned table.
--   3. It installs a trigger so the column can never again disagree -- it is
--      written in the same transaction as the authoritative row, by the database,
--      so no future writer can forget.
--
-- The column is kept rather than dropped only because it is not this change's to
-- remove; it is now a derived mirror that nothing in the application reads.
--
-- No new table, so no new GRANT: the application role already holds SELECT on
-- tenant.customer_identity_policies and UPDATE on tenant.tenants (V0035), which
-- is everything the new read path and the trigger need.

-- 1. Refuse to silently re-partition existing customers.
--
-- Before this migration every tenant resolved as TENANT_SHARED whatever they
-- configured. Honouring the configured mode is the fix, but for a tenant that
-- already has accounts it is also exactly the flip ADR 0015 forbids as an
-- in-place toggle: accounts created unpartitioned would stop being found under
-- BRAND_ISOLATED and the next sign-in would create a second account for a person
-- who already has one. That is a merge/split migration with duplicate discovery
-- and approval, not a deployment side effect, so the deployment stops here and
-- says so rather than quietly splitting people.
DO $$
DECLARE
    conflicted integer;
    sample text;
BEGIN
    WITH mismatched AS (
        SELECT p.tenant_id, p.identity_mode
        FROM tenant.customer_identity_policies p
        WHERE p.superseded_at IS NULL
          AND EXISTS (
              SELECT 1
              FROM customer.customer_accounts a
              WHERE a.tenant_id = p.tenant_id
                AND ((p.identity_mode = 'BRAND_ISOLATED'
                        AND a.identity_partition_brand_id IS NULL)
                  OR (p.identity_mode = 'TENANT_SHARED'
                        AND a.identity_partition_brand_id IS NOT NULL))
          )
    )
    -- The count is over every conflicted tenant; only the listing is capped, so
    -- an operator is told the real size of the problem and not the size of the
    -- sample.
    SELECT
        (SELECT count(*) FROM mismatched),
        (SELECT string_agg(tenant_id::text || ' -> ' || identity_mode, ', ')
         FROM (SELECT tenant_id, identity_mode FROM mismatched ORDER BY tenant_id LIMIT 10) capped)
    INTO conflicted, sample;

    IF conflicted > 0 THEN
        RAISE EXCEPTION
            'ADR 0015: % tenant(s) have customer accounts partitioned against their configured '
            'identity mode (first ten: %). Honouring the configured mode would split or merge '
            'existing customer accounts. Run the governed identity migration for these tenants, '
            'or supersede the policy row to the mode their data is actually in, then redeploy.',
            conflicted, sample;
    END IF;
END $$;

-- 2. Backfill the mirror from the authority.
--
-- Deliberately does not touch version or updated_at: correcting a denormalised
-- copy is not a change to the tenant, and bumping the optimistic-lock version
-- here would fail whatever in-flight control-plane write held the old one.
UPDATE tenant.tenants t
SET customer_identity_policy = p.identity_mode
FROM tenant.customer_identity_policies p
WHERE p.tenant_id = t.id
  AND p.superseded_at IS NULL
  AND t.customer_identity_policy IS DISTINCT FROM p.identity_mode;

-- 3. Keep it in step from now on, in the database rather than in a repository
-- method a future writer can bypass. The bug being fixed here was precisely an
-- application that wrote one of two stores and forgot the other.
CREATE OR REPLACE FUNCTION tenant.mirror_customer_identity_mode() RETURNS trigger AS $$
BEGIN
    -- Only the current row mirrors. Superseding a row does not change the mode;
    -- the insert of its replacement does, and that insert fires this trigger.
    IF NEW.superseded_at IS NULL THEN
        UPDATE tenant.tenants
        SET customer_identity_policy = NEW.identity_mode
        WHERE id = NEW.tenant_id
          AND customer_identity_policy IS DISTINCT FROM NEW.identity_mode;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_customer_identity_policy_mirror
    AFTER INSERT OR UPDATE ON tenant.customer_identity_policies
    FOR EACH ROW EXECUTE FUNCTION tenant.mirror_customer_identity_mode();

COMMENT ON COLUMN tenant.tenants.customer_identity_policy IS
    'ADR 0015. Derived mirror of the current tenant.customer_identity_policies row, maintained by trg_customer_identity_policy_mirror. NOT the source of truth and not read by identity resolution: read tenant.customer_identity_policies. Changing the mode is a governed migration with duplicate discovery and approval, never an in-place toggle, because flipping it silently would merge or split real people''s accounts.';
