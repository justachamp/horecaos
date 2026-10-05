-- ADR 0141 "When the partner is down", ADR 0058: the operations alert the marketplace
-- availability reconciler raises when a binding has had a dish unconfirmed for longer than
-- its bound (MarketplaceStaleChannelMonitor) fans out through the same
-- OperationsAlertFanoutService / telegram_binding_events subscription machinery every other
-- operations event does, so a chat that wants to hear about it has to be able to subscribe to
-- it. MARKETPLACE_CHANNEL_STALE joins the enumerated event class list V0114 last restated.
--
-- Restating the full current list rather than adding a clause, per this migration's
-- predecessors' own warning: a bare ADD/DROP pair on a CHECK silently drops every value the
-- previous migration did not know about if the full list is not carried forward.
ALTER TABLE integration.telegram_binding_events DROP CONSTRAINT ck_telegram_binding_event_class;
ALTER TABLE integration.telegram_binding_events
    ADD CONSTRAINT ck_telegram_binding_event_class CHECK (
        event_class IN (
            'ORDER_CONFIRMED', 'ORDER_REJECTED', 'ORDER_APPROVAL_DEADLINE_WARNING',
            'ORDER_AWAITING_APPROVAL',
            'DIGEST_15M', 'DIGEST_HALF_DAY', 'DIGEST_DAY_CLOSE',
            'PLATFORM_DIGEST_HALF_DAY', 'PLATFORM_DIGEST_DAY_CLOSE',
            'PAYMENT_ATTEMPT_FAILED', 'PAYMENT_ATTEMPT_NEEDS_OPERATOR',
            'FISCAL_DOCUMENT_BLOCKED',
            'ITEM_86D',
            'DEAD_LETTER_RECORDED', 'POS_EXPORT_AWAITING_OPERATOR',
            'CAMPAIGN_BLOCK_RATE_PAUSED',
            'MARKETPLACE_CHANNEL_STALE'));
