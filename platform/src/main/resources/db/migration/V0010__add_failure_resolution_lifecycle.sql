-- ADR 0006: governed retry, dead-letter inspection, and replay.
--
-- Adds an explicit RESOLVED terminal state to both the outbox and the inbox.
-- It is deliberately distinct from PUBLISHED and PROCESSED: an operator
-- deciding that no further processing is required is a different fact from the
-- platform completing the work, and collapsing the two would make operational
-- overrides invisible in every count and dashboard afterwards.

ALTER TABLE integration.outbox_events
    ADD COLUMN resolved_at timestamptz,
    ADD COLUMN resolved_by varchar(255),
    ADD COLUMN resolution_reason varchar(1000),
    ADD COLUMN resolution_evidence varchar(512),
    ADD COLUMN error_code varchar(64);

ALTER TABLE integration.outbox_events
    DROP CONSTRAINT ck_outbox_event_status,
    ADD CONSTRAINT ck_outbox_event_status CHECK (
        status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'DEAD_LETTER', 'RESOLVED')
    );

ALTER TABLE integration.outbox_events
    DROP CONSTRAINT ck_outbox_event_lifecycle,
    ADD CONSTRAINT ck_outbox_event_lifecycle CHECK (
        (status = 'PENDING'
            AND claim_token IS NULL AND claimed_at IS NULL
            AND published_at IS NULL AND dead_lettered_at IS NULL AND resolved_at IS NULL)
        OR (status = 'PUBLISHING'
            AND claim_token IS NOT NULL AND claimed_at IS NOT NULL
            AND published_at IS NULL AND dead_lettered_at IS NULL AND resolved_at IS NULL)
        OR (status = 'PUBLISHED'
            AND claim_token IS NULL AND claimed_at IS NULL
            AND published_at IS NOT NULL AND dead_lettered_at IS NULL AND resolved_at IS NULL)
        OR (status = 'DEAD_LETTER'
            AND claim_token IS NULL AND claimed_at IS NULL
            AND published_at IS NULL AND dead_lettered_at IS NOT NULL AND resolved_at IS NULL)
        OR (status = 'RESOLVED'
            AND published_at IS NULL AND resolved_at IS NOT NULL
            AND resolved_by IS NOT NULL AND resolution_reason IS NOT NULL)
    );

ALTER TABLE integration.inbox_messages
    ADD COLUMN resolved_at timestamptz,
    ADD COLUMN resolved_by varchar(255),
    ADD COLUMN resolution_reason varchar(1000),
    ADD COLUMN resolution_evidence varchar(512);

ALTER TABLE integration.inbox_messages
    DROP CONSTRAINT ck_inbox_status,
    ADD CONSTRAINT ck_inbox_status CHECK (
        status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'RETRY_PENDING', 'DEAD_LETTER', 'RESOLVED')
    );

ALTER TABLE integration.inbox_messages
    DROP CONSTRAINT ck_inbox_lifecycle,
    ADD CONSTRAINT ck_inbox_lifecycle CHECK (
        (status = 'RECEIVED'
            AND processing_token IS NULL AND processing_started_at IS NULL
            AND processed_at IS NULL AND dead_lettered_at IS NULL AND resolved_at IS NULL)
        OR (status = 'PROCESSING'
            AND processing_token IS NOT NULL AND processing_started_at IS NOT NULL
            AND processed_at IS NULL AND dead_lettered_at IS NULL AND resolved_at IS NULL)
        OR (status = 'PROCESSED'
            AND processing_token IS NULL AND processed_at IS NOT NULL
            AND dead_lettered_at IS NULL AND resolved_at IS NULL)
        OR (status = 'RETRY_PENDING'
            AND processing_token IS NULL
            AND processed_at IS NULL AND dead_lettered_at IS NULL AND resolved_at IS NULL)
        OR (status = 'DEAD_LETTER'
            AND processing_token IS NULL
            AND processed_at IS NULL AND dead_lettered_at IS NOT NULL AND resolved_at IS NULL)
        OR (status = 'RESOLVED'
            AND processed_at IS NULL AND resolved_at IS NOT NULL
            AND resolved_by IS NOT NULL AND resolution_reason IS NOT NULL)
    );

CREATE INDEX ix_outbox_events_resolved
    ON integration.outbox_events (resolved_at)
    WHERE status = 'RESOLVED';

CREATE INDEX ix_inbox_messages_resolved
    ON integration.inbox_messages (resolved_at)
    WHERE status = 'RESOLVED';

COMMENT ON COLUMN integration.outbox_events.resolved_at IS
    'ADR 0006: an authorized operator established that no further processing is required. Not the same as published.';
COMMENT ON COLUMN integration.inbox_messages.resolved_at IS
    'ADR 0006: an authorized operator established that no further processing is required. Not the same as processed.';
