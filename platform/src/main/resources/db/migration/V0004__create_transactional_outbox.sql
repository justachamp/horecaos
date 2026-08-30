CREATE TABLE integration.outbox_events (
    event_id uuid PRIMARY KEY,
    event_type varchar(128) NOT NULL,
    event_version integer NOT NULL,
    tenant_id uuid NOT NULL,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id uuid NOT NULL,
    topic varchar(249) NOT NULL,
    partition_key varchar(255) NOT NULL,
    correlation_id varchar(128) NOT NULL,
    causation_id varchar(128),
    occurred_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    trace_context jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claim_token uuid,
    claimed_at timestamptz,
    published_at timestamptz,
    dead_lettered_at timestamptz,
    last_error varchar(2000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_outbox_event_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT ck_outbox_event_version CHECK (event_version > 0),
    CONSTRAINT ck_outbox_event_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_event_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_outbox_event_trace CHECK (jsonb_typeof(trace_context) = 'object'),
    CONSTRAINT ck_outbox_event_status CHECK (
        status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'DEAD_LETTER')
    ),
    CONSTRAINT ck_outbox_event_lifecycle CHECK (
        (status = 'PENDING'
            AND claim_token IS NULL
            AND claimed_at IS NULL
            AND published_at IS NULL
            AND dead_lettered_at IS NULL)
        OR
        (status = 'PUBLISHING'
            AND claim_token IS NOT NULL
            AND claimed_at IS NOT NULL
            AND published_at IS NULL
            AND dead_lettered_at IS NULL)
        OR
        (status = 'PUBLISHED'
            AND claim_token IS NULL
            AND claimed_at IS NULL
            AND published_at IS NOT NULL
            AND dead_lettered_at IS NULL)
        OR
        (status = 'DEAD_LETTER'
            AND claim_token IS NULL
            AND claimed_at IS NULL
            AND published_at IS NULL
            AND dead_lettered_at IS NOT NULL)
    )
);

CREATE INDEX ix_outbox_events_pending
    ON integration.outbox_events (next_attempt_at, occurred_at, event_id)
    WHERE status = 'PENDING';

CREATE INDEX ix_outbox_events_stale_claim
    ON integration.outbox_events (claimed_at, event_id)
    WHERE status = 'PUBLISHING';

CREATE INDEX ix_outbox_events_partition_order
    ON integration.outbox_events (topic, partition_key, occurred_at, event_id)
    WHERE status IN ('PENDING', 'PUBLISHING', 'DEAD_LETTER');

CREATE INDEX ix_outbox_events_tenant_occurred
    ON integration.outbox_events (tenant_id, occurred_at DESC);

CREATE INDEX ix_outbox_events_dead_letter
    ON integration.outbox_events (dead_lettered_at DESC)
    WHERE status = 'DEAD_LETTER';

COMMENT ON TABLE integration.outbox_events IS
    'Tenant-aware domain events committed with business state and relayed to Kafka at least once';
COMMENT ON COLUMN integration.outbox_events.claim_token IS
    'Lease owner token used to reject completion by a stale relay worker';
COMMENT ON COLUMN integration.outbox_events.last_error IS
    'Sanitized and length-bounded final error from the most recent publication attempt';
