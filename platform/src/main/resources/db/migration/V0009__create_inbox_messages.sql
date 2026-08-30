-- ADR 0005: duplicate-safe consumption.
--
-- Kafka delivery is at-least-once by design (ADR 0004), and a consumer can also
-- see a record again after a rebalance or after processing succeeds but the
-- offset commit fails. Correctness therefore cannot depend on broker delivery
-- semantics. This table is where a consumer proves it has already acted.

CREATE TABLE integration.inbox_messages (
    id uuid PRIMARY KEY,
    consumer_name varchar(128) NOT NULL,
    event_id uuid NOT NULL,
    topic varchar(249) NOT NULL,
    partition integer NOT NULL,
    record_offset bigint NOT NULL,
    tenant_id uuid NOT NULL,
    event_type varchar(128) NOT NULL,
    event_version integer NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    correlation_id varchar(128) NOT NULL,
    causation_id varchar(128),
    occurred_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    payload_sha256 char(64) NOT NULL,
    status varchar(24) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    processing_token uuid,
    processing_started_at timestamptz,
    processed_at timestamptz,
    dead_lettered_at timestamptz,
    last_error_code varchar(64),
    last_error varchar(2000),
    received_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    -- Semantic deduplication. The key is per consumer, not global: two
    -- consumers must be able to process the same event independently, and
    -- replaying one must not replay the other.
    CONSTRAINT uq_inbox_consumer_event UNIQUE (consumer_name, event_id),

    -- Transport diagnostics: which record carried this event.
    CONSTRAINT uq_inbox_consumer_record UNIQUE (consumer_name, topic, partition, record_offset),

    CONSTRAINT ck_inbox_partition CHECK (partition >= 0),
    CONSTRAINT ck_inbox_offset CHECK (record_offset >= 0),
    CONSTRAINT ck_inbox_event_version CHECK (event_version > 0),
    CONSTRAINT ck_inbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_inbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_inbox_payload_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_inbox_status CHECK (
        status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'RETRY_PENDING', 'DEAD_LETTER')
    ),
    CONSTRAINT ck_inbox_lifecycle CHECK (
        (status = 'RECEIVED'
            AND processing_token IS NULL AND processing_started_at IS NULL
            AND processed_at IS NULL AND dead_lettered_at IS NULL)
        OR
        (status = 'PROCESSING'
            AND processing_token IS NOT NULL AND processing_started_at IS NOT NULL
            AND processed_at IS NULL AND dead_lettered_at IS NULL)
        OR
        (status = 'PROCESSED'
            AND processing_token IS NULL AND processed_at IS NOT NULL
            AND dead_lettered_at IS NULL)
        OR
        (status = 'RETRY_PENDING'
            AND processing_token IS NULL
            AND processed_at IS NULL AND dead_lettered_at IS NULL)
        OR
        (status = 'DEAD_LETTER'
            AND processing_token IS NULL
            AND processed_at IS NULL AND dead_lettered_at IS NOT NULL)
    )
);

-- Due work for retry workers.
CREATE INDEX ix_inbox_due
    ON integration.inbox_messages (consumer_name, available_at)
    WHERE status IN ('RECEIVED', 'RETRY_PENDING');

-- Per-aggregate ordering, so a retrying item can block later state-changing
-- events for the same aggregate rather than letting them overtake it.
CREATE INDEX ix_inbox_aggregate_order
    ON integration.inbox_messages (consumer_name, topic, aggregate_id, occurred_at, event_id);

CREATE INDEX ix_inbox_tenant_time ON integration.inbox_messages (tenant_id, received_at DESC);

CREATE INDEX ix_inbox_dead_letter
    ON integration.inbox_messages (consumer_name, dead_lettered_at)
    WHERE status = 'DEAD_LETTER';

CREATE INDEX ix_inbox_stale_claim
    ON integration.inbox_messages (processing_started_at)
    WHERE status = 'PROCESSING';

COMMENT ON TABLE integration.inbox_messages IS
    'ADR 0005 consumer inbox. Deduplication key is (consumer_name, event_id); the business effect and the PROCESSED transition commit together.';

COMMENT ON COLUMN integration.inbox_messages.payload_sha256 IS
    'Detects a producer violating event immutability: the same event id arriving with a different payload is a contract collision, not a duplicate.';
