-- ADR 0030: remove the last duplicate implementation of scoped resolution.
--
-- ordering.order_acceptance_policies predates the shared mechanism and
-- implements the same scoped-versioned pattern independently. Two
-- implementations of one rule drift: they disagree about precedence, about what
-- an explicit null means, and about how a decision pins the version it used, and
-- the disagreement only surfaces when a policy changes and a historical order
-- becomes unexplainable. It is migrated while it still holds no production data.

INSERT INTO tenant.policies (
    id, key_code, scope_type, tenant_id, brand_id, location_id,
    version, status, document, document_hash, valid_from, valid_until, created_by, retired_at)
SELECT
    p.id,
    'ordering.acceptance',
    CASE
        WHEN p.location_id IS NOT NULL THEN 'LOCATION'
        WHEN p.brand_id IS NOT NULL THEN 'BRAND'
        ELSE 'TENANT'
    END,
    p.tenant_id,
    p.brand_id,
    p.location_id,
    p.version,
    CASE WHEN p.superseded_at IS NULL THEN 'ACTIVE' ELSE 'RETIRED' END,
    jsonb_build_object(
        'mode', p.acceptance_mode,
        'approvalChannel', p.approval_channel,
        'approvalTimeoutSeconds', p.approval_timeout_seconds,
        'timeoutAction', p.timeout_action,
        'rejectionReasonRequired', p.rejection_reason_required,
        'notifyCustomerWhilePending', p.notify_customer_while_pending),
    encode(sha256(jsonb_build_object(
        'mode', p.acceptance_mode,
        'approvalChannel', p.approval_channel,
        'approvalTimeoutSeconds', p.approval_timeout_seconds,
        'timeoutAction', p.timeout_action,
        'rejectionReasonRequired', p.rejection_reason_required,
        'notifyCustomerWhilePending', p.notify_customer_while_pending)::text::bytea), 'hex'),
    p.effective_from,
    p.superseded_at,
    'migration:V0012',
    p.superseded_at
FROM ordering.order_acceptance_policies p;

-- The active version per scope, which the specialised table expressed through
-- partial unique indexes on superseded_at.
INSERT INTO tenant.policy_current (
    key_code, scope_type, tenant_id, brand_id, location_id,
    policy_id, policy_version, activated_by)
SELECT
    'ordering.acceptance',
    CASE
        WHEN p.location_id IS NOT NULL THEN 'LOCATION'
        WHEN p.brand_id IS NOT NULL THEN 'BRAND'
        ELSE 'TENANT'
    END,
    p.tenant_id,
    p.brand_id,
    p.location_id,
    p.id,
    p.version,
    'migration:V0012'
FROM ordering.order_acceptance_policies p
WHERE p.superseded_at IS NULL;

DROP TABLE ordering.order_acceptance_policies;

COMMENT ON TABLE tenant.policies IS
    'ADR 0030 versioned policy documents, including ordering.acceptance migrated from its own table in V0012. Immutable once referenced; editing creates a new version.';
