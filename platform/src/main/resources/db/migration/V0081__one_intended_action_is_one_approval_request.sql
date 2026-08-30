-- ADR 0027: N signatures must not buy N executions.
--
-- V0071 made an approval single-use, and the property it delivered holds per
-- ROW. It does not hold per ACTION, because nothing stopped one intended action
-- from becoming several rows. JdbcApprovalService.requireApproval does a SELECT
-- (findRequest) and then an INSERT (createRequest) under READ COMMITTED, and
-- V0007 line 156 created only the NON-unique
--   ix_approval_request_action ON (action_code, parameters_hash)
-- so four threads issuing the identical command all read "nothing live" and all
-- inserted: four PENDING rows for one refund. A checker looking at that queue
-- sees four entries with the same action code and the same hash and no way to
-- tell they are one action; approving two of them, which is the natural reading
-- of "this refund needs two approvers", hands the maker two spendable
-- signatures. Three sequential requireApproval + consume() attempts then returned
-- Approved, Approved, Pending: the identical refund executed twice.
--
-- ---------------------------------------------------------------- the predicate
--
-- The key is (tenant_id, action_code, parameters_hash) — the same three columns
-- findRequest matches on, because a uniqueness rule over a different set from the
-- lookup would leave the lookup free to find a row the rule never governed.
--
-- The predicate is `status = 'PENDING'`, and the reasoning is worth writing down
-- because two wider predicates look more correct and are not.
--
-- It cannot mention expires_at. An index predicate must be immutable and now()
-- is not, so "live" in the sense findRequest means it — unexpired as well as
-- undecided — is not expressible here at all. The time half is discharged by
-- moving a lapsed row to EXPIRED, which is what expiry means, rather than by
-- leaving it PENDING and hoping a predicate excludes it. JdbcApprovalService now
-- does that for the exact key it is about to insert, in the same transaction, so
-- next week's legitimate request for the same action does not depend on the
-- background sweeper having run. That keeps ApprovalExpirySweeper's stated
-- position true — correctness does not depend on it, and no caller pays for a
-- bulk update at random, because the update is keyed on one action's three
-- columns.
--
-- It does not need to include APPROVED or DECLINED. Every row enters this table
-- as PENDING: createRequest is the only INSERT and it writes the literal
-- 'PENDING'. So uniqueness at birth is uniqueness for the row's whole life, and
-- a second row for a key whose first row is APPROVED or DECLINED and unexpired
-- cannot be created anyway — findRequest returns that row and the caller never
-- reaches the INSERT. Including them would additionally require widening
-- ck_approval_request_decision, since an APPROVED row that lapsed unspent could
-- then have to become EXPIRED while carrying a decided_by that the constraint
-- forbids on EXPIRED. Paying for that to forbid a row that no code path can
-- write is not defence in depth; it is a second rule to keep consistent with the
-- first.
--
-- NULLS NOT DISTINCT because tenant_id is null for a PLATFORM-scope request, and
-- under the default NULLS DISTINCT every platform request would be unique to
-- itself and the rule would apply to no platform action at all. findRequest
-- already matches those rows with IS NOT DISTINCT FROM; this is the index saying
-- the same thing.
--
-- No new table, so no new GRANT: V0007 granted the application role table-level
-- INSERT, SELECT and UPDATE on audit.approval_requests, and an index needs no
-- privilege of its own.
--
-- Not CONCURRENTLY: Flyway runs a migration in a transaction, the table is
-- request-rate small, and an exclusive lock held for the length of one index
-- build on it is shorter than the deploy that carries it.

-- Duplicates that already exist would fail the index build, so they are settled
-- first. The earliest request per key survives — it is the one a checker is most
-- likely already looking at, and it is the request the maker actually intended;
-- the rest are the accidents of the race. They become EXPIRED rather than being
-- deleted, because an audit table does not lose rows: an investigator has to be
-- able to see that four requests existed and what became of them. EXPIRED is
-- legal for them without touching ck_approval_request_decision, since a PENDING
-- row has no decision to preserve.
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY tenant_id, action_code, parameters_hash
               ORDER BY requested_at, id) AS position
      FROM audit.approval_requests
     WHERE status = 'PENDING'
)
UPDATE audit.approval_requests AS request
   SET status = 'EXPIRED',
       version = version + 1
  FROM ranked
 WHERE request.id = ranked.id
   AND ranked.position > 1;

CREATE UNIQUE INDEX uq_approval_request_pending_action
    ON audit.approval_requests (tenant_id, action_code, parameters_hash)
    NULLS NOT DISTINCT
    WHERE status = 'PENDING';

COMMENT ON INDEX audit.uq_approval_request_pending_action IS
    'ADR 0027: one intended action is one request. Every row is born PENDING, so uniqueness over PENDING rows is uniqueness over the action. A lapsed request is moved to EXPIRED — by the sweeper, or by requireApproval for its own key — which is what frees the action to be requested again.';
