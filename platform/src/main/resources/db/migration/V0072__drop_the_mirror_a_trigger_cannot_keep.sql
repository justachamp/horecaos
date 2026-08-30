-- ADR 0015: the third copy of "which row is current", and the column that copy
-- existed to maintain.
--
-- V0063 removed two copies of the rule that decides which
-- tenant.customer_identity_policies row governs now -- one in
-- ConfiguredCustomerPolicyLookup, one in
-- JdbcTenantControlPlaneStore.findCurrentCustomerIdentityMode -- and replaced
-- them with tenant.current_customer_identity_policy(uuid, timestamptz), "so the
-- rule lives once, in the schema that owns the table, where neither module can
-- drift from it -- the same reason V0060 put the mirror in a trigger instead of
-- a repository method."
--
-- It left a third copy inside the very trigger it cited as precedent.
-- tenant.mirror_customer_identity_mode() gates on `IF NEW.superseded_at IS NULL`
-- with no test of effective_from, so it mirrors the newest row rather than the
-- current one. With a cutover scheduled for 2026-09-01, at 2026-08-21 the
-- function answers version 1 / BRAND_ISOLATED -- correctly -- while
-- tenant.tenants.customer_identity_policy already reads TENANT_SHARED. Wrong for
-- the whole eleven-day scheduling window, in a column that V0060's own
-- COMMENT ON COLUMN, ConfiguredCustomerPolicyLookup's javadoc and ADR 0002 all
-- describe as the mirror of the *current* row.

-- Teaching the trigger to call the function would remove the third copy of the
-- predicate and would still leave the column wrong, because this column is not
-- fixable by any trigger.
--
-- A trigger fires on a write. "Which row is current" changes with the passage of
-- time and no write at all: the scheduled cutover above becomes current at
-- midnight on the first, when nothing touches either table. Whatever predicate
-- the trigger evaluates, it evaluates at insert time and stores the answer, and
-- the answer has an expiry date that a varchar column cannot express.
--
--   * as written today, the mirror is wrong from the moment the future-dated row
--     is inserted until the cutover;
--   * corrected to consult tenant.current_customer_identity_policy(tenant, now()),
--     it would be right for that window and wrong from the cutover onwards, until
--     some later write to the policy table happened to refresh it.
--
-- Neither is a mirror. The second is the more dangerous of the two, because it
-- would be wrong only sometimes and would look like it worked in every test that
-- does not schedule a cutover -- which is how this column survived V0060 and
-- V0063 in the first place.
--
-- Nor can the schema express it another way: a generated column must be
-- immutable, and a value that depends on the current instant is the opposite of
-- immutable. The only honest form of this answer is a function of (tenant,
-- instant), which is what V0063 already created, and the only honest form of the
-- question is to ask it at the instant you mean. So the column goes, with the
-- trigger and the trigger function that fed it.
--
-- Safe to drop. Nothing in src/main reads it: identity resolution has read the
-- versioned table since V0060, and V0063 gave both modules the function. No view
-- or other object depends on it either -- plain DROP COLUMN is RESTRICT by
-- default, so an installation where something does depend on it stops the
-- deployment here and names the dependency rather than cascading it away.
--
-- What is deliberately NOT touched is V0060's step-1 guard, which refuses a
-- deployment that would re-partition customer accounts that already exist. That
-- guard carries the same defect -- superseded_at IS NULL with no test of
-- effective_from, so it compares existing partitions against whatever mode is
-- newest rather than whatever mode is in force -- but it is an applied migration
-- and Flyway is append-only. Its consequence is bounded: it ran once, against
-- the data of that one deployment. The live danger was that it is the in-tree
-- precedent a future migration would copy, and after this migration the only
-- copy of the predicate left to reach for is the right one:
--
--   SELECT policy_version, identity_mode
--   FROM tenant.current_customer_identity_policy(<tenant>, <the instant you mean>)
--
-- with the instant named rather than now(), for the reason V0063 parameterised
-- it: the caller's clock is the authority on what time it is.
--
-- No table is created and no privilege changes, so there is no new GRANT. The
-- application role's UPDATE on tenant.tenants (V0035) stays as it is; it simply
-- has one fewer column it could write and never did.

DROP TRIGGER IF EXISTS trg_customer_identity_policy_mirror
    ON tenant.customer_identity_policies;

DROP FUNCTION IF EXISTS tenant.mirror_customer_identity_mode();

ALTER TABLE tenant.tenants
    DROP COLUMN customer_identity_policy;
