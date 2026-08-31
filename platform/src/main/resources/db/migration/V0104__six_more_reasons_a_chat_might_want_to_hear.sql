-- ADR 0058: the trigger-listener build-out for every operations event class
-- outside ordering (payments, fiscal, inventory, integration).
--
-- V0099's own comment named this moment: "admitting their values here before
-- a trigger can ever write them is the same mistake V0038's comment on
-- ck_installation_category warns against" — and now six of them can. This is
-- the DROP/ADD that comment pre-announced, widening
-- ck_telegram_binding_event_class to the event classes
-- PaymentOperationsAlertTrigger, FiscalOperationsAlertTrigger,
-- InventoryOperationsAlertTrigger and IntegrationOperationsAlertTrigger now
-- publish:
--
--   PAYMENT_ATTEMPT_FAILED         - an attempt reached FAILED (ADR 0013)
--   PAYMENT_ATTEMPT_NEEDS_OPERATOR - UncertaintyResolver reached
--                                    OPERATIONS_EXCEPTION: a human, not a retry
--   FISCAL_DOCUMENT_BLOCKED        - a fiscal obligation entered BLOCKED
--                                    (ADR 0038's worklist)
--   ITEM_86D                       - an item's availability toggled off
--   DEAD_LETTER_RECORDED           - an outbox/inbox dead letter was written
--                                    (ADR 0006), resolvable to an order
--   POS_EXPORT_AWAITING_OPERATOR   - a POS export reached AWAITING_OPERATOR
--
-- Still out of scope, unchanged from V0099's own list: the provider circuit
-- breaker opening (platform-scoped, no tenant dimension — routed to the
-- control-plane audience instead, which this migration does not touch) and
-- fulfillment, which is not part of this build.
ALTER TABLE integration.telegram_binding_events
    DROP CONSTRAINT ck_telegram_binding_event_class;

-- The list below also re-states V0103's five digest classes. Each recreation
-- of this constraint replaces the whole list, so a migration that enumerates
-- only what it knew about at authoring time would silently revoke its
-- siblings' values — V0103 and this file were written in parallel worktrees
-- against the same base, and were reconciled to be cumulative at merge.
ALTER TABLE integration.telegram_binding_events
    ADD CONSTRAINT ck_telegram_binding_event_class CHECK (
        event_class IN (
            'ORDER_CONFIRMED', 'ORDER_REJECTED', 'ORDER_APPROVAL_DEADLINE_WARNING',
            'DIGEST_15M', 'DIGEST_HALF_DAY', 'DIGEST_DAY_CLOSE',
            'PLATFORM_DIGEST_HALF_DAY', 'PLATFORM_DIGEST_DAY_CLOSE',
            'PAYMENT_ATTEMPT_FAILED', 'PAYMENT_ATTEMPT_NEEDS_OPERATOR',
            'FISCAL_DOCUMENT_BLOCKED',
            'ITEM_86D',
            'DEAD_LETTER_RECORDED', 'POS_EXPORT_AWAITING_OPERATOR'));

COMMENT ON CONSTRAINT ck_telegram_binding_event_class ON integration.telegram_binding_events IS
    'ADR 0058 stage 1 (ordering) plus the payments/fiscal/inventory/integration '
    'trigger listeners this migration''s wave adds. Onboarding''s stuck-run '
    'warning and ops/bands'' tier escalations are control-plane audience, not '
    'a tenant chat subscription, so neither value belongs in this list; the '
    'provider circuit breaker is platform-scoped for the same reason.';
