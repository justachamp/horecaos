-- ADR 0141 (Accepted 2026-10-01), gap map row 2.5a: what each marketplace is
-- TOLD about each mapped item, and what the platform believes the partner holds.
--
-- One row per (binding, mapped item): the ADR 0040 MENU_ITEM mapping's external
-- id on one MARKETPLACE binding. The reconciler is LEVEL-TRIGGERED, not an
-- edge-triggered consumer of inventory.events: each row keeps the DESIRED
-- availability, recomputed from inventory's one resolver, and CONFIRMED, what the
-- partner is KNOWN to hold. A worker sends whenever the two differ or the
-- partner's state is unknown. So an outage replays no backlog (the partner gets
-- the current truth, never an hour of intermediate states), a lost event leaves
-- nothing wrong for longer than one resync interval, and a stop and a lift that
-- both happen while no push could have reached the partner collapse into nothing
-- to send.
--
-- confirmed_available NULL means "not known", and that is the load-bearing value:
-- a new mapping starts NULL, which makes the first push of any item
-- unconditional, and an attempt whose outcome is UNKNOWN (a timeout or a reset
-- after the request was written, a 5xx that promises no atomicity) sets it back
-- to NULL, because the partner may have applied it. Without that, a timed-out
-- restore the partner DID apply, followed by a stop, would diff false against a
-- stale confirmed false, mark the row IN_SYNC and send nothing while the partner
-- sells the stopped dish.
--
-- state is derived, never authoritative: a row is IN_SYNC only when
-- confirmed_available is KNOWN and equals desired_available. The CHECK writes
-- IS NOT NULL out, because a CHECK passes on NULL and "in sync with an unknown"
-- is exactly the bug.
--
-- No personal data lives here: identifiers, booleans, timestamps and stable
-- codes. last_failure_code is a code from a closed set, never a provider's own
-- error body (ADR 0029).

CREATE TABLE integration.marketplace_item_availability (
    tenant_id uuid NOT NULL,
    binding_id uuid NOT NULL,
    external_entity_id varchar(255) NOT NULL,
    variant_id uuid NOT NULL,
    location_id uuid,

    desired_available boolean NOT NULL,
    -- Advances only when desired_available actually changes. The state-set call is
    -- keyed (binding, item, desired_seq), which makes a retry naturally idempotent.
    desired_seq bigint NOT NULL DEFAULT 1,
    desired_at timestamptz NOT NULL,

    confirmed_available boolean,
    confirmed_at timestamptz,

    last_attempt_available boolean,
    last_attempt_at timestamptz,
    -- What the gateway route CONCLUDED about the attempt, not a FailureCategory:
    -- FailureClassifier files a SocketTimeoutException and a ConnectException under
    -- the same TRANSIENT_INFRASTRUCTURE, and only one of them leaves the partner's
    -- state as it was.
    last_attempt_outcome varchar(16),

    state varchar(24) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_failure_code varchar(48),
    -- Since when desired and confirmed have disagreed: "7 items not confirmed since
    -- 14:32". Null while IN_SYNC.
    pending_since timestamptz,

    -- The lease a worker holds while it sends (the JdbcSourcingJobStore pattern):
    -- two overlapping runs claim disjoint rows, and a crashed worker's lease lapses.
    lease_owner varchar(64),
    lease_expires_at timestamptz,

    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (binding_id, external_entity_id),

    CONSTRAINT fk_marketplace_item_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_marketplace_item_state CHECK (
        state IN ('IN_SYNC', 'PENDING', 'UNCERTAIN', 'REJECTED_UNMAPPED', 'SUSPENDED')),
    CONSTRAINT ck_marketplace_item_outcome CHECK (
        last_attempt_outcome IS NULL OR last_attempt_outcome IN ('CONFIRMED', 'NOT_APPLIED', 'UNKNOWN')),
    CONSTRAINT ck_marketplace_item_seq CHECK (desired_seq >= 1),
    CONSTRAINT ck_marketplace_item_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_marketplace_item_failure_code CHECK (
        last_failure_code IS NULL OR last_failure_code ~ '^[A-Z0-9_]{1,48}$'),
    -- IS NOT NULL written out: a CHECK passes on NULL, and "in sync with an unknown"
    -- is the bug this table exists to make unrepresentable.
    CONSTRAINT ck_marketplace_item_in_sync CHECK (
        state <> 'IN_SYNC'
        OR (confirmed_available IS NOT NULL AND confirmed_available = desired_available)),
    CONSTRAINT ck_marketplace_item_uncertain CHECK (state <> 'UNCERTAIN' OR confirmed_available IS NULL),
    CONSTRAINT ck_marketplace_item_lease CHECK (
        (lease_owner IS NULL) = (lease_expires_at IS NULL))
);

-- The worker's claim: rows that still need a call, stops first.
CREATE INDEX ix_marketplace_item_work
    ON integration.marketplace_item_availability (next_attempt_at, desired_available)
    WHERE state IN ('PENDING', 'UNCERTAIN');

-- The console's read: one binding's items by state.
CREATE INDEX ix_marketplace_item_binding_state
    ON integration.marketplace_item_availability (tenant_id, binding_id, state);

CREATE INDEX ix_marketplace_item_variant
    ON integration.marketplace_item_availability (tenant_id, variant_id);

COMMENT ON TABLE integration.marketplace_item_availability IS
    'ADR 0141. Per (MARKETPLACE binding, mapped MENU_ITEM): desired availability recomputed from the one resolver, and confirmed_available, what the partner is KNOWN to hold (NULL = unknown). A level-triggered reconciler sends whenever they differ or the partner state is unknown.';
COMMENT ON COLUMN integration.marketplace_item_availability.confirmed_available IS
    'NULL means not known: a new mapping, or an attempt whose outcome was UNKNOWN. A NULL always sends.';
COMMENT ON COLUMN integration.marketplace_item_availability.last_attempt_outcome IS
    'CONFIRMED | NOT_APPLIED | UNKNOWN: what the gateway route concluded about the partner state after the attempt (ADR 0141 Decision 7).';

GRANT SELECT, INSERT, UPDATE, DELETE ON integration.marketplace_item_availability TO horecaos_application;
