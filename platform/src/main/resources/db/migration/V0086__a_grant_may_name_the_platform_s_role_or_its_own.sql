-- A grant may name the platform's role or its own tenant's, and nothing else.
--
-- V0008 wrote this comment above `ck_role_ownership`:
--
--     -- A platform-defined role is owned by nobody; a tenant-defined role must
--     -- name its tenant, so one tenant can never see another's custom role.
--
-- The CHECK it sits on is true. The sentence after the comma is not, and the
-- reference two tables down is what makes it false:
--
--     CONSTRAINT fk_grant_role FOREIGN KEY (role_id) REFERENCES iam.roles (id)
--
-- on the id alone. Nothing in the schema relates the role's owner to the grant's
-- tenant, so a grant in tenant B may name a role tenant A defined privately, and
-- `JdbcAuthorizationService` will then resolve that role's capabilities inside
-- tenant B. A grant is the authorization primitive of the whole platform; this
-- is the one reference in `known_tenant_blind_references.tsv` where the
-- consequence is another tenant's capability set, not a dangling pointer.
--
-- Reproduced before it was fixed, in GrantManagementServiceTests: an INSERT of a
-- grant in tenant B naming tenant A's custom role was accepted, and
-- `authorization.has("intruder", ORDER_CANCEL, tenant(B))` then answered true.
--
-- ---------------------------------------------------------------------------
-- Why this is not a composite foreign key
-- ---------------------------------------------------------------------------
--
-- `iam.roles.tenant_id` is nullable by design and `ck_role_ownership` makes the
-- split explicit: a platform-defined role has tenant_id NULL, a tenant-defined
-- role must name its tenant. The target is therefore MIXED, and the rule a grant
-- needs is a disjunction:
--
--     role.tenant_id IS NULL          -- the platform's role, anyone may name it
--  OR role.tenant_id = grant.tenant_id -- or one this tenant defined
--
-- A foreign key is an equality between two column lists. It cannot express "or".
-- Three shapes were considered and rejected before this one:
--
--   1. `FOREIGN KEY (role_id, tenant_id) REFERENCES iam.roles (id, tenant_id)`.
--      Under MATCH SIMPLE a NULL anywhere in the key stops the check entirely,
--      so every platform-role grant — which is every grant that exists — would
--      become unchecked, while a tenant-role grant naming a foreign role would
--      still have to be caught by something else. MATCH FULL is worse: it
--      forbids a partly-NULL key outright, so the platform case becomes
--      impossible rather than unchecked.
--
--   2. A sentinel: a generated `owner_tenant_id` on `iam.roles` holding
--      `coalesce(tenant_id, '00...0')` — the same nil-uuid idiom `uq_grant_active`
--      already uses for a NULL scope_id — with a matching column on `iam.grants`
--      and a two-column key onto it, plus a CHECK that the copy is either the nil
--      uuid or this grant's own tenant. That shape does hold, and it is the one
--      to reach for if this table ever needs the guarantee to survive a role
--      being re-parented while grants are being written concurrently. It was not
--      taken here because the copy on `iam.grants` has to be filled by something,
--      and the only honest filler is a trigger that reads the role row — at which
--      point the trigger below is doing the work and the column, the backfill,
--      the generated column, the unique constraint and the dropped-and-re-added
--      foreign key are ceremony around it. It would also rename the reference,
--      and `tools/checks/tenant_scoped_references.py` matches the column name
--      `tenant_id` exactly, so `fk_grant_role_owner (role_id, role_owner_tenant_id)`
--      would be reported as a NEW tenant-blind reference needing a NEW allowlist
--      entry — a file that may only shrink, growing.
--
--   3. Leaving it to `GrantManagementService`. That is where the rule belongs and
--      it is added there in the same change, but a rule that lives only in one
--      service is a rule that holds until the second writer. V0069 and V0077 both
--      say this in their own words: the constraint is what holds when the
--      resolved path is bypassed by a background job, a repair script, or a write
--      path nobody has written yet. The test above wrote the row with plain SQL.
--
-- So: a trigger, which is the one thing in PostgreSQL that can read the other row
-- and express the disjunction. It is a backstop, not the primary control — the
-- service refuses first, and refuses without saying which of "no such role" and
-- "not your role" happened.

-- ---------------------------------------------------------------------------
-- 1. Refuse the deployment rather than delete a grant
-- ---------------------------------------------------------------------------
--
-- There should be no violating row: `RoleRegistrySynchronizer` writes only
-- platform roles, `GrantManagementService` resolves only platform role codes,
-- and nothing else writes this table. That is an argument, not a fact about this
-- database, so it is checked rather than assumed.
--
-- A grant is somebody's access. Deleting one silently is how a shift arrives to
-- find it cannot open a till, and it destroys the evidence of how the row got
-- there. The migration stops instead, and an operator adjudicates each row with
-- the grant in front of them. The message carries a count and no identifiers:
-- it reaches deployment logs, and a tenant id in a log is a tenant boundary in a
-- log.
DO $$
DECLARE
    offending bigint;
BEGIN
    SELECT count(*) INTO offending
      FROM iam.grants g
      JOIN iam.roles r ON r.id = g.role_id
     WHERE r.tenant_id IS NOT NULL
       AND r.tenant_id IS DISTINCT FROM g.tenant_id;

    IF offending > 0 THEN
        RAISE EXCEPTION
            '% grant(s) name a role owned by another tenant; this migration will '
            'not delete a grant. Adjudicate each row (join iam.grants to iam.roles '
            'on role_id where roles.tenant_id is distinct from grants.tenant_id), '
            'revoke or re-point it, then re-run.', offending
            USING ERRCODE = 'raise_exception';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 2. The rule, in the schema
-- ---------------------------------------------------------------------------

CREATE FUNCTION iam.assert_grant_names_a_visible_role() RETURNS trigger
    LANGUAGE plpgsql
    -- Not SECURITY DEFINER: this needs no privilege the writer does not have —
    -- every role the application may write a grant for, it may already read. The
    -- search_path is pinned anyway, with pg_temp last, so a temporary table named
    -- `roles` cannot become the table this rule consults.
    SET search_path = pg_catalog, iam, pg_temp
AS $$
DECLARE
    role_owner uuid;
BEGIN
    SELECT r.tenant_id INTO role_owner FROM iam.roles r WHERE r.id = NEW.role_id;

    -- No such role at all: fk_grant_role refuses it in its own words a moment
    -- from now. Saying so here would only replace the foreign key's message with
    -- a worse one.
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    -- role_owner IS NULL is the platform's role: every tenant may name it, and
    -- this is the branch a composite key could not have kept.
    IF role_owner IS NOT NULL AND role_owner IS DISTINCT FROM NEW.tenant_id THEN
        RAISE EXCEPTION
            'A grant may name a platform role or a role in its own tenant'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END $$;

COMMENT ON FUNCTION iam.assert_grant_names_a_visible_role() IS
    'V0086: the half of fk_grant_role a foreign key cannot express — the role a '
    'grant names must be the platform''s or the grant''s own tenant''s. The '
    'message names neither the role nor the tenant: a caller who learns that a '
    'role belongs to somebody else has learned that it exists.';

CREATE TRIGGER trg_grant_names_a_visible_role
    BEFORE INSERT OR UPDATE OF role_id, tenant_id ON iam.grants
    FOR EACH ROW EXECUTE FUNCTION iam.assert_grant_names_a_visible_role();

-- ---------------------------------------------------------------------------
-- 3. And from the other side
-- ---------------------------------------------------------------------------
--
-- A write-time check alone leaves the invariant able to lapse without a write to
-- `iam.grants` at all: move a role to another tenant, or hand a platform role an
-- owner, and every grant already pointing at it becomes a cross-tenant grant
-- that nothing revisits. Nothing in the application does this today — the
-- synchronizer's ON CONFLICT DO UPDATE never lists tenant_id, so this trigger
-- does not fire on startup — which is exactly the condition under which such a
-- rule is written and then quietly relied upon.
CREATE FUNCTION iam.assert_role_reparenting_leaves_no_grant_behind() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, iam, pg_temp
AS $$
DECLARE
    stranded bigint;
BEGIN
    IF NEW.tenant_id IS NOT DISTINCT FROM OLD.tenant_id THEN
        RETURN NEW;
    END IF;

    SELECT count(*) INTO stranded
      FROM iam.grants g
     WHERE g.role_id = NEW.id
       AND (NEW.tenant_id IS NOT NULL AND NEW.tenant_id IS DISTINCT FROM g.tenant_id);

    IF stranded > 0 THEN
        RAISE EXCEPTION
            'This role is named by % grant(s) that its new owner does not own; '
            'revoke them before re-parenting the role', stranded
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END $$;

COMMENT ON FUNCTION iam.assert_role_reparenting_leaves_no_grant_behind() IS
    'V0086: the same rule, held from the roles side, so the invariant cannot be '
    'broken by moving the role instead of by writing the grant.';

CREATE TRIGGER trg_role_reparenting_leaves_no_grant_behind
    BEFORE UPDATE OF tenant_id ON iam.roles
    FOR EACH ROW EXECUTE FUNCTION iam.assert_role_reparenting_leaves_no_grant_behind();

-- ---------------------------------------------------------------------------
-- 4. What is still not closed
-- ---------------------------------------------------------------------------
--
-- `fk_grant_role` is still a one-column reference, and it still appears in
-- tools/checks/known_tenant_blind_references.tsv, because the reference has not
-- changed and the file refuses an entry for a reference that no longer exists.
-- Its reason is rewritten there to say what now enforces the rule instead. Two
-- things it deliberately does not claim:
--
--   * the trigger is checked per row at write time, so it cannot be read off
--     `pg_constraint` the way a composite key can. Whoever next audits this
--     schema by enumerating foreign keys will still see a tenant-blind one, and
--     the allowlist entry is where they find out why.
--   * `fk_grant_role` still answers "is this uuid a role somewhere on the
--     platform" — for a caller who can reach a raw INSERT, which is nobody
--     through the API, since GrantManagementService takes a role *code* and
--     resolves it inside the caller's own tenant.
COMMENT ON CONSTRAINT fk_grant_role ON iam.grants IS
    'One column by necessity: iam.roles is mixed-ownership, so a composite key '
    'would have to choose between the platform''s roles and the tenant''s. '
    'trg_grant_names_a_visible_role (V0086) holds the half this cannot.';
