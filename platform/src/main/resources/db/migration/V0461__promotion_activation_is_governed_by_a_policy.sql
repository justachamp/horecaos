-- ADR 0140: activating a markup, or a promotion whose largest percentage or fixed
-- amount crosses the thresholds read through ADR 0030, needs a second person
-- (ADR 0018 asks for four-eyes "above configured risk thresholds"; the ADR 0027
-- mechanism is how the platform asks for one).
--
-- The action `pricing.promotion.activate` is fail-closed (ApprovalAction): a
-- deployment that deleted the policy should stop large promotions, not let one
-- person decide to give the tenant's money away alone. The thresholds themselves
-- are ADR 0030 keys -- `pricing.promotion.approval.*` -- and the service asks for
-- approval only when one is crossed, so a small promotion still activates
-- directly. The policy is seeded at platform scope so the action is governed
-- from the first day rather than waiting for somebody to author it; a tenant may
-- narrow it with its own row.
INSERT INTO audit.approval_policies (
    id, tenant_id, action_code, scope_type, threshold_json, required_approver_capability,
    valid_from, version, approved_by)
VALUES (
    '0192d1a0-0000-7000-8000-000000000461', NULL, 'pricing.promotion.activate', 'PLATFORM',
    '{"description": "Activating a markup, or a promotion above the configured percentage or amount"}'::jsonb,
    'pricing.promotion.manage',
    '2026-10-01T00:00:00Z', 1, 'migration V0461');
