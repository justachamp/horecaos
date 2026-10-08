-- ADR 0146: what a delivery attempt keeps once a gateway has said something about
-- it, and where a receipt can find the attempt it is about.
--
-- Nothing here creates a table. Four additions to notifications.delivery_attempts,
-- one index per question that is asked of it, and one sequence.
--
-- provider_segments. The segments the provider says it billed (VAS reports `parts`
-- on /send). Null means "not reported", which is different from one. The platform
-- estimates segments before a campaign sends (marketing SmsSegments, ADR 0044) and
-- the tenant's cost ceiling is enforced against that estimate, so the figure the
-- gateway actually billed is stored beside it and a disagreement is counted
-- (horecaos.sms.segments.mismatch) rather than assumed away.
--
-- receipt_state. 'NO_RECEIPT' only, and only ever derived: set by a sweeper for an
-- attempt whose provider accepted it and never reported on it, for a provider type
-- whose receipt endpoint is enabled. It is not a provider status and not a status
-- event, because it is the absence of one. For a provider type with no receipt
-- source it stays null however old the attempt is: with nothing listening, silence
-- says nothing, and reporting it as a state would be a claim the platform cannot
-- support (ADR 0146 Decision 5). A receipt that arrives late clears it.
--
-- status 'FAILED'. The provider took the message and later said it will not arrive
-- (a failed, rejected or blacklisted receipt, or a search that found it in such a
-- state). The set had no word for that: REJECTED means the provider refused the
-- request itself, and reusing it would erase the difference between "we were
-- refused" and "we were accepted and then it failed", which is the difference a
-- support conversation turns on. Terminal, like DELIVERED, and never resent: a retry
-- is a second message and a second charge.

ALTER TABLE notifications.delivery_attempts
    ADD COLUMN provider_segments integer,
    ADD COLUMN receipt_state varchar(16);

ALTER TABLE notifications.delivery_attempts
    ADD CONSTRAINT ck_attempt_provider_segments CHECK (
        provider_segments IS NULL OR provider_segments >= 0
    ),
    ADD CONSTRAINT ck_attempt_receipt_state CHECK (
        receipt_state IS NULL OR receipt_state = 'NO_RECEIPT'
    ),
    -- NO_RECEIPT describes an accepted attempt. A delivered, failed or refused one
    -- has been reported on, so the two cannot both be true.
    ADD CONSTRAINT ck_attempt_receipt_state_accepted CHECK (
        receipt_state IS NULL OR status = 'ACCEPTED'
    );

ALTER TABLE notifications.delivery_attempts DROP CONSTRAINT ck_attempt_status;
ALTER TABLE notifications.delivery_attempts
    ADD CONSTRAINT ck_attempt_status CHECK (
        status IN ('REQUESTED', 'ACCEPTED', 'DELIVERED', 'FAILED', 'REJECTED',
                   'RETRYABLE_FAILURE', 'UNCERTAIN', 'RECONCILED_NOT_SENT')
    );

COMMENT ON COLUMN notifications.delivery_attempts.provider_segments IS
    'ADR 0146 Decision 7. The segments the provider says it billed; null when it did not say. Compared with the platform estimate and a mismatch is a metric.';
COMMENT ON COLUMN notifications.delivery_attempts.receipt_state IS
    'ADR 0146 Decision 5. NO_RECEIPT, derived by a sweeper, only for a provider type whose receipt endpoint is enabled. Null is the absence of a claim, not the presence of a delivery.';

-- A receipt arrives with the provider's message id and the installation it was
-- sent to, and nothing else. The attempt it is about is the one made through a
-- binding of that installation under that id. tenant_id leads because every read
-- is tenant-scoped.
CREATE INDEX ix_attempts_external_message
    ON notifications.delivery_attempts (tenant_id, provider_binding_id, external_message_id)
    WHERE external_message_id IS NOT NULL;

-- What the "no receipt" sweeper scans: accepted attempts nobody has reported on.
-- Partial on exactly that set, because the overwhelming majority of attempts are
-- terminal within seconds.
CREATE INDEX ix_attempts_awaiting_receipt
    ON notifications.delivery_attempts (requested_at)
    WHERE status = 'ACCEPTED' AND receipt_state IS NULL AND provider_type IS NOT NULL;

-- A receipt is written through the ADR 0005 inbox, whose key includes a transport
-- position. An HTTP callback has none, so one is minted: a sequence is the
-- cheapest value that is unique per arrival and says nothing about the message.
CREATE SEQUENCE integration.provider_receipt_inbox_position_seq;

GRANT USAGE, SELECT ON SEQUENCE integration.provider_receipt_inbox_position_seq TO horecaos_application;
