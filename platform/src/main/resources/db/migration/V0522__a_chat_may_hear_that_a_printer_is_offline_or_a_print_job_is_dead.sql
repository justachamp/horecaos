-- ADR 0154, "Events, alerts and metrics": the two things an operator watches in printing are ADR 0058
-- operations alerts, and a chat can only hear what its subscription's event class admits.
--
-- PRINTER_OFFLINE: a branch printer the agent reports unreachable while a kitchen ticket is waiting on
-- it for two minutes. PRINT_JOB_DEAD: a job that used all six attempts. Neither was in the closed
-- TelegramEventClass set, so an alert raised under either would have reached no one ("silent on no
-- subscriber").
--
-- The full current list is restated, as V0488 does and for the same reason: a bare ADD/DROP pair on a
-- CHECK silently drops every value the previous migration did not know about. ADR 0155 widens the same
-- list (TERMINAL_CREDENTIAL_LOCKED, TERMINAL_DEVICE_UNSEEN) in a parallel wave: the last of the two to
-- merge carries every value in force.
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
            'MARKETPLACE_CHANNEL_STALE',
            'PRINTER_OFFLINE', 'PRINT_JOB_DEAD'));
