-- ADR 0027: make the maker-checker control reachable.
--
-- V0007 created audit.approval_policies and granted the application role SELECT
-- on it, and nothing has ever written a row. No migration seeds one, and until
-- this change no service or endpoint inserted one, so JdbcApprovalService's
-- resolvePolicy found nothing on every real deployment. requireApproval then
-- returned NOT_REQUIRED for every refund, courier payout, settlement close,
-- loyalty adjustment and onboarding step that is meant to need a second
-- signature, and the audit trail recorded each of them as legitimately not
-- requiring approval. The control was not failing. It was unreachable, and an
-- unreachable control looks exactly like one that decided the action was small
-- enough. Nothing could be authored even by an operator who wanted to, because
-- the role held SELECT and nothing else.
--
-- Table-level UPDATE is deliberately NOT granted, following V0007's discipline
-- of granting the narrowest privilege the work needs. A policy is versioned and
-- its version is snapshotted onto every approval request it produces, so
-- rewriting a threshold in place would silently change what a recorded
-- policy_version means: the evidence would say an approver saw words that no
-- longer exist anywhere. A change to a policy is therefore a new version row,
-- which needs INSERT and nothing more.
--
-- The single exception is valid_until, and it is not optional. Resolution picks
-- the highest version whose window is open, so a superseding row cannot retire
-- an earlier one on its own; closing the earlier version's window is the only
-- way to end it, and it is the only way to turn a policy off at all. That is
-- granted at column level, the narrowest privilege PostgreSQL can express, so
-- the guarantee rests on the grant rather than on a service remembering which
-- columns it may touch.
--
-- Ordering matters: revoking a privilege at table level also revokes every
-- column-level grant of that privilege, so the REVOKE comes first.

REVOKE UPDATE, DELETE, TRUNCATE ON audit.approval_policies FROM qoida_application;

GRANT INSERT ON audit.approval_policies TO qoida_application;
GRANT UPDATE (valid_until) ON audit.approval_policies TO qoida_application;

COMMENT ON TABLE audit.approval_policies IS
    'ADR 0027 maker-checker thresholds, authored through the control plane under approval.policy.manage. Append-only apart from valid_until: a change is a new version row, and closing a version''s window is how one is retired.';
