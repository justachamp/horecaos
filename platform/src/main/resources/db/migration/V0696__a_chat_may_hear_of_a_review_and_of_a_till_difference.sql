-- ADR 0187 / ADR 0058 / ADR 0071 / ADR 0171: an operations chat may hear that a customer left a positive or a
-- negative review, and that the loyalty ledger and a till disagree in a way a person has to look at.
--
--   REVIEW_POSITIVE          a review of 4 or 5 stars was submitted
--   REVIEW_NEGATIVE          a review of 1 to 3 stars was submitted
--   LOYALTY_SYNC_DISCREPANCY a point-of-sale sync pass opened a difference that needs a person (ADR 0171, Decision 6)
--
-- None is in any default subscription set: a chat hears these only if it subscribed, so a subscription is the
-- opt-in (the recovery alerts need a policy switch as well because they start clocks; a review alert starts none).
--
-- Restated from the LIVE constraint -- V0665's list (V0544's plus TILL_REVISION_NEEDED), the last migration to widen
-- it -- never from an older copy: a bare DROP/ADD silently drops every value the previous migration knew about if the
-- full list is not carried forward. The batch-21 integration carried V0665's class into this list; a later migration
-- that restates the constraint must carry every class here.
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
            'PRINTER_OFFLINE', 'PRINT_JOB_DEAD',
            'TERMINAL_CREDENTIAL_LOCKED', 'TERMINAL_DEVICE_UNSEEN',
            'RECOVERY_CASE_OPENED', 'RECOVERY_CASE_SLA_BREACHED',
            'TILL_REVISION_NEEDED',
            'REVIEW_POSITIVE', 'REVIEW_NEGATIVE',
            'LOYALTY_SYNC_DISCREPANCY'));
