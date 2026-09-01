-- ADR 0059 stage 4: the block-rate guard's operator alert (CampaignBlockRateMonitor)
-- fans out through the same OperationsAlertFanoutService/telegram_binding_events
-- subscription machinery every other operations event does, so a chat that wants
-- to hear about it has to be able to subscribe to it. CAMPAIGN_BLOCK_RATE_PAUSED
-- joins the enumerated event class list V0106 (following V0104's own precedent)
-- last restated.
--
-- Restating the full current list rather than adding a clause, per this
-- migration's own predecessors' own warning: a bare ADD/DROP pair on a CHECK
-- silently drops every value the previous migration did not know about if the
-- full list is not carried forward.
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
            'CAMPAIGN_BLOCK_RATE_PAUSED'));
