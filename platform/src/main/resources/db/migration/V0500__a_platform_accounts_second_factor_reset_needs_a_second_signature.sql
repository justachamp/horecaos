-- ADR 0148: removing a platform administrator's second factor needs a second signature.
--
-- The action `iam.staff.mfa.reset` is fail-closed (ApprovalAction, ADR 0050): a
-- platform account can enter any tenant (ADR 0081) and change platform policy, and
-- a reset is the one act that turns its two factors back into one. A deployment
-- that deleted this policy should stop resets, not let one administrator perform
-- them alone. The policy is seeded at platform scope so the action is governed
-- from the first day rather than waiting for somebody to author it.
--
-- The approver must hold the capability the reset itself needs, which at platform
-- scope only an administrator does; the approver is never the requester
-- (ApprovalService.decide).
INSERT INTO audit.approval_policies (
    id, tenant_id, action_code, scope_type, threshold_json, required_approver_capability,
    valid_from, version, approved_by)
VALUES (
    '0192d1a0-0000-7000-8000-000000000500', NULL, 'iam.staff.mfa.reset', 'PLATFORM',
    '{"description": "Removing a platform account''s second factor, every time"}'::jsonb,
    'iam.staff.mfa.reset',
    '2026-10-07T00:00:00Z', 1, 'migration V0500');
