-- ADR 0027: one signature authorises one execution, not twenty-four hours of them.
--
-- JdbcApprovalService.findRequest matched on action_code + parameters_hash +
-- tenant_id with `status <> 'EXPIRED' AND expires_at > now`, and requireApproval
-- answered Approved for that row. Nothing anywhere marked the request spent, and
-- the status vocabulary had no word for spent to write: V0007's
-- ck_approval_request_status admits PENDING, APPROVED, DECLINED and EXPIRED and
-- nothing else. So once a checker approved, the maker could execute the
-- identical action as often as they liked until the 24-hour validity lapsed, and
-- the parameters hash makes that easy rather than hard — every call site hashes
-- the business parameters and deliberately excludes the idempotency key, so a
-- resubmission with a fresh key is the same hash and the same approval answers
-- again. A 500 000-point goodwill credit was an unlimited number of them.
--
-- Two places in the code said the opposite of what the schema permitted:
-- ApprovalRequestController's decide operation ("An approval lets the maker's
-- identical resubmission proceed exactly once") and ApprovalOutcome.Approved
-- ("the action may proceed exactly once"). This is the missing state that makes
-- those sentences true.
--
-- CONSUMED is a decided status, not a fourth terminal decision: the request was
-- approved by a named checker and then exercised by the maker, so decided_by and
-- decided_at stay populated and ck_approval_request_decision is widened to say
-- so. consumed_by and consumed_at record who spent it and when, which is the
-- pair an investigator needs to line an execution up against the signature that
-- allowed it. Both are subject identifiers and instants; ADR 0029 keeps personal
-- data off this table and this change adds none.
--
-- No new table, so no new GRANT: V0007 granted the application role table-level
-- INSERT, SELECT and UPDATE on audit.approval_requests, and a table-level
-- privilege in PostgreSQL covers columns added later. The narrower column-level
-- discipline V0059 applied to approval_policies is not repeated here because a
-- request row is working state that its own service transitions, not a versioned
-- record that must never be rewritten.

ALTER TABLE audit.approval_requests
    ADD COLUMN consumed_at timestamptz,
    ADD COLUMN consumed_by varchar(255);

ALTER TABLE audit.approval_requests
    DROP CONSTRAINT ck_approval_request_status;

ALTER TABLE audit.approval_requests
    ADD CONSTRAINT ck_approval_request_status CHECK (
        status IN ('PENDING', 'APPROVED', 'DECLINED', 'EXPIRED', 'CONSUMED')
    );

ALTER TABLE audit.approval_requests
    DROP CONSTRAINT ck_approval_request_decision;

ALTER TABLE audit.approval_requests
    ADD CONSTRAINT ck_approval_request_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status = 'EXPIRED' AND decided_by IS NULL)
        OR (status IN ('APPROVED', 'DECLINED', 'CONSUMED')
            AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
    );

-- A spent approval names its spender, and an unspent one never claims to have
-- been spent. Without this half, a bug that set the status and forgot the
-- columns would leave a request that is refused as used and cannot say by whom.
ALTER TABLE audit.approval_requests
    ADD CONSTRAINT ck_approval_request_consumption CHECK (
        (status = 'CONSUMED' AND consumed_at IS NOT NULL AND consumed_by IS NOT NULL)
        OR (status <> 'CONSUMED' AND consumed_at IS NULL AND consumed_by IS NULL)
    );

COMMENT ON COLUMN audit.approval_requests.consumed_at IS
    'When the approved action was executed. Set in the same transaction as the effect, so an action that rolled back leaves the approval usable.';
COMMENT ON COLUMN audit.approval_requests.consumed_by IS
    'The subject that executed the approved action. Ordinarily requested_by; the four-eyes rule governs who decides, not who executes.';

COMMENT ON TABLE audit.approval_requests IS
    'ADR 0027 maker-checker requests. The policy version is snapshotted so a later policy change cannot alter what was approved. An approval is single-use: executing under it moves the row to CONSUMED, and the identical resubmission raises a new request.';
